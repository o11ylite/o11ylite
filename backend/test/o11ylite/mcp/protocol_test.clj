;; ---------------------------------------------------------
;; o11ylite.mcp.protocol-test
;;
;; Unit tests for MCP (2026-07-28) message handling: header/_meta
;; validation, dispatch, and tool result shapes. Pure functions,
;; no system needed.
;; ---------------------------------------------------------

(ns o11ylite.mcp.protocol-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.mcp.protocol :as protocol]))

;; ---------------------------------------------------------
;; Helpers

(def ^:private meta-ok
  {:io.modelcontextprotocol/protocolVersion "2026-07-28"
   :io.modelcontextprotocol/clientCapabilities {}})

(def ^:private errors (atom []))

(def ^:private server
  {:server-info {:name "test" :version "1"}
   :instructions "hello"
   :tools [{:name "echo"
            :description "Echo"
            :input-schema {:type "object"}
            :scope "read"
            :handler (fn [_ args] {:echo args})}
           {:name "fail"
            :description "Tool error"
            :input-schema {:type "object"}
            :scope "read"
            :handler (fn [_ _] (throw (protocol/tool-error "bad input" {:field ["wrong"]})))}
           {:name "crash"
            :description "Unexpected error"
            :input-schema {:type "object"}
            :scope "read"
            :handler (fn [_ _] (throw (RuntimeException. "boom")))}
           {:name "secret"
            :description "Needs write"
            :input-schema {:type "object"}
            :scope "write"
            :handler (fn [_ _] {:ok true})}]
   :check-access (fn [tool {:keys [scope]}]
                   (when (and (= "write" (:scope tool)) (not= "write" scope))
                     {:status 403 :body "forbidden"}))
   :on-error (fn [e _tool] (swap! errors conj e))})

(defn- -request
  "Build a valid request; overrides merge into the message, headers into headers."
  [method params & {:keys [headers context]}]
  (protocol/handle-message
    server
    {:headers (merge {"mcp-protocol-version" "2026-07-28"
                      "mcp-method" method}
                     (when-let [n (:name params)] {"mcp-name" n})
                     headers)
     :message {:jsonrpc "2.0" :id 7 :method method
               :params (merge {:_meta meta-ok} params)}
     :context (or context {:scope "read"})}))

;; ---------------------------------------------------------
;; Tests

