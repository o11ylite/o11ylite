;; ---------------------------------------------------------
;; o11ylite.routes.mcp
;;
;; MCP endpoint (Streamable HTTP, protocol revision 2026-07-28) and
;; its OAuth Protected Resource Metadata (RFC 9728).
;;
;; POST /mcp — one JSON-RPC request per HTTP request; responses are
;;   always single JSON objects (no SSE: no tool streams progress).
;; GET/DELETE /mcp — 405; this revision has no GET stream or sessions.
;; GET /.well-known/oauth-protected-resource[/mcp] — points clients at
;;   the built-in authorization server.
;;
;; Auth mirrors /api: open mode needs none; otherwise a Bearer token is
;; required — an API key, or an OAuth access token whose audience is
;; this endpoint. Session cookies are deliberately not accepted, so a
;; browser cannot be tricked into calling tools with the user's login.
;; ---------------------------------------------------------

(ns o11ylite.routes.mcp
  (:require
    [clojure.string :as str]
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.auth.public-url :as public-url]
    [o11ylite.auth.scope :as scope]
    [o11ylite.mcp.protocol :as protocol]
    [o11ylite.mcp.tools :as tools]
    [o11ylite.routes.oauth :as routes.oauth]
    [o11ylite.util.json :as json]
    [o11ylite.util.response :as response]
    [o11ylite.util.telemetry :as telemetry]
    [o11ylite.version :as version]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.net URI]))

;; ---------------------------------------------------------
;; Auth challenges (RFC 6750 §3, RFC 9728 §5.1)

(def ^:private challenge-scope
  "Scopes suggested to clients in the 401 challenge: enough for every tool."
  (str/join " " routes.oauth/scopes-supported))

(defn- -resource-metadata-url
  [auth-config request]
  (str (public-url/base-url auth-config request)
       "/.well-known/oauth-protected-resource" public-url/mcp-path))

(defn- -challenge
  [auth-config request {:keys [error description scope]}]
  (str "Bearer resource_metadata=\"" (-resource-metadata-url auth-config request) "\""
       ", scope=\"" scope "\""
       (when error (str ", error=\"" error "\""))
       (when description (str ", error_description=\"" description "\""))))

(defn- -auth-error
  [auth-config request status params]
  {:status status
   :headers {"Content-Type" "application/json"
             "WWW-Authenticate" (-challenge auth-config request params)}
   :body (json/write-str {:error (or (:error params) "unauthorized")
                          :error_description (:description params)})})

;; ---------------------------------------------------------
;; Request guards

