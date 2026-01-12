;; ---------------------------------------------------------
;; o11ylite.integration.api.metric-query-test
;;
;; Integration tests for metrics query API endpoint.
;; Tests HTTP behavior, not schema details (see store/metrics/query_schema_test).
;; ---------------------------------------------------------

(ns o11ylite.integration.api.metric-query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Validation

(deftest metrics-query-returns-400-on-invalid-request-test
  (testing "POST /api/query/metrics returns 400 with error details for invalid request"
    (let [response (h/post-json "/api/query/metrics" {:invalid "query"})]
      (is (= 400 (h/status response)))
      (is (h/json-response? response))
      (is (map? (get-in response [:body :error])))))

  (testing "POST /api/query/metrics returns 400 when metrics is empty"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics []})]
      (is (= 400 (h/status response)))
      (is (h/json-response? response))))

  (testing "POST /api/query/metrics returns 400 for duplicate metric IDs"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}
                                           {:id "A" :name "memory.usage" :agg "avg"}]})]
      (is (= 400 (h/status response)))
      (is (h/json-response? response)))))

;; ---------------------------------------------------------
;; Basic Query Structure

(deftest metrics-query-returns-time-series-structure-test
  (testing "POST /api/query/metrics returns proper time-series response structure"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A"
                                            :name "cpu.utilization"
                                            :agg "avg"}]})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      ;; Verify response structure
      (is (number? (get-in response [:body :data :bucket_ms])))
      (is (number? (get-in response [:body :data :start_ms])))
      (is (number? (get-in response [:body :data :end_ms])))
      (is (vector? (get-in response [:body :data :series])))
      (is (number? (get-in response [:body :metadata :query_time_ms]))))))

(deftest metrics-query-auto-bucket-ms-test
  (testing "POST /api/query/metrics auto-calculates bucket_ms using nice intervals"
    (let [query-bucket (fn [start end]
                         (get-in (h/post-json "/api/query/metrics"
                                              {:time_range {:start start :end end}
                                               :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})
                                 [:body :data :bucket_ms]))]
      ;; 1 hour (3600000ms) -> ideal 36000ms -> rounds to 1 minute (60,000 ms)
      (is (= 60000 (query-bucket 1702000000000 1702003600000)))
      ;; 5 minutes (300000ms) -> ideal 3000ms -> rounds to 5s (5,000 ms)
      (is (= 5000 (query-bucket 1702000000000 1702000300000)))
      ;; 24 hours (86400000ms) -> ideal 864000ms (~14m) -> rounds to 20 minutes (1,200,000 ms)
      (is (= 1200000 (query-bucket 1702000000000 1702086400000))))))

(deftest metrics-query-explicit-bucket-ms-test
  (testing "POST /api/query/metrics uses explicit bucket_ms when provided"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :bucket_ms 30000
                                 :metrics [{:id "A"
                                            :name "cpu.utilization"
                                            :agg "avg"}]})]
      (is (= 200 (h/status response)))
      (is (= 30000 (get-in response [:body :data :bucket_ms]))))))

;; ---------------------------------------------------------
;; Multi-Metric Query

(deftest metrics-query-multi-metric-test
  (testing "POST /api/query/metrics accepts multiple metrics"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A"
                                            :name "http.server.errors"
                                            :agg "sum"}
                                           {:id "B"
                                            :name "http.server.requests"
                                            :agg "sum"}]})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response)))))

;; ---------------------------------------------------------
;; Query with Filters

(deftest metrics-query-with-filters-test
  (testing "POST /api/query/metrics with filter"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :filter {:field "attr.env" :op "=" :value "prod"}
                                 :metrics [{:id "A"
                                            :name "cpu.utilization"
                                            :agg "avg"}]})]
      (is (= 200 (h/status response)))))

  (testing "POST /api/query/metrics with per-metric filter"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A"
                                            :name "http.server.errors"
                                            :agg "sum"
                                            :filter {:field "attr.status_code" :op ">=" :value "500"}}]})]
      (is (= 200 (h/status response))))))

;; ---------------------------------------------------------
;; Query with Group By

(deftest metrics-query-with-group-by-test
  (testing "POST /api/query/metrics with group_by"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A"
                                            :name "cpu.utilization"
                                            :agg "avg"}]
                                 :group_by ["attr.host.name"]})]
      (is (= 200 (h/status response)))))

  (testing "POST /api/query/metrics with multiple group_by fields"
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A"
                                            :name "http.server.duration"
                                            :agg "avg"}]
                                 :group_by ["attr.service" "attr.method"]})]
      (is (= 200 (h/status response))))))
