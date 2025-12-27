;; ---------------------------------------------------------
;; o11ylite.integration.api.query-test
;;
;; Integration tests for query API endpoints.
;; Tests HTTP behavior, not schema details (see store/events/query_test).
;; ---------------------------------------------------------

(ns o11ylite.integration.api.query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]
   [tick.core :as t]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- event-metadata [] (:cache/event-metadata h/*system*))
(defn- batcher [] (:ingest/batcher h/*system*))

(defn- current-epoch-seconds
  "Get current Unix epoch time in seconds."
  []
  (quot (System/currentTimeMillis) 1000))

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
                                {:time_range {:start 1702000000
                                              :end 1702003600}
                                 :visualization {:type "table"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :rows])))
      (is (number? (get-in response [:body :data :total_count])))
      (is (number? (get-in response [:body :metadata :query_time_ms]))))))

(deftest events-query-table-with-data-test
  (testing "POST /api/query/events returns ingested events"
    (let [now-s (current-epoch-seconds)]

      ;; Ingest test events
      (h/ingest-sample-events! (event-metadata) (batcher) 2
                               {:service "test-query-service"
                                :name "test-event"})

      ;; Query the data
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-s 3600)
                                                :end (+ now-s 60)}
                                   :filter {:field "service"
                                            :op "="
                                            :value "test-query-service"}
                                   :visualization {:type "table" :limit 10}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        (let [rows (get-in response [:body :data :rows])
              total (get-in response [:body :data :total_count])]
          (is (= 2 total))
          (is (= 2 (count rows)))
          (is (every? #(= "test-event" (:name %)) rows))
          (is (every? #(= "test-query-service" (:service %)) rows)))))))

(deftest events-query-table-with-aggregation-test
  (testing "POST /api/query/events with aggregation returns grouped results"
    (let [now-s (current-epoch-seconds)]

      ;; Ingest events from two services
      (h/ingest-sample-events! (event-metadata) (batcher) 2 {:service "service-a"})
      (h/ingest-sample-events! (event-metadata) (batcher) 1 {:service "service-b"})

      ;; Query with count aggregation grouped by service
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-s 3600)
                                                :end (+ now-s 60)}
                                   :aggregations [{:field "*"
                                                   :function "count"
                                                   :alias "event_count"}]
                                   :group_by ["service"]
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        (let [rows (get-in response [:body :data :rows])]
          ;; Should have 2 groups (service-a and service-b)
          (is (= 2 (count rows)))

          ;; Find each service's row and verify count
          (let [service-a-row (first (filter #(= "service-a" (:service %)) rows))
                service-b-row (first (filter #(= "service-b" (:service %)) rows))]
            (is (= 2 (:event_count service-a-row)))
            (is (= 1 (:event_count service-b-row)))))))))

;; ---------------------------------------------------------
;; Field Names with Dots

(deftest events-query-filter-by-field-with-dots-test
  (testing "POST /api/query/events handles filtering by attribute fields containing dots"
    (let [now-s (current-epoch-seconds)]

      ;; Ingest events with a dotted attribute (attr.http.method is generated by make-random-event)
      (h/ingest-sample-events! (event-metadata) (batcher) 1
                               {:service "test-dotted-field-service"
                                :attr.http.method "GET"})

      ;; Query filtering by the dotted field name
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-s 3600)
                                                :end (+ now-s 60)}
                                   :filter {:field "attr.http.method"
                                            :op "="
                                            :value "GET"}
                                   :visualization {:type "table" :limit 10}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        (let [rows (get-in response [:body :data :rows])]
          (is (pos? (count rows)))
          (is (some #(= "GET" (:attr.http.method %)) rows)))))))

;; ---------------------------------------------------------
;; Time Series Visualization

(deftest events-query-time-series-test
  (testing "POST /api/query/events with time_series visualization"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000
                                              :end 1702003600}
                                 :aggregations [{:field "*"
                                                 :function "count"}]
                                 :visualization {:type "time_series"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :series])))
      (is (number? (get-in response [:body :data :bucket_ms])))
      (is (number? (get-in response [:body :data :start_ms])))
      (is (number? (get-in response [:body :data :end_ms]))))))

(deftest events-query-time-series-auto-bucket-ms-test
  (testing "POST /api/query/events auto-calculates bucket_ms in milliseconds"
    ;; 1 hour time range (3600 seconds) should produce ~36 second buckets
    ;; bucket_ms = max(1000, (3600 / 100) * 1000) = 36000ms
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000
                                              :end 1702003600}
                                 :aggregations [{:field "*"
                                                 :function "count"}]
                                 :visualization {:type "time_series"}})
          bucket-ms (get-in response [:body :data :bucket_ms])]
      (is (= 200 (h/status response)))
      ;; Should be 36000ms (36 seconds), not 36ms
      (is (= 36000 bucket-ms)))))

(deftest events-query-time-series-with-data-test
  (testing "POST /api/query/events time_series returns bucketed series grouped by labels"
    ;; Use fixed timestamps truncated to minute boundary for predictable bucketing
    (let [bucket-1 (-> (t/instant) (t/truncate :minutes))
          bucket-2 (t/>> bucket-1 (t/of-minutes 1))
          bucket-1-epoch-s (t/long bucket-1)
          bucket-2-epoch-s (t/long bucket-2)]

      ;; Ingest events into first time bucket with deterministic durations
      (h/ingest-sample-events! (event-metadata) (batcher) 2
                               {:service "ts-service-a" :timestamp bucket-1 :span.duration_ms 100.0})
      (h/ingest-sample-events! (event-metadata) (batcher) 1
                               {:service "ts-service-b" :timestamp bucket-1 :span.duration_ms 200.0})

      ;; Ingest events into second time bucket with deterministic durations
      (h/ingest-sample-events! (event-metadata) (batcher) 3
                               {:service "ts-service-a" :timestamp bucket-2 :span.duration_ms 150.0})
      (h/ingest-sample-events! (event-metadata) (batcher) 2
                               {:service "ts-service-b" :timestamp bucket-2 :span.duration_ms 250.0})

      ;; Query time series grouped by service with two aggregations (no explicit aliases)
      (let [end-epoch-s (+ bucket-2-epoch-s 60)
            response (h/post-json "/api/query/events"
                                  {:time_range {:start bucket-1-epoch-s
                                                :end end-epoch-s}
                                   :filter {:or [{:field "service" :op "=" :value "ts-service-a"}
                                                 {:field "service" :op "=" :value "ts-service-b"}]}
                                   :aggregations [{:field "*" :function "count"}
                                                  {:field "span.duration_ms" :function "avg"}]
                                   :group_by ["service"]
                                   :visualization {:type "time_series"
                                                   :bucket_ms 60000}})
            data (get-in response [:body :data])
            bucket-1-ts (* bucket-1-epoch-s 1000)
            bucket-2-ts (* bucket-2-epoch-s 1000)]
        (is (= 200 (h/status response)))
        ;; With new format: one series per (labels, aggregation) combination
        ;; Each series has :name and :data with {:timestamp :value} points
        (is (= {:bucket_ms 60000
                :start_ms bucket-1-ts
                :end_ms (* end-epoch-s 1000)
                :series [{:labels {:service "ts-service-a"}
                          :name "avg_span.duration_ms"
                          :data [{:timestamp bucket-1-ts :value 100.0}
                                 {:timestamp bucket-2-ts :value 150.0}]}
                         {:labels {:service "ts-service-a"}
                          :name "count_*"
                          :data [{:timestamp bucket-1-ts :value 2}
                                 {:timestamp bucket-2-ts :value 3}]}
                         {:labels {:service "ts-service-b"}
                          :name "avg_span.duration_ms"
                          :data [{:timestamp bucket-1-ts :value 200.0}
                                 {:timestamp bucket-2-ts :value 250.0}]}
                         {:labels {:service "ts-service-b"}
                          :name "count_*"
                          :data [{:timestamp bucket-1-ts :value 1}
                                 {:timestamp bucket-2-ts :value 2}]}]}
               (update data :series
                       (fn [s] (vec (sort-by (juxt #(get-in % [:labels :service]) :name) s))))))))))

;; ---------------------------------------------------------
;; Heatmap Visualization

(deftest events-query-heatmap-test
  (testing "POST /api/query/events with heatmap visualization"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000
                                              :end 1702003600}
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
                                {:time_range {:start 1702000000
                                              :end 1702003600}
                                 :filter {:field "trace_id"
                                          :op "="
                                          :value "abc123"}
                                 :visualization {:type "trace"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? (get-in response [:body :data :spans])))
      (is (number? (get-in response [:body :data :total_count])))
      (is (boolean? (get-in response [:body :data :truncated]))))))
