;; ---------------------------------------------------------
;; o11ylite.test-helpers.mcp
;;
;; HTTP helpers for MCP endpoint integration tests: build
;; 2026-07-28 requests (headers mirrored from the body, _meta) and
;; unwrap tool results.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers.mcp
  (:require
    [jsonista.core :as json]
    [o11ylite.test-helpers.http :as http]))

;; ---------------------------------------------------------
;; Request Helpers

(def request-meta
  "Per-request _meta every 2026-07-28 request carries."
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}
   "io.modelcontextprotocol/clientInfo" {"name" "o11ylite-test" "version" "1"}})

(defn- -parse
  [body]
  (when (seq body)
    (json/read-value body json/keyword-keys-object-mapper)))

(defn request
  "POST a JSON-RPC request to /mcp with correct transport headers.
   Options: :token (Bearer), :headers (extra/overrides).
   Returns the HTTP response with :body parsed as JSON."
  ([method params] (request method params {}))
  ([method params {:keys [token headers]}]
   (let [response (http/post "/mcp"
                             {:headers (cond-> {"Content-Type" "application/json"
                                                "Accept" "application/json, text/event-stream"
                                                "MCP-Protocol-Version" "2026-07-28"
                                                "Mcp-Method" method}
                                         (:name params) (assoc "Mcp-Name" (:name params))
                                         token (assoc "Authorization" (str "Bearer " token))
                                         true (merge headers))
                              :body (json/write-value-as-string
                                      {:jsonrpc "2.0"
                                       :id 1
                                       :method method
                                       :params (assoc params :_meta request-meta)})})]
     (assoc response :body (-parse (:body response))))))

(defn call-tool
  "Call a tool. Returns the HTTP response with parsed :body."
  ([tool-name arguments] (call-tool tool-name arguments {}))
  ([tool-name arguments opts]
   (request "tools/call" {:name tool-name :arguments arguments} opts)))

(defn tool-result
  "Call a tool and return its structuredContent; throws when the call
   did not produce a successful tool result."
  ([tool-name arguments] (tool-result tool-name arguments {}))
  ([tool-name arguments opts]
   (let [{:keys [status body]} (call-tool tool-name arguments opts)
         result (:result body)]
     (when-not (and (= 200 status) (false? (:isError result)))
       (throw (ex-info (str tool-name " failed") {:status status :body body})))
     (:structuredContent result))))

(defn tool-error-text
  "Call a tool expected to fail; return the isError text (nil if it succeeded)."
  ([tool-name arguments] (tool-error-text tool-name arguments {}))
  ([tool-name arguments opts]
   (let [result (get-in (call-tool tool-name arguments opts) [:body :result])]
     (when (:isError result)
       (get-in result [:content 0 :text])))))
