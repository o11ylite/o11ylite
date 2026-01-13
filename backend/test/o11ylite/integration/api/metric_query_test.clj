;; ---------------------------------------------------------
;; o11ylite.integration.api.metric-query-test
;;
;; Integration tests for metrics query API endpoint.
;; Tests HTTP behavior, not schema details (see store/metrics/query_schema_test).
;; ---------------------------------------------------------

(ns o11ylite.integration.api.metric-query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.store.metrics.metadata :as metadata]
   [o11ylite.test-helpers :as h]
   [tick.core :as t]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite [] (:db/sqlite h/*system*))

(defn- instant->time-ns
  "Convert a tick Instant to nanoseconds since epoch."
  [instant]
  (* (.toEpochMilli instant) 1000000))

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

(deftest metrics-query-invalid-aggregation-test
  (testing "POST /api/query/metrics returns 400 for invalid aggregation on known metric"
    ;; Setup: create a sum (counter) metric
    (metadata/upsert-metrics! (sqlite)
                              {"http.requests.total"
                               {:metric_type :sum
                                :unit "1"
                                :attributes #{}}})

    ;; avg is not valid for sum metrics
    (let [response (h/post-json "/api/query/metrics"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :metrics [{:id "A"
                                            :name "http.requests.total"
                                            :agg "avg"}]})]
      (is (= 400 (h/status response)))
      (is (h/json-response? response))
      (is (string? (get-in response [:body :error])))
      (is (re-find #"not valid for sum" (get-in response [:body :error]))))))

;; ---------------------------------------------------------
;; Auto Bucket Selection

(deftest metrics-query-auto-bucket-ms-test
  (testing "POST /api/query/metrics auto-calculates bucket_ms using nice intervals"
    ;; Ingest a metric so queries have valid data context
    (h/export-metrics!
     {:service-name "bucket-test-service"
      :meter-name "test-meter"
      :metrics [(h/build-gauge-metric
                 {:name "test.metric"
                  :unit "1"
                  :data-points [{:value 1.0}]})]})

    (let [query-bucket (fn [start end]
                         (get-in (h/post-json "/api/query/metrics"
                                              {:time_range {:start start :end end}
                                               :metrics [{:id "A" :name "test.metric" :agg "avg"}]})
                                 [:body :data :bucket_ms]))]
      ;; 1 hour (3600000ms) -> ideal 36000ms -> rounds to 1 minute (60,000 ms)
      (is (= 60000 (query-bucket 1702000000000 1702003600000)))
      ;; 5 minutes (300000ms) -> ideal 3000ms -> rounds to 5s (5,000 ms)
      (is (= 5000 (query-bucket 1702000000000 1702000300000)))
      ;; 24 hours (86400000ms) -> ideal 864000ms (~14m) -> rounds to 20 minutes (1,200,000 ms)
      (is (= 1200000 (query-bucket 1702000000000 1702086400000))))))

;; ---------------------------------------------------------
;; Gauge Metrics

(deftest metrics-query-gauge-single-metric-test
  (testing "Query single gauge metric returns bucketed time series"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest gauge data points in the same bucket
      (h/export-metrics!
       {:service-name "gauge-single-service"
        :meter-name "test-meter"
        :metrics [(h/build-gauge-metric
                   {:name "cpu.utilization"
                    :description "CPU usage"
                    :unit "%"
                    :data-points [{:value 10.0 :time-ns time-ns}
                                  {:value 20.0 :time-ns time-ns}
                                  {:value 30.0 :time-ns time-ns}]})]})

      ;; Query with avg aggregation
      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "cpu.utilization"
                                              :agg "avg"}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "cpu.utilization"
                          :name "avg(cpu.utilization)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 20.0}]}]}
               data))))))

(deftest metrics-query-gauge-with-group-by-test
  (testing "Query gauge metric with group_by by service returns separate series per service"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest gauge data points from different services
      (h/export-metrics!
       {:service-name "grouped-service-1"
        :meter-name "test-meter"
        :metrics [(h/build-gauge-metric
                   {:name "memory.usage.grouped"
                    :description "Memory usage"
                    :unit "bytes"
                    :data-points [{:value 100.0 :time-ns time-ns}]})]})
      (h/export-metrics!
       {:service-name "grouped-service-2"
        :meter-name "test-meter"
        :metrics [(h/build-gauge-metric
                   {:name "memory.usage.grouped"
                    :unit "bytes"
                    :data-points [{:value 200.0 :time-ns time-ns}]})]})

      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "memory.usage.grouped"
                                              :agg "sum"}]
                                   :group_by ["service"]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "memory.usage.grouped"
                          :name "sum(memory.usage.grouped)"
                          :labels {:service "grouped-service-1"}
                          :data [{:timestamp bucket-ms :value 100.0}]}
                         {:id "A"
                          :metric "memory.usage.grouped"
                          :name "sum(memory.usage.grouped)"
                          :labels {:service "grouped-service-2"}
                          :data [{:timestamp bucket-ms :value 200.0}]}]}
               (update data :series
                       (fn [s] (vec (sort-by #(get-in % [:labels :service]) s))))))))))

(deftest metrics-query-gauge-multiple-buckets-test
  (testing "Query gauge metric across multiple time buckets"
    (let [bucket-1 (-> (t/instant) (t/truncate :minutes))
          bucket-2 (t/>> bucket-1 (t/of-minutes 1))
          bucket-1-ms (.toEpochMilli bucket-1)
          bucket-2-ms (.toEpochMilli bucket-2)
          time-ns-1 (instant->time-ns bucket-1)
          time-ns-2 (instant->time-ns bucket-2)
          end-ms (+ bucket-2-ms 60000)]

      ;; Ingest gauge data points in two different buckets
      (h/export-metrics!
       {:service-name "gauge-buckets-service"
        :meter-name "test-meter"
        :metrics [(h/build-gauge-metric
                   {:name "disk.usage"
                    :unit "bytes"
                    :data-points [{:value 50.0 :time-ns time-ns-1}
                                  {:value 60.0 :time-ns time-ns-1}
                                  {:value 80.0 :time-ns time-ns-2}
                                  {:value 100.0 :time-ns time-ns-2}]})]})

      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-1-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "disk.usage"
                                              :agg "avg"}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-1-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "disk.usage"
                          :name "avg(disk.usage)"
                          :labels {}
                          :data [{:timestamp bucket-1-ms :value 55.0}
                                 {:timestamp bucket-2-ms :value 90.0}]}]}
               data))))))

;; ---------------------------------------------------------
;; Sum/Counter Metrics

(deftest metrics-query-sum-with-rate-test
  (testing "Query sum/counter metric with rate aggregation"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest sum (counter) data point with delta temporality
      ;; Rate = sum(value) / bucket_seconds = 600 / 60 = 10
      (h/export-metrics!
       {:service-name "sum-rate-service"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "http.requests.count"
                    :description "HTTP request count"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 600.0 :time-ns time-ns}]})]})

      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "http.requests.count"
                                              :agg "rate"}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "http.requests.count"
                          :name "rate(http.requests.count)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 10.0}]}]}
               data))))))

(deftest metrics-query-sum-aggregation-test
  (testing "Query sum/counter metric with sum aggregation"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest sum data point with delta temporality (single data point)
      (h/export-metrics!
       {:service-name "sum-agg-service"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "http.errors.count"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 18.0 :time-ns time-ns}]})]})

      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "http.errors.count"
                                              :agg "sum"}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "http.errors.count"
                          :name "sum(http.errors.count)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 18.0}]}]}
               data))))))

;; ---------------------------------------------------------
;; Filters

(deftest metrics-query-with-global-filter-test
  (testing "Query with global filter by service returns only matching data"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest data from different services
      (h/export-metrics!
       {:service-name "filter-service-prod"
        :meter-name "test-meter"
        :metrics [(h/build-gauge-metric
                   {:name "error.rate.filter"
                    :unit "%"
                    :data-points [{:value 5.0 :time-ns time-ns}]})]})
      (h/export-metrics!
       {:service-name "filter-service-staging"
        :meter-name "test-meter"
        :metrics [(h/build-gauge-metric
                   {:name "error.rate.filter"
                    :unit "%"
                    :data-points [{:value 10.0 :time-ns time-ns}]})]})

      ;; Query with filter for prod service only
      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :filter {:field "service" :op "=" :value "filter-service-prod"}
                                   :metrics [{:id "A"
                                              :name "error.rate.filter"
                                              :agg "avg"}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "error.rate.filter"
                          :name "avg(error.rate.filter)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 5.0}]}]}
               data))))))

(deftest metrics-query-with-per-metric-filter-test
  (testing "Query with per-metric filter by service applies to specific metric"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest data from different services (delta temporality)
      (h/export-metrics!
       {:service-name "responses-ok-service"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "http.responses.permetric"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 100.0 :time-ns time-ns}]})]})
      (h/export-metrics!
       {:service-name "responses-error-service"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "http.responses.permetric"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 50.0 :time-ns time-ns}]})]})

      ;; Query with per-metric filter for error service only
      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "http.responses.permetric"
                                              :agg "sum"
                                              :filter {:field "service" :op "=" :value "responses-error-service"}}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "http.responses.permetric"
                          :name "sum(http.responses.permetric)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 50.0}]}]}
               data))))))

;; ---------------------------------------------------------
;; Multi-Metric Query

(deftest metrics-query-multi-metric-test
  (testing "Query multiple metrics in one request"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest two different metrics (delta temporality)
      (h/export-metrics!
       {:service-name "multi-metric-service"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "http.server.errors"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 50.0 :time-ns time-ns}]})
                  (h/build-sum-metric
                   {:name "http.server.requests"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 1000.0 :time-ns time-ns}]})]})

      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "http.server.errors"
                                              :agg "sum"}
                                             {:id "B"
                                              :name "http.server.requests"
                                              :agg "sum"}]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "http.server.errors"
                          :name "sum(http.server.errors)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 50.0}]}
                         {:id "B"
                          :metric "http.server.requests"
                          :name "sum(http.server.requests)"
                          :labels {}
                          :data [{:timestamp bucket-ms :value 1000.0}]}]}
               (update data :series (fn [s] (vec (sort-by :id s))))))))))

;; ---------------------------------------------------------
;; Complex Scenarios

(deftest metrics-query-multi-metric-with-group-by-test
  (testing "Query multiple metrics with group_by by service returns series for each metric/service"
    (let [bucket-time (-> (t/instant) (t/truncate :minutes))
          bucket-ms (.toEpochMilli bucket-time)
          time-ns (instant->time-ns bucket-time)
          end-ms (+ bucket-ms 60000)]

      ;; Ingest metrics from different services (delta temporality for sum)
      (h/export-metrics!
       {:service-name "complex-api"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "requests.complex"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 100.0 :time-ns time-ns}]})
                  (h/build-gauge-metric
                   {:name "latency.complex"
                    :unit "ms"
                    :data-points [{:value 50.0 :time-ns time-ns}]})]})
      (h/export-metrics!
       {:service-name "complex-web"
        :meter-name "test-meter"
        :metrics [(h/build-sum-metric
                   {:name "requests.complex"
                    :unit "1"
                    :temporality :delta
                    :monotonic? true
                    :data-points [{:value 200.0 :time-ns time-ns}]})
                  (h/build-gauge-metric
                   {:name "latency.complex"
                    :unit "ms"
                    :data-points [{:value 30.0 :time-ns time-ns}]})]})

      (let [response (h/post-json "/api/query/metrics"
                                  {:time_range {:start bucket-ms :end end-ms}
                                   :bucket_ms 60000
                                   :metrics [{:id "A"
                                              :name "requests.complex"
                                              :agg "sum"}
                                             {:id "B"
                                              :name "latency.complex"
                                              :agg "avg"}]
                                   :group_by ["service"]})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        (is (= {:bucket_ms 60000
                :start_ms bucket-ms
                :end_ms end-ms
                :series [{:id "A"
                          :metric "requests.complex"
                          :name "sum(requests.complex)"
                          :labels {:service "complex-api"}
                          :data [{:timestamp bucket-ms :value 100.0}]}
                         {:id "A"
                          :metric "requests.complex"
                          :name "sum(requests.complex)"
                          :labels {:service "complex-web"}
                          :data [{:timestamp bucket-ms :value 200.0}]}
                         {:id "B"
                          :metric "latency.complex"
                          :name "avg(latency.complex)"
                          :labels {:service "complex-api"}
                          :data [{:timestamp bucket-ms :value 50.0}]}
                         {:id "B"
                          :metric "latency.complex"
                          :name "avg(latency.complex)"
                          :labels {:service "complex-web"}
                          :data [{:timestamp bucket-ms :value 30.0}]}]}
               (update data :series
                       (fn [s] (vec (sort-by (juxt :id #(get-in % [:labels :service])) s))))))))))
