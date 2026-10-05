;; ---------------------------------------------------------
;; o11ylite.integration.mcp-test
;;
;; Integration tests for the MCP endpoint in open mode: transport
;; behavior, discovery documents, and every tool against real
;; storage. One deftest amortizes system startup.
;; ---------------------------------------------------------

(ns o11ylite.integration.mcp-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [jsonista.core :as json]
    [o11ylite.notebook :as notebook]
    [o11ylite.store.services :as services]
    [o11ylite.test-helpers :as h]
    [o11ylite.test-helpers.mcp :as mcp]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(def ^:private mcp-url (h/url "/mcp"))

(defn- -alert-rule-args
  [overrides]
  (merge {:name "Error spike"
          :query_mode "events"
          :query {:filter {:field "span.status_code" :op "=" :value "error"}
                  :aggregations [{:id "A" :function "count" :field "*"}]
                  :having {:ref "A" :op ">" :value 10}
                  :visualization {:type "table"}}
          :eval_window_ms 300000
          :eval_interval_ms 60000}
         overrides))

;; ---------------------------------------------------------
;; Tests

(deftest mcp-open-mode-test
  (testing "transport: discover, list, and HTTP method handling"
    (let [{:keys [status body]} (mcp/request "server/discover" {})]
      (is (= 200 status))
      (is (= ["2026-07-28"] (get-in body [:result :supportedVersions]))))
    (let [tools (get-in (mcp/request "tools/list" {}) [:body :result :tools])]
      (is (= ["list_services" "list_event_fields" "query_events" "get_trace" "list_metrics"
              "describe_metric" "query_metrics" "list_alert_rules" "get_alert_rule"
              "save_alert_rule" "create_notebook"]
             (mapv :name tools))))
    (is (= 405 (h/status (h/get-request "/mcp"))))
    (is (= 405 (h/status (h/delete-request "/mcp"))))
    (testing "missing transport headers are rejected"
      (is (= 400 (h/status (mcp/request "tools/list" {} {:headers {"Mcp-Method" "server/discover"}})))))
    (testing "notifications get 202 without a body"
      (let [resp (h/post "/mcp" {:headers {"Content-Type" "application/json"}
                                 :body "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}"})]
        (is (= 202 (h/status resp)))
        (is (str/blank? (h/body resp)))))
    (testing "malformed JSON is a parse error"
      (let [resp (h/post "/mcp" {:headers {"Content-Type" "application/json"} :body "{"})]
        (is (= 400 (h/status resp)))
        (is (= -32700 (get-in (json/read-value (h/body resp) json/keyword-keys-object-mapper)
                              [:error :code]))))))

  (testing "Origin validation"
    (is (= 403 (h/status (mcp/request "tools/list" {} {:headers {"Origin" "http://evil.example"}}))))
    (is (= 200 (h/status (mcp/request "tools/list" {} {:headers {"Origin" "http://localhost:3333"}})))))

  (testing "discovery documents"
    (let [prm (:body (h/get-json "/.well-known/oauth-protected-resource/mcp"))
          prm-root (:body (h/get-json "/.well-known/oauth-protected-resource"))
          as-meta (:body (h/get-json "/.well-known/oauth-authorization-server"))]
      (is (= mcp-url (:resource prm)))
      (is (= prm prm-root))
      (is (= ["http://localhost:3333"] (:authorization_servers prm)))
      (is (= "http://localhost:3333" (:issuer as-meta)))
      (is (= "http://localhost:3333/oauth/token" (:token_endpoint as-meta)))
      (is (= ["S256"] (:code_challenge_methods_supported as-meta)))
      (is (true? (:client_id_metadata_document_supported as-meta)))
      (is (true? (:authorization_response_iss_parameter_supported as-meta)))
      (is (= ["authorization_code" "refresh_token"] (:grant_types_supported as-meta)))))

  (testing "telemetry tools"
    (h/ingest-sample-events! 20 {:service "checkout"})
    (h/ingest-sample-metrics! 10)
    ;; The telemetry catalog buffer registers services on a timer; seed directly.
    (services/upsert-services! (:db/sqlite h/*system*) ["checkout"] (System/currentTimeMillis))
    (is (= ["checkout"] (map :name (:services (mcp/tool-result "list_services" {})))))

    (let [fields (mcp/tool-result "list_event_fields" {:search "SERVICE"})]
      (is (some #(= {:name "service" :type "string"} %) (:fields fields))))

    (let [result (mcp/tool-result "query_events" {:time_range {:last "2h"}
                                                  :limit 3
                                                  :fields ["service" "trace_id" "timestamp"]})
          row (first (:rows result))]
      (is (= 3 (:row_count result)))
      (is (true? (:has_more result)))
      (is (= #{:service :trace_id :timestamp} (set (keys row))))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (:timestamp row)) "timestamps are ISO-8601")
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*Z" (get-in result [:time_range :start])))

      (testing "cursor pagination"
        (let [next-page (mcp/tool-result "query_events" {:time_range {:last "2h"} :limit 3
                                                         :cursor (:next_cursor result)})]
          (is (= 3 (:row_count next-page)))))

      (testing "get_trace"
        (let [trace (mcp/tool-result "get_trace" {:trace_id (:trace_id row)})]
          (is (= (:trace_id row) (:trace_id trace)))
          (is (pos? (:span_count trace))))))

    (let [result (mcp/tool-result "query_events" {:time_range {:last "2h"}
                                                  :filter {:field "service" :op "=" :value "checkout"}
                                                  :aggregations [{:id "A" :function "count" :field "*"}]
                                                  :group_by ["service"]})]
      (is (= [{:service "checkout" (keyword "count(*)") 20}] (:rows result))))

    (let [result (mcp/tool-result "query_events" {:time_range {:last "2h"}
                                                  :aggregations [{:id "A" :function "count" :field "*"}]
                                                  :visualization {:type "time_series"}})]
      (is (seq (:series result))))

    (testing "query errors are tool errors the model can fix"
      (is (str/includes? (mcp/tool-error-text "query_events" {:filter {:field "no_such_field" :op "=" :value 1}})
                         "no_such_field"))
      (is (str/includes? (mcp/tool-error-text "query_events" {:visualization {:type "trace"}})
                         "visualization"))
      (is (str/includes? (mcp/tool-error-text "query_events" {:limt 5}) "disallowed key")))

    (let [metric-name (-> (mcp/tool-result "list_metrics" {}) :metrics first :name)
          described (mcp/tool-result "describe_metric" {:name metric-name})
          result (mcp/tool-result "query_metrics" {:time_range {:last "2h"}
                                                   :metrics [{:id "A" :name metric-name :agg "avg"}]})]
      (is (some? metric-name))
      (is (= "gauge" (:metric_type described)))
      (is (seq (:series result)))
      (is (str/includes? (mcp/tool-error-text "describe_metric" {:name "nope"}) "not found"))))

  (testing "alert rule tools"
    (let [created (mcp/tool-result "save_alert_rule" (-alert-rule-args {:enabled false}))
          id (get-in created [:alert_rule :id])]
      (is (true? (:created created)))
      (is (false? (get-in created [:alert_rule :enabled])))
      (is (str/ends-with? (:url created) (str "/alert-rules/" id "/edit")))

      (let [updated (mcp/tool-result "save_alert_rule" (-alert-rule-args {:id id :name "Renamed"}))]
        (is (false? (:created updated)))
        (is (= "Renamed" (get-in updated [:alert_rule :name])))
        (is (false? (get-in updated [:alert_rule :enabled])) "omitting enabled keeps the current value"))
      (is (true? (get-in (mcp/tool-result "save_alert_rule" (-alert-rule-args {:id id :name "Renamed"
                                                                               :enabled true}))
                         [:alert_rule :enabled])))

      (let [fetched (mcp/tool-result "get_alert_rule" {:id id})]
        (is (= "Renamed" (get-in fetched [:alert_rule :name])))
        (is (= [] (:instances fetched))))

      (is (= [id] (map :id (:alert_rules (mcp/tool-result "list_alert_rules" {})))))
      (is (= [] (:alert_rules (mcp/tool-result "list_alert_rules" {:state "firing"}))))

      (testing "validation and unknown ids"
        (is (str/includes? (mcp/tool-error-text "save_alert_rule" (-alert-rule-args {:eval_window_ms 1}))
                           "eval_window_ms"))
        (is (str/includes? (mcp/tool-error-text "save_alert_rule" (-alert-rule-args {:id "missing"}))
                           "not found"))
        (is (str/includes? (mcp/tool-error-text "get_alert_rule" {:id "missing"}) "not found")))))

  (testing "create_notebook"
    (let [result (mcp/tool-result "create_notebook"
                                  {:name "Checkout incident"
                                   :cells [{:title "Errors by service"
                                            :query_mode "events"
                                            :query {:aggregations [{:id "A" :function "count" :field "*"}]
                                                    :group_by ["service"]
                                                    :visualization {:type "table"}}
                                            :pinned_from "2026-01-01T00:00:00Z"
                                            :pinned_to "2026-01-01T01:00:00Z"}]})
          saved (notebook/get-notebook-by-id (:db/sqlite h/*system*) (:id result))]
      (is (= 1 (:cell_count result)))
      (is (str/ends-with? (:url result) (str "/notebooks/" (:id result))))
      (is (= "Checkout incident" (:name saved)))
      (is (= ["Errors by service"] (map :title (:cells saved)))))

    (testing "an invalid cell creates nothing"
      (let [before (count (notebook/list-notebooks (:db/sqlite h/*system*)))]
        (is (str/includes? (mcp/tool-error-text "create_notebook"
                                                {:name "Bad" :cells [{:query_mode "events" :query {}}]})
                           "index 0"))
        (is (= before (count (notebook/list-notebooks (:db/sqlite h/*system*)))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.mcp-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
