;; ---------------------------------------------------------
;; o11ylite.mcp.protocol
;;
;; Model Context Protocol (revision 2026-07-28) over Streamable HTTP,
;; server side, tools only.
;;
;; The 2026-07-28 revision is stateless: no initialize handshake and no
;; sessions. Every request carries its protocol version and client
;; capabilities in params._meta, and the transport mirrors the method
;; (and tool name) into Mcp-Method / Mcp-Name headers that must match
;; the body.
;;
;; This namespace has no O11yLite dependencies besides JSON encoding:
;; it takes a parsed request and a server description and returns a
;; Ring-style {:status :headers :body} map with :body as data. Auth,
;; Origin checks, and serialization live in o11ylite.routes.mcp.
;;
;; Server description:
;;   {:server-info  {:name "..." :version "..."}
;;    :instructions "..."
;;    :tools        [tool ...]               ; ordered, see below
;;    :check-access (fn [tool context])      ; nil, or a response map to short-circuit
;;    :on-error     (fn [throwable tool])}   ; report unexpected tool failures
;;
;; Tool:
;;   {:name :title :description :input-schema :annotations
;;    :handler (fn [context arguments] -> data)}
;; A handler returns any JSON-encodable value (sent as structuredContent
;; and as JSON text), or throws (tool-error ...) for failures the model
;; can fix, which become isError results.
;; ---------------------------------------------------------

(ns o11ylite.mcp.protocol
  (:require
    [clojure.string :as str]
    [jsonista.core :as jsonista]
    [o11ylite.util.json :as json])
  (:import
    [java.nio.charset StandardCharsets]
    [java.util Base64]))

;; ---------------------------------------------------------
;; Constants

(def protocol-version
  "The only protocol revision this server implements."
  "2026-07-28")

(def supported-versions
  "Protocol versions accepted on requests."
  [protocol-version])

(def ^:private list-ttl-ms
  "Freshness hint for tools/list and server/discover. The tool set only
   changes on upgrade, so a few minutes keeps clients' caches useful."
  (* 5 60 1000))

(def ^:private meta-protocol-version :io.modelcontextprotocol/protocolVersion)
(def ^:private meta-client-capabilities :io.modelcontextprotocol/clientCapabilities)
(def ^:private meta-client-info :io.modelcontextprotocol/clientInfo)

;; JSON-RPC 2.0 and MCP error codes
(def ^:private parse-error -32700)
(def ^:private invalid-request -32600)
(def ^:private method-not-found -32601)
(def ^:private invalid-params -32602)
(def ^:private header-mismatch -32020)
(def ^:private unsupported-protocol-version -32022)

;; ---------------------------------------------------------
;; Tool errors

(defn tool-error
  "Build an exception a tool handler throws to report a failure the
   model can act on (bad arguments, unknown id, validation errors).
   `details` is optional data appended as JSON."
  ([message] (tool-error message nil))
  ([message details]
   (ex-info message {::tool-error true ::details details})))

(defn- -tool-error?
  [e]
  (true? (::tool-error (ex-data e))))

;; ---------------------------------------------------------
;; Response builders

(defn- -error-response
  ([status id code message] (-error-response status id code message nil))
  ([status id code message data]
   {:status status
    :body {:jsonrpc "2.0"
           :id id
           :error (cond-> {:code code :message message}
                    data (assoc :data data))}}))

(defn- -result-response
  [server id result]
  {:status 200
   :body {:jsonrpc "2.0"
          :id id
          :result (merge {:resultType "complete"
                          :_meta {"io.modelcontextprotocol/serverInfo" (:server-info server)}}
                         result)}})

;; ---------------------------------------------------------
;; Header validation