(deftest discover-and-list-test
  (testing "server/discover advertises version, tools capability, and cache hints"
    (let [{:keys [status body]} (-request "server/discover" {})
          result (:result body)]
      (is (= 200 status))
      (is (= 7 (:id body)))
      (is (= "complete" (:resultType result)))
      (is (= ["2026-07-28"] (:supportedVersions result)))
      (is (= {:tools {}} (:capabilities result)))
      (is (= "hello" (:instructions result)))
      (is (pos? (:ttlMs result)))
      (is (= "public" (:cacheScope result)))
      (is (= {:name "test" :version "1"}
             (get-in result [:_meta "io.modelcontextprotocol/serverInfo"])))))

  (testing "tools/list returns tools in declared order without internal keys"
    (let [tools (get-in (-request "tools/list" {}) [:body :result :tools])]
      (is (= ["echo" "fail" "crash" "secret"] (map :name tools)))
      (is (= #{:name :description :inputSchema} (set (keys (first tools))))))))

(deftest header-and-meta-validation-test
  (testing "missing MCP-Protocol-Version header"
    (let [resp (protocol/handle-message
                 server {:headers {"mcp-method" "tools/list"}
                         :message {:jsonrpc "2.0" :id 1 :method "tools/list"
                                   :params {:_meta meta-ok}}})]
      (is (= 400 (:status resp)))
      (is (= -32020 (get-in resp [:body :error :code])))))

  (testing "Mcp-Method header must match the body"
    (let [resp (-request "tools/list" {} :headers {"mcp-method" "tools/call"})]
      (is (= 400 (:status resp)))
      (is (= -32020 (get-in resp [:body :error :code])))))

  (testing "missing _meta fields are invalid params"
    (let [resp (protocol/handle-message
                 server {:headers {"mcp-protocol-version" "2026-07-28" "mcp-method" "tools/list"}
                         :message {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}}})]
      (is (= 400 (:status resp)))
      (is (= -32602 (get-in resp [:body :error :code])))))

  (testing "header version must match _meta version"
    (let [resp (-request "tools/list" {} :headers {"mcp-protocol-version" "2025-11-25"})]
      (is (= -32020 (get-in resp [:body :error :code])))))

  (testing "unsupported version lists supported versions"
    (let [old-meta (assoc meta-ok :io.modelcontextprotocol/protocolVersion "2025-11-25")
          resp (-request "tools/list" {:_meta old-meta}
                         :headers {"mcp-protocol-version" "2025-11-25"})]
      (is (= 400 (:status resp)))
      (is (= -32022 (get-in resp [:body :error :code])))
      (is (= {:supported ["2026-07-28"] :requested "2025-11-25"}
             (get-in resp [:body :error :data])))))

  (testing "Mcp-Name must match the tool name; base64 sentinel is decoded"
    (is (= -32020 (get-in (-request "tools/call" {:name "echo"} :headers {"mcp-name" "other"})
                          [:body :error :code])))
    (is (= 200 (:status (-request "tools/call" {:name "echo"}
                                  :headers {"mcp-name" "=?base64?ZWNobw==?="}))))))

(deftest message-shape-test
  (testing "notifications are accepted with 202 and no body"
    (let [resp (protocol/handle-message server {:headers {} :message {:jsonrpc "2.0" :method "x"}})]
      (is (= {:status 202 :body nil} resp))))

  (testing "batches and non-2.0 messages are invalid requests"
    (is (= -32600 (get-in (protocol/handle-message server {:headers {} :message [{:a 1}]})
                          [:body :error :code])))
    (is (= -32600 (get-in (protocol/handle-message server {:headers {} :message {:jsonrpc "1.0" :id 1 :method "x"}})
                          [:body :error :code]))))

  (testing "null id is rejected"
    (is (= -32600 (get-in (protocol/handle-message server {:headers {} :message {:jsonrpc "2.0" :id nil :method "x"}})
                          [:body :error :code]))))

  (testing "parse errors"
    (let [{:keys [response]} (protocol/parse-message "{not json")]
      (is (= 400 (:status response)))
      (is (= -32700 (get-in response [:body :error :code])))))

  (testing "unknown method returns 404 + -32601 (ping was removed in 2026-07-28)"
    (let [resp (-request "ping" {})]
      (is (= 404 (:status resp)))
      (is (= -32601 (get-in resp [:body :error :code]))))))

(deftest tools-call-test
  (testing "successful call returns structured content and JSON text"
    (let [result (get-in (-request "tools/call" {:name "echo" :arguments {:x 1}}) [:body :result])]
      (is (= "complete" (:resultType result)))
      (is (false? (:isError result)))
      (is (= {:echo {:x 1}} (:structuredContent result)))
      (is (= "{\"echo\":{\"x\":1}}" (get-in result [:content 0 :text])))))

  (testing "tool errors become isError results with details"
    (let [result (get-in (-request "tools/call" {:name "fail"}) [:body :result])]
      (is (true? (:isError result)))
      (is (= "bad input\n{\"field\":[\"wrong\"]}" (get-in result [:content 0 :text])))))

  (testing "unexpected exceptions are reported and hidden from the client"
    (reset! errors [])
    (let [result (get-in (-request "tools/call" {:name "crash"}) [:body :result])]
      (is (true? (:isError result)))
      (is (= "Internal error while running the tool" (get-in result [:content 0 :text])))
      (is (= 1 (count @errors)))))

  (testing "unknown tool is a protocol error"
    (is (= -32602 (get-in (-request "tools/call" {:name "nope"}) [:body :error :code]))))

  (testing "non-object arguments are rejected"
    (is (= -32602 (get-in (-request "tools/call" {:name "echo" :arguments [1]}) [:body :error :code]))))

  (testing "check-access can short-circuit the call"
    (is (= {:status 403 :body "forbidden"} (-request "tools/call" {:name "secret"})))
    (is (= 200 (:status (-request "tools/call" {:name "secret"} :context {:scope "write"}))))))

(deftest request-info-test
  (is (= {:mcp.method.name "tools/call"
          :jsonrpc.request.id "3"
          :gen_ai.tool.name "echo"
          :mcp.protocol.version "2026-07-28"
          :o11ylite.mcp.client_name "claude"}
         (protocol/request-info {:jsonrpc "2.0" :id 3 :method "tools/call"
                                 :params {:name "echo"
                                          :_meta (assoc meta-ok :io.modelcontextprotocol/clientInfo
                                                        {:name "claude"})}}))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.mcp.protocol-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
