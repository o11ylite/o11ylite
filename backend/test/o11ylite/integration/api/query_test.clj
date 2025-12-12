;; ---------------------------------------------------------
;; o11ylite.integration.api.query-test
;;
;; Integration tests for query API endpoints.
;; Tests HTTP behavior, not schema details (see ducklake/events/query_test).
;; ---------------------------------------------------------

(ns o11ylite.integration.api.query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Validation

(deftest events-query-returns-400-on-invalid-request-test
  (testing "POST /api/query/events returns 400 with error details for invalid request"
    (let [response (h/post-json "/api/query/events" {:invalid "query"})]
      (is (= 400 (h/status response)))
      (is (h/json-response? response))
      (is (map? (get-in response [:body :error]))))))

;; ---------------------------------------------------------
;; Table Visualization

(deftest events-query-table-test
  (testing "POST /api/query/events with table visualization"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :visualization {:type "table"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :rows])))
      (is (number? (get-in response [:body :data :total_count])))
      (is (number? (get-in response [:body :metadata :query_time_ms]))))))

;; ---------------------------------------------------------
;; Time Series Visualization

(deftest events-query-time-series-test
  (testing "POST /api/query/events with time_series visualization"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :aggregations [{:field "*"
                                                 :function "count"}]
                                 :visualization {:type "time_series"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :series])))
      (is (number? (get-in response [:body :data :bucket_ms]))))))

;; ---------------------------------------------------------
;; Heatmap Visualization

(deftest events-query-heatmap-test
  (testing "POST /api/query/events with heatmap visualization"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :group_by ["duration_ms"]
                                 :visualization {:type "heatmap"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :x_buckets])))
      (is (vector? (get-in response [:body :data :y_buckets])))
      (is (vector? (get-in response [:body :data :values]))))))

;; ---------------------------------------------------------
;; Trace Visualization

(deftest events-query-trace-test
  (testing "POST /api/query/events with trace visualization"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :filter {:field "trace_id"
                                          :op "="
                                          :value "abc123"}
                                 :visualization {:type "trace"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :spans])))
      (is (number? (get-in response [:body :data :total_count])))
      (is (boolean? (get-in response [:body :data :truncated]))))))