(def ^:private base64-sentinel #"^=\?base64\?(.*)\?=$")

(defn decode-header-value
  "Decode the =?base64?...?= sentinel used for non-ASCII header values.
   Returns the value unchanged when it is not encoded, nil when the
   encoding is invalid."
  [value]
  (if-let [[_ encoded] (some->> value (re-matches base64-sentinel))]
    (try
      (String. (.decode (Base64/getDecoder) ^String encoded) StandardCharsets/UTF_8)
      (catch IllegalArgumentException _ nil))
    value))

(defn- -check-headers
  "Validate the mirrored request headers against the body.
   Returns an error response or nil."
  [headers id method params]
  (let [version-header (get headers "mcp-protocol-version")
        method-header (get headers "mcp-method")
        meta-version (get-in params [:_meta meta-protocol-version])]
    (cond
      (str/blank? version-header)
      (-error-response 400 id header-mismatch "Missing MCP-Protocol-Version header")

      (not= method-header method)
      (-error-response 400 id header-mismatch
                       (str "Header mismatch: Mcp-Method header value '" method-header
                            "' does not match body value '" method "'"))

      (or (not (string? meta-version))
          (not (map? (get-in params [:_meta meta-client-capabilities]))))
      (-error-response 400 id invalid-params
                       (str "params._meta must include " (subs (str meta-protocol-version) 1)
                            " and " (subs (str meta-client-capabilities) 1)))

      (not= version-header meta-version)
      (-error-response 400 id header-mismatch
                       "Header mismatch: MCP-Protocol-Version header does not match params._meta")

      (not (some #{meta-version} supported-versions))
      (-error-response 400 id unsupported-protocol-version "Unsupported protocol version"
                       {:supported supported-versions
                        :requested meta-version}))))

(defn- -check-name-header
  [headers id tool-name]
  (let [raw (get headers "mcp-name")]
    (when-not (and raw (= (decode-header-value raw) tool-name))
      (-error-response 400 id header-mismatch
                       (str "Header mismatch: Mcp-Name header value '" raw
                            "' does not match body value '" tool-name "'")))))

;; ---------------------------------------------------------
;; Methods

(defn- -tool-definition
  [{:keys [name title description input-schema annotations]}]
  (cond-> {:name name
           :description description
           :inputSchema input-schema}
    title (assoc :title title)
    annotations (assoc :annotations annotations)))

(defn- -discover
  [server id _context _params]
  (-result-response server id
                    (cond-> {:supportedVersions supported-versions
                             :capabilities {:tools {}}
                             :ttlMs list-ttl-ms
                             :cacheScope "public"}
                      (:instructions server) (assoc :instructions (:instructions server)))))

(defn- -list-tools
  [server id _context _params]
  ;; Single page: the tool set is small, so nextCursor is never set.
  (-result-response server id {:tools (mapv -tool-definition (:tools server))
                               :ttlMs list-ttl-ms
                               :cacheScope "public"}))

(defn- -tool-result
  [data]
  {:content [{:type "text" :text (json/write-str data)}]
   :structuredContent data
   :isError false})

(defn- -tool-error-result
  [message details]
  {:content [{:type "text"
              :text (cond-> message
                      details (str "\n" (json/write-str details)))}]
   :isError true})

(defn- -run-tool
  [server tool context arguments]
  (try
    (-tool-result ((:handler tool) context arguments))
    (catch clojure.lang.ExceptionInfo e
      (if (-tool-error? e)
        (-tool-error-result (ex-message e) (::details (ex-data e)))
        (do (when-let [on-error (:on-error server)] (on-error e tool))
            (-tool-error-result "Internal error while running the tool" nil))))
    (catch Exception e
      (when-let [on-error (:on-error server)] (on-error e tool))
      (-tool-error-result "Internal error while running the tool" nil))))

(defn- -call-tool
  [server {:keys [id context params headers]}]
  (let [tool-name (:name params)
        arguments (:arguments params {})
        tool (first (filter #(= tool-name (:name %)) (:tools server)))]
    (cond
      (not (string? tool-name))
      (-error-response 400 id invalid-params "params.name is required")

      :else
      (or (-check-name-header headers id tool-name)
          (cond
            (nil? tool)
            (-error-response 200 id invalid-params (str "Unknown tool: " tool-name))

            (not (map? arguments))
            (-error-response 200 id invalid-params "params.arguments must be an object")

            :else
            (or (when-let [check (:check-access server)] (check tool context))
                (-result-response server id (-run-tool server tool context arguments))))))))

;; ---------------------------------------------------------
;; Public API

(defn request-info
  "Attributes describing a parsed message, for telemetry.
   Keys follow the OTel MCP semantic conventions."
  [message]
  (when (map? message)
    (let [params (:params message)]
      (cond-> {}
        (string? (:method message)) (assoc :mcp.method.name (:method message))
        (some? (:id message)) (assoc :jsonrpc.request.id (str (:id message)))
        (string? (:name params)) (assoc :gen_ai.tool.name (:name params))
        (get-in params [:_meta meta-protocol-version])
        (assoc :mcp.protocol.version (str (get-in params [:_meta meta-protocol-version])))
        (get-in params [:_meta meta-client-info :name])
        (assoc :o11ylite.mcp.client_name (str (get-in params [:_meta meta-client-info :name])))))))

(defn parse-message
  "Parse a request body. Returns {:message m} or {:response r} for
   unparseable input."
  [body-str]
  (try
    {:message (jsonista/read-value body-str jsonista/keyword-keys-object-mapper)}
    (catch Exception _
      {:response (-error-response 400 nil parse-error "Parse error")})))

(defn handle-message
  "Handle one parsed JSON-RPC message.
   headers: Ring request headers (lower-case names).
   context: passed through to :check-access and tool handlers."
  [server {:keys [headers message context]}]
  (let [{:keys [jsonrpc id method params]} (when (map? message) message)
        has-id? (and (map? message) (contains? message :id))]
    (cond
      (or (not (map? message)) (not= "2.0" jsonrpc) (not (string? method)))
      (-error-response 400 (when (or (string? id) (integer? id)) id)
                       invalid-request "Invalid Request: expected a single JSON-RPC 2.0 request")

      ;; Notifications need no response. This revision defines no
      ;; client-to-server notifications over HTTP, so accept and ignore.
      (not has-id?)
      {:status 202 :body nil}

      (not (or (string? id) (integer? id)))
      (-error-response 400 nil invalid-request "Invalid Request: id must be a string or integer")

      (and (some? params) (not (map? params)))
      (-error-response 400 id invalid-params "params must be an object")

      :else
      (or (-check-headers headers id method params)
          (case method
            "server/discover" (-discover server id context params)
            "tools/list" (-list-tools server id context params)
            "tools/call" (-call-tool server {:id id :context context
                                             :params params :headers headers})
            (-error-response 404 id method-not-found (str "Method not found: " method)))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (def server {:server-info {:name "demo" :version "dev"}
               :tools [{:name "echo"
                        :description "Echo arguments"
                        :input-schema {:type "object"}
                        :handler (fn [_ args] args)}]})

  (handle-message server
                  {:headers {"mcp-protocol-version" "2026-07-28"
                             "mcp-method" "tools/call"
                             "mcp-name" "echo"}
                   :message {:jsonrpc "2.0" :id 1 :method "tools/call"
                             :params {:name "echo" :arguments {:x 1}
                                      :_meta {:io.modelcontextprotocol/protocolVersion "2026-07-28"
                                              :io.modelcontextprotocol/clientCapabilities {}}}}})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