(def ^:private loopback-hosts #{"localhost" "127.0.0.1" "[::1]"})

(defn- -allowed-origin?
  "With O11YLITE_PUBLIC_URL set, only its origin is allowed. Without it,
   only loopback origins are: the base URL would be derived from the Host
   header, which a DNS-rebinding page controls along with Origin."
  [auth-config request-origin]
  (if-let [configured (:public-url auth-config)]
    (= request-origin (public-url/origin configured))
    ;; URI.getHost keeps the brackets on IPv6 literals ("[::1]").
    (contains? loopback-hosts (some-> (public-url/canonical-uri request-origin) URI. .getHost))))

(defn- -check-origin
  "Reject cross-origin browser requests (DNS-rebinding protection).
   Non-browser clients send no Origin header."
  [auth-config request]
  (when-let [request-origin (get-in request [:headers "origin"])]
    (when-not (-allowed-origin? auth-config (str/lower-case request-origin))
      (response/json 403 {:jsonrpc "2.0"
                          :id nil
                          :error {:code -32600 :message "Origin not allowed"}}))))

(def ^:private max-body-bytes (* 1024 1024))

(defn- -read-body
  "Read the request body, or nil when it exceeds max-body-bytes."
  [request]
  (when-let [^java.io.InputStream in (:body request)]
    (let [buf (.readNBytes in (inc max-body-bytes))]
      (when (<= (alength buf) max-body-bytes)
        (String. buf "UTF-8")))))

(defn- -authenticate
  "Returns {:principal p} or {:response r}."
  [{:keys [auth-config] :as deps} request]
  (if (:open-mode? auth-config)
    {:principal {:type :open :scope "admin"}}
    (let [token (auth-mw/extract-bearer-token request)
          principal (when token
                      (auth-mw/bearer-principal deps request
                                                (public-url/mcp-resource auth-config request)))]
      (cond
        (nil? token)
        {:response (-auth-error auth-config request 401
                                {:scope challenge-scope
                                 :description "Authorization required"})}

        (nil? principal)
        {:response (-auth-error auth-config request 401
                                {:scope challenge-scope
                                 :error "invalid_token"
                                 :description "The access token is invalid, expired, or not issued for this resource"})}

        (not (scope/has-scope? (:scope principal) "read"))
        {:response (-auth-error auth-config request 403
                                {:scope "read"
                                 :error "insufficient_scope"
                                 :description "read scope required"})}

        :else
        {:principal principal}))))

;; ---------------------------------------------------------
;; MCP server

(defn- -make-check-access
  "Per-tool scope check. A token without the tool's scope gets 403 +
   insufficient_scope so clients can step up authorization."
  [auth-config request]
  (fn [tool {:keys [principal]}]
    (when-not (scope/has-scope? (:scope principal) (:scope tool))
      (-auth-error auth-config request 403
                   {:scope (:scope tool)
                    :error "insufficient_scope"
                    :description (str (:scope tool) " scope required for " (:name tool))}))))

(defn- -on-tool-error
  [e tool]
  (telemetry/report-error! ::tool-failed e :gen_ai.tool.name (:name tool)))

(defn- -server
  [auth-config request]
  {:server-info {:name "o11ylite" :version version/current}
   :instructions tools/instructions
   :tools tools/all
   :check-access (-make-check-access auth-config request)
   :on-error -on-tool-error})

(defn- -response-attributes
  [response]
  (let [body (:body response)]
    (cond-> {:http.response.status_code (:status response)}
      (get-in body [:error :code]) (assoc :rpc.jsonrpc.error_code (get-in body [:error :code]))
      (contains? (:result body) :isError) (assoc :o11ylite.mcp.tool_is_error
                                                 (boolean (get-in body [:result :isError]))))))

(defn- -to-ring
  "Serialize a protocol response. Responses produced by auth helpers
   already carry a string body."
  [response]
  (cond
    (nil? (:body response)) {:status (:status response) :headers {} :body ""}
    (string? (:body response)) response
    :else (response/json (:status response) (:body response))))

(defn- -make-mcp-handler
  "POST /mcp"
  [{:keys [auth-config sqlite duckdb blocked-fields] :as deps}]
  (fn [request]
    (or (-check-origin auth-config request)
        (let [{:keys [principal] auth-response :response} (-authenticate deps request)]
          (if auth-response
            auth-response
            (let [body (-read-body request)
                  {:keys [message] parse-response :response}
                  (if body
                    (protocol/parse-message body)
                    {:response {:status 413
                                :body {:jsonrpc "2.0" :id nil
                                       :error {:code -32600 :message "Request body too large"}}}})
                  _ (span/add-span-data! {:attributes (merge (protocol/request-info message)
                                                             {:o11ylite.mcp.principal_type
                                                              (name (:type principal))})})
                  response (or parse-response
                               (protocol/handle-message
                                 (-server auth-config request)
                                 {:headers (:headers request)
                                  :message message
                                  :context {:principal principal
                                            :base-url (public-url/base-url auth-config request)
                                            :deps {:sqlite sqlite
                                                   :duckdb duckdb
                                                   :blocked-fields blocked-fields}}}))]
              (span/add-span-data! {:attributes (-response-attributes response)})
              (-to-ring response)))))))

(defn- -method-not-allowed
  [_request]
  {:status 405
   :headers {"Allow" "POST" "Content-Type" "application/json"}
   :body (json/write-str {:error "method_not_allowed"
                          :error_description "The MCP endpoint only accepts POST"})})

;; ---------------------------------------------------------
;; Protected Resource Metadata (RFC 9728)

(defn- -make-resource-metadata-handler
  [{:keys [auth-config]}]
  (fn [request]
    (response/json 200 {:resource (public-url/mcp-resource auth-config request)
                        :authorization_servers [(public-url/base-url auth-config request)]
                        :scopes_supported routes.oauth/scopes-supported
                        :bearer_methods_supported ["header"]
                        :resource_name "O11yLite"})))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "MCP endpoint route."
  [deps]
  [public-url/mcp-path {:post {:handler (-make-mcp-handler deps)}
                        :get {:handler -method-not-allowed}
                        :delete {:handler -method-not-allowed}}])

(defn metadata-routes
  "Protected resource metadata, at the root and path-specific well-known URIs."
  [deps]
  (let [handler (-make-resource-metadata-handler deps)]
    [["/.well-known/oauth-protected-resource" {:get {:handler handler}}]
     [(str "/.well-known/oauth-protected-resource" public-url/mcp-path) {:get {:handler handler}}]]))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; curl -s localhost:3000/mcp \
  ;;   -H 'Content-Type: application/json' \
  ;;   -H 'MCP-Protocol-Version: 2026-07-28' -H 'Mcp-Method: tools/list' \
  ;;   -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":
  ;;        {"io.modelcontextprotocol/protocolVersion":"2026-07-28",
  ;;         "io.modelcontextprotocol/clientCapabilities":{}}}}'

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
