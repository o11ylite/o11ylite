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
(defn- event-batcher [] (:ingest/event-batcher h/*system*))

(defn- current-epoch-ms
  "Get current Unix epoch time in milliseconds."
  []
  (System/currentTimeMillis))

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

(deftest events-query-table-with-data-test
  (testing "POST /api/query/events returns ingested events"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest test events
      (h/ingest-sample-events! (event-metadata) (event-batcher) 2
                               {:service "test-query-service"
                                :name "test-event"})

      ;; Query the data
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
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
          (is (every? #(= "test-query-service" (:service %)) rows))))))

  (testing "timestamp columns are returned as epoch milliseconds (float)"
    (let [now-ms (current-epoch-ms)
          ;; Create timestamp with microsecond precision for test
          test-timestamp (t/>> (t/truncate (t/instant) :millis) (t/of-micros 123))]

      (h/ingest-sample-events! (event-metadata) (event-batcher) 1
                               {:service "test-timestamp-format"
                                :timestamp test-timestamp})

      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :filter {:field "service"
                                            :op "="
                                            :value "test-timestamp-format"}
                                   :visualization {:type "table" :limit 1}})
            row (first (get-in response [:body :data :rows]))
            timestamp (:timestamp row)
            observed-time (:meta.observed_time row)]
        ;; Timestamps should be numbers (epoch milliseconds as float), not strings
        (is (number? timestamp) "timestamp should be epoch milliseconds (number)")
        (is (number? observed-time) "meta.observed_time should be epoch milliseconds (number)")
        ;; Verify microsecond precision is preserved in the fractional part
        ;; test-timestamp has .123 microseconds, which becomes 0.123 in the fractional ms
        (is (< (Math/abs (- (mod timestamp 1) 0.123)) 0.001)
            "microsecond precision should be preserved in fractional milliseconds")))))

(deftest events-query-table-with-aggregation-test
  (testing "POST /api/query/events with aggregation returns grouped results"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest events from two services
      (h/ingest-sample-events! (event-metadata) (event-batcher) 2 {:service "service-a"})
      (h/ingest-sample-events! (event-metadata) (event-batcher) 1 {:service "service-b"})

      ;; Query with count aggregation grouped by service
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :aggregations [{:field "*"
                                                   :function "count"}]
                                   :group_by ["service"]
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        (let [rows (get-in response [:body :data :rows])]
          ;; Should have 2 groups (service-a and service-b)
          (is (= 2 (count rows)))

          ;; Find each service's row and verify count
          ;; Alias is auto-generated as "count(*)"
          (let [service-a-row (first (filter #(= "service-a" (:service %)) rows))
                service-b-row (first (filter #(= "service-b" (:service %)) rows))]
            (is (= 2 (get service-a-row (keyword "count(*)"))))
            (is (= 1 (get service-b-row (keyword "count(*)"))))))))))

;; ---------------------------------------------------------
;; Field Names with Dots

(deftest events-query-filter-by-field-with-dots-test
  (testing "POST /api/query/events handles filtering by attribute fields containing dots"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest events with a dotted attribute (attr.http.method is generated by make-random-event)
      (h/ingest-sample-events! (event-metadata) (event-batcher) 1
                               {:service "test-dotted-field-service"
                                :attr.http.method "GET"})

      ;; Query filtering by the dotted field name
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
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
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
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
  (testing "POST /api/query/events auto-calculates bucket_ms using nice intervals"
    ;; Nice bucket sizes: 1s, 5s, 10s, 20s, 30s, 1m, 2m, 5m, 10m, 20m, 30m, 1h, 2h, 4h, 6h, 12h, 1d
    ;; Formula: pick smallest nice bucket that yields ~100 buckets
    (let [query-bucket (fn [start end]
                         (get-in (h/post-json "/api/query/events"
                                              {:time_range {:start start :end end}
                                               :aggregations [{:field "*" :function "count"}]
                                               :visualization {:type "time_series"}})
                                 [:body :data :bucket_ms]))]
      ;; 1 hour (3600000ms) -> ideal 36000ms -> rounds to 1 minute (60,000 ms)
      (is (= 60000 (query-bucket 1702000000000 1702003600000)))
      ;; 5 minutes (300000ms) -> ideal 3000ms -> rounds to 5s (5,000 ms)
      (is (= 5000 (query-bucket 1702000000000 1702000300000)))
      ;; 24 hours (86400000ms) -> ideal 864000ms (~14m) -> rounds to 20 minutes (1,200,000 ms)
      (is (= 1200000 (query-bucket 1702000000000 1702086400000)))
      ;; 7 days (604800000ms) -> ideal 6048000ms (~100m) -> rounds to 2 hours (7,200,000 ms)
      (is (= 7200000 (query-bucket 1702000000000 1702604800000))))))

(deftest events-query-time-series-with-data-test
  (testing "POST /api/query/events time_series returns bucketed series grouped by labels"
    ;; Use fixed timestamps truncated to minute boundary for predictable bucketing
    (let [bucket-1 (-> (t/instant) (t/truncate :minutes))
          bucket-2 (t/>> bucket-1 (t/of-minutes 1))
          bucket-1-ms (.toEpochMilli bucket-1)
          bucket-2-ms (.toEpochMilli bucket-2)]

      ;; Ingest events into first time bucket with deterministic durations
      (h/ingest-sample-events! (event-metadata) (event-batcher) 2
                               {:service "ts-service-a" :timestamp bucket-1 :span.duration_ms 100.0})
      (h/ingest-sample-events! (event-metadata) (event-batcher) 1
                               {:service "ts-service-b" :timestamp bucket-1 :span.duration_ms 200.0})

      ;; Ingest events into second time bucket with deterministic durations
      (h/ingest-sample-events! (event-metadata) (event-batcher) 3
                               {:service "ts-service-a" :timestamp bucket-2 :span.duration_ms 150.0})
      (h/ingest-sample-events! (event-metadata) (event-batcher) 2
                               {:service "ts-service-b" :timestamp bucket-2 :span.duration_ms 250.0})

      ;; Query time series grouped by service with two aggregations (no explicit aliases)
      (let [end-ms (+ bucket-2-ms 60000)
            response (h/post-json "/api/query/events"
                                  {:time_range {:start bucket-1-ms
                                                :end end-ms}
                                   :filter {:or [{:field "service" :op "=" :value "ts-service-a"}
                                                 {:field "service" :op "=" :value "ts-service-b"}]}
                                   :aggregations [{:field "*" :function "count"}
                                                  {:field "span.duration_ms" :function "avg"}]
                                   :group_by ["service"]
                                   :visualization {:type "time_series"
                                                   :bucket_ms 60000}})
            data (get-in response [:body :data])]
        (is (= 200 (h/status response)))
        ;; With new format: one series per (labels, aggregation) combination
        ;; Each series has :name and :data with {:timestamp :value} points
        (is (= {:bucket_ms 60000
                :start_ms bucket-1-ms
                :end_ms end-ms
                :series [{:labels {:service "ts-service-a"}
                          :name "avg(span.duration_ms)"
                          :data [{:timestamp bucket-1-ms :value 100.0}
                                 {:timestamp bucket-2-ms :value 150.0}]}
                         {:labels {:service "ts-service-a"}
                          :name "count(*)"
                          :data [{:timestamp bucket-1-ms :value 2}
                                 {:timestamp bucket-2-ms :value 3}]}
                         {:labels {:service "ts-service-b"}
                          :name "avg(span.duration_ms)"
                          :data [{:timestamp bucket-1-ms :value 200.0}
                                 {:timestamp bucket-2-ms :value 250.0}]}
                         {:labels {:service "ts-service-b"}
                          :name "count(*)"
                          :data [{:timestamp bucket-1-ms :value 1}
                                 {:timestamp bucket-2-ms :value 2}]}]}
               (update data :series
                       (fn [s] (vec (sort-by (juxt #(get-in % [:labels :service]) :name) s))))))))))

;; ---------------------------------------------------------
;; Heatmap Visualization (DEFERRED to post-v1)
;;
;; Decision: Heatmap is deferred because:
;; 1. Low user demand - most developers unfamiliar with heatmaps
;; 2. Market evidence: HyperDX lacks it, Datadog buries it
;; 3. Significant implementation effort for niche use case
;;
;; When implemented: Use "smart visualization" pattern where user selects
;; heatmap mode and sees a simplified "Distribution of: [field]" selector.
;; Backend handles histogram bucketing via DuckDB's histogram()/width_bucket().

(deftest ^:deferred events-query-heatmap-test
  (testing "POST /api/query/events with heatmap visualization (DEFERRED - returns empty data)"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :group_by ["duration_ms"]
                                 :visualization {:type "heatmap"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      ;; Currently returns empty vectors - will be populated when implemented
      (is (vector? (get-in response [:body :data :x_buckets])))
      (is (vector? (get-in response [:body :data :y_buckets])))
      (is (vector? (get-in response [:body :data :values]))))))

;; ---------------------------------------------------------
;; Trace Visualization
;;
;; Part of v1, accessed via dedicated /trace/:id frontend page.
;; Uses /api/query/events with filter on trace_id and visualization: {type: "trace"}.
;; Users click trace_id links in table results to navigate to the trace page.

(deftest events-query-trace-test
  (testing "POST /api/query/events with trace visualization (TODO - returns empty data)"
    (let [response (h/post-json "/api/query/events"
                                {:time_range {:start 1702000000000
                                              :end 1702003600000}
                                 :filter {:field "trace_id"
                                          :op "="
                                          :value "abc123"}
                                 :visualization {:type "trace"}})]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      ;; TODO: Implement span retrieval - currently returns empty data
      (is (vector? (get-in response [:body :data :spans])))
      (is (number? (get-in response [:body :data :total_count])))
      (is (boolean? (get-in response [:body :data :truncated]))))))
