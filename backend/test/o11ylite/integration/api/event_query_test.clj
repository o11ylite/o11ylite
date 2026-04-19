;; ---------------------------------------------------------
;; o11ylite.integration.api.event-query-test
;;
;; Integration tests for events query API endpoint.
;; Tests HTTP behavior, not schema details (see store/events/query_schema_test).
;; ---------------------------------------------------------

(ns o11ylite.integration.api.event-query-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.events-schema-cache :as events-schema-cache]
    [o11ylite.test-helpers :as h]
    [tick.core :as t]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

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

(deftest events-query-table-with-data-test
  (testing "POST /api/query/events returns ingested events"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest test events
      (h/ingest-sample-events! 2
                               {:service "test-query-service"
                                :name "test-event"})

      ;; Query the data
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :filter {:field "service"
                                            :op "="
                                            :value "test-query-service"}
                                   :limit 10
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        (let [rows (get-in response [:body :data :rows])
              total (get-in response [:body :data :total_count])]
          (is (= 2 total))
          (is (= 2 (count rows)))
          (is (every? #(= "test-event" (:name %)) rows))
          (is (every? #(= "test-query-service" (:service %)) rows))))))

  (testing "id field is present and serialized as string for JS compatibility"
    (let [now-ms (current-epoch-ms)]

      (h/ingest-sample-events! 2
                               {:service "test-id-serialization"})

      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :filter {:field "service"
                                            :op "="
                                            :value "test-id-serialization"}
                                   :limit 10
                                   :visualization {:type "table"}})
            rows (get-in response [:body :data :rows])]
        (is (= 2 (count rows)))
        ;; Snowflake IDs exceed JavaScript's MAX_SAFE_INTEGER (2^53-1)
        ;; and must be serialized as strings to preserve precision
        (is (every? #(string? (:id %)) rows) "id should be serialized as string")
        (is (every? #(re-matches #"\d+" (:id %)) rows) "id should be numeric string")
        ;; Each event should have a unique id
        (is (= 2 (count (set (map :id rows)))) "each event should have unique id"))))

  (testing "timestamp columns are returned as epoch milliseconds (float)"
    (let [now-ms (current-epoch-ms)
          ;; Create timestamp with microsecond precision for test
          test-timestamp (t/>> (t/truncate (t/instant) :millis) (t/of-micros 123))]

      (h/ingest-sample-events! 1
                               {:service "test-timestamp-format"
                                :timestamp test-timestamp})

      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :filter {:field "service"
                                            :op "="
                                            :value "test-timestamp-format"}
                                   :limit 1
                                   :visualization {:type "table"}})
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
  (testing "POST /api/query/events with aggregation returns grouped results with columns metadata"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest events from two services
      (h/ingest-sample-events! 2 {:service "service-a"})
      (h/ingest-sample-events! 1 {:service "service-b"})

      ;; Query with count aggregation grouped by service
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :aggregations [{:id "A"
                                                   :field "*"
                                                   :function "count"}]
                                   :group_by ["service"]
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        ;; Verify columns metadata maps ref to row key
        (let [columns (get-in response [:body :data :columns])]
          (is (= [{:ref "A" :key "count(*)"}] columns)))

        (let [rows (get-in response [:body :data :rows])]
          ;; Should have 2 groups (service-a and service-b)
          (is (= 2 (count rows)))

          ;; Find each service's row and verify count
          ;; Row keys still use descriptive aliases like "count(*)"
          (let [service-a-row (first (filter #(= "service-a" (:service %)) rows))
                service-b-row (first (filter #(= "service-b" (:service %)) rows))]
            (is (= 2 (get service-a-row (keyword "count(*)"))))
            (is (= 1 (get service-b-row (keyword "count(*)"))))))))))

;; ---------------------------------------------------------
;; Cursor-based Pagination

(defn- query-events
  "Helper to query events with optional cursor."
  [now-ms service-name cursor]
  (let [response (h/post-json "/api/query/events"
                              (cond-> {:time_range {:start (- now-ms 3600000)
                                                    :end (+ now-ms 60000)}
                                       :filter {:field "service" :op "=" :value service-name}
                                       :limit 2
                                       :visualization {:type "table"}}
                                cursor (assoc :cursor cursor)))]
    {:status (h/status response)
     :data (get-in response [:body :data])}))

(deftest events-query-pagination-test
  (testing "POST /api/query/events supports cursor-based pagination"
    (let [now-ms (current-epoch-ms)
          service-name "pagination-test-service"
          _ (h/ingest-sample-events! 5 {:service service-name})

          ;; First page
          page1 (query-events now-ms service-name nil)
          _ (is (= 200 (:status page1)))
          _ (is (= 2 (get-in page1 [:data :total_count])))
          _ (is (true? (get-in page1 [:data :has_more])) "Page 1 should have more")
          _ (is (some? (get-in page1 [:data :next_cursor])) "Page 1 should have cursor")

          ;; Second page
          page2 (query-events now-ms service-name (get-in page1 [:data :next_cursor]))
          _ (is (= 200 (:status page2)))
          _ (is (= 2 (get-in page2 [:data :total_count])))
          _ (is (true? (get-in page2 [:data :has_more])) "Page 2 should have more")
          _ (is (some? (get-in page2 [:data :next_cursor])) "Page 2 should have cursor")

          ;; No overlap
          ids1 (set (map :id (get-in page1 [:data :rows])))
          ids2 (set (map :id (get-in page2 [:data :rows])))
          _ (is (empty? (clojure.set/intersection ids1 ids2)) "No overlapping IDs")

          ;; Third page (final)
          page3 (query-events now-ms service-name (get-in page2 [:data :next_cursor]))]
      (is (= 200 (:status page3)))
      (is (= 1 (get-in page3 [:data :total_count])) "Page 3 should have 1 event")
      (is (false? (get-in page3 [:data :has_more])) "Page 3 should not have more")
      (is (nil? (get-in page3 [:data :next_cursor])) "Page 3 should not have cursor"))))

(deftest events-query-pagination-with-custom-sort-test
  (testing "POST /api/query/events supports cursor pagination with custom sort"
    (let [now-ms (current-epoch-ms)
          ;; Ingest events with distinct service names for predictable sort order
          _ (h/ingest-sample-events! 1 {:service "sort-test-aaa"})
          _ (h/ingest-sample-events! 1 {:service "sort-test-bbb"})
          _ (h/ingest-sample-events! 1 {:service "sort-test-ccc"})

          query-with-sort (fn [cursor]
                            (let [response (h/post-json "/api/query/events"
                                                        (cond-> {:time_range {:start (- now-ms 3600000)
                                                                              :end (+ now-ms 60000)}
                                                                 :filter {:field "service" :op "contains" :value "sort-test-"}
                                                                 :limit 1
                                                                 :visualization {:type "table"
                                                                                 :sort {:field "service" :order "asc"}}}
                                                          cursor (assoc :cursor cursor)))]
                              {:status (h/status response)
                               :data (get-in response [:body :data])}))

          ;; First page - should get "aaa" (alphabetically first)
          page1 (query-with-sort nil)
          _ (is (= 200 (:status page1)))
          _ (is (= "sort-test-aaa" (get-in page1 [:data :rows 0 :service])))
          _ (is (true? (get-in page1 [:data :has_more])))

          ;; Second page - should get "bbb"
          page2 (query-with-sort (get-in page1 [:data :next_cursor]))
          _ (is (= 200 (:status page2)))
          _ (is (= "sort-test-bbb" (get-in page2 [:data :rows 0 :service])))
          _ (is (true? (get-in page2 [:data :has_more])))

          ;; Third page - should get "ccc"
          page3 (query-with-sort (get-in page2 [:data :next_cursor]))]
      (is (= 200 (:status page3)))
      (is (= "sort-test-ccc" (get-in page3 [:data :rows 0 :service])))
      (is (false? (get-in page3 [:data :has_more]))))))

;; ---------------------------------------------------------
;; Field Names with Dots

(deftest events-query-filter-by-field-with-dots-test
  (testing "POST /api/query/events handles filtering by attribute fields containing dots"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest events with a dotted attribute (attr.http.method is generated by make-random-event)
      (h/ingest-sample-events! 1
                               {:service "test-dotted-field-service"
                                :attr.http.method "GET"})

      ;; Query filtering by the dotted field name
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :filter {:field "attr.http.method"
                                            :op "="
                                            :value "GET"}
                                   :limit 10
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))
        (is (h/json-response? response))

        (let [rows (get-in response [:body :data :rows])]
          (is (pos? (count rows)))
          (is (some #(= "GET" (:attr.http.method %)) rows)))))))

;; ---------------------------------------------------------
;; Time Series Visualization

(deftest events-query-time-series-auto-bucket-ms-test
  (testing "POST /api/query/events auto-calculates bucket_ms using nice intervals"
    ;; Nice bucket sizes: 1s, 5s, 10s, 20s, 30s, 1m, 2m, 5m, 10m, 20m, 30m, 1h, 2h, 4h, 6h, 12h, 1d
    ;; Formula: pick smallest nice bucket that yields ~100 buckets
    (let [query-bucket (fn [start end]
                         (get-in (h/post-json "/api/query/events"
                                              {:time_range {:start start :end end}
                                               :aggregations [{:id "A" :field "*" :function "count"}]
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
      (h/ingest-sample-events! 2
                               {:service "ts-service-a" :timestamp bucket-1 :span.duration_ms 100.0})
      (h/ingest-sample-events! 1
                               {:service "ts-service-b" :timestamp bucket-1 :span.duration_ms 200.0})

      ;; Ingest events into second time bucket with deterministic durations
      (h/ingest-sample-events! 3
                               {:service "ts-service-a" :timestamp bucket-2 :span.duration_ms 150.0})
      (h/ingest-sample-events! 2
                               {:service "ts-service-b" :timestamp bucket-2 :span.duration_ms 250.0})

      ;; Query time series grouped by service with two aggregations (no explicit aliases)
      (let [end-ms (+ bucket-2-ms 60000)
            response (h/post-json "/api/query/events"
                                  {:time_range {:start bucket-1-ms
                                                :end end-ms}
                                   :filter {:or [{:field "service" :op "=" :value "ts-service-a"}
                                                 {:field "service" :op "=" :value "ts-service-b"}]}
                                   :aggregations [{:id "A" :field "*" :function "count"}
                                                  {:id "B" :field "span.duration_ms" :function "avg"}]
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
  (testing "POST /api/query/events trace returns spans with id field"
    (let [now-ms (current-epoch-ms)
          trace-id "test-trace-12345"
          root-span-id "root-span-001"
          child-span-id "child-span-002"
          base-time (t/instant)]

      ;; Ingest root span
      (h/ingest-sample-events! 1
                               {:trace_id trace-id
                                :span_id root-span-id
                                :parent_span_id nil
                                :name "HTTP GET /api/users"
                                :service "api-gateway"
                                :span.status_code "OK"
                                :span.duration_ms 100.5
                                :timestamp base-time
                                :meta.signal_type :span})

      ;; Ingest child span (starts 10ms after root)
      (h/ingest-sample-events! 1
                               {:trace_id trace-id
                                :span_id child-span-id
                                :parent_span_id root-span-id
                                :name "DB query"
                                :service "user-service"
                                :span.status_code "OK"
                                :span.duration_ms 45.2
                                :timestamp (t/>> base-time (t/of-millis 10))
                                :meta.signal_type :span})

      ;; Ingest a span_event (SHOULD be returned in trace query)
      ;; Span events don't have parent_span_id, span.status_code, or span.duration_ms
      (h/ingest-sample-events! 1
                               {:trace_id trace-id
                                :span_id child-span-id
                                :parent_span_id nil
                                :name "db.query"
                                :service "user-service"
                                :timestamp (t/>> base-time (t/of-millis 12))
                                :meta.signal_type :span_event
                                :span.status_code nil
                                :span.duration_ms nil
                                :span.kind nil})

      ;; Ingest a log event (should NOT be returned in trace query)
      (h/ingest-sample-events! 1
                               {:trace_id trace-id
                                :span_id child-span-id
                                :name "log message"
                                :service "user-service"
                                :timestamp (t/>> base-time (t/of-millis 15))
                                :meta.signal_type :log})

      ;; Query the trace
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :filter {:field "trace_id"
                                            :op "="
                                            :value trace-id}
                                   :visualization {:type "trace"}})
            data (get-in response [:body :data])
            spans (:spans data)
            actual-spans (filter #(= "span" (:meta.signal_type %)) spans)
            span-events (filter #(= "span_event" (:meta.signal_type %)) spans)]
        (is (= 200 (h/status response)))
        (is (= 3 (:total_count data)) "Should return 2 spans + 1 span_event, not the log")
        (is (= 3 (count spans)))
        (is (= 2 (count actual-spans)))
        (is (= 1 (count span-events)))

        ;; Verify response shape and ordering for spans
        (is (= {:span_id root-span-id
                :parent_span_id nil
                :name "HTTP GET /api/users"
                :service "api-gateway"
                :meta.signal_type "span"
                :span.status_code "OK"
                :span.duration_ms 100.5}
               (dissoc (first actual-spans) :timestamp)))
        (is (= {:span_id child-span-id
                :parent_span_id root-span-id
                :name "DB query"
                :service "user-service"
                :meta.signal_type "span"
                :span.status_code "OK"
                :span.duration_ms 45.2}
               (dissoc (second actual-spans) :timestamp)))

        ;; Verify span_event shape (span_events don't have parent_span_id)
        (is (= {:span_id child-span-id
                :parent_span_id nil
                :name "db.query"
                :service "user-service"
                :meta.signal_type "span_event"
                :span.status_code nil
                :span.duration_ms nil}
               (dissoc (first span-events) :timestamp)))

        (is (number? (:timestamp (first actual-spans))))
        (is (< (:timestamp (first actual-spans)) (:timestamp (second actual-spans))))))))

;; ---------------------------------------------------------
;; Having (Post-Aggregation Filtering)

(deftest events-query-table-with-having-test
  (testing "POST /api/query/events with having filters aggregated results"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest 5 events for service-a, 1 for service-b
      (h/ingest-sample-events! 5 {:service "having-service-a"})
      (h/ingest-sample-events! 1 {:service "having-service-b"})

      ;; Query with count > 2 having filter
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :aggregations [{:id "A"
                                                   :field "*"
                                                   :function "count"}]
                                   :group_by ["service"]
                                   :having {:ref "A" :op ">" :value 2}
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))

        (let [rows (get-in response [:body :data :rows])]
          ;; Only service-a should pass (count=5 > 2), service-b filtered out (count=1)
          (is (= 1 (count rows)))
          (is (= "having-service-a" (:service (first rows))))
          (is (= 5 (get (first rows) (keyword "count(*)")))))))))

(deftest events-query-table-with-composed-having-test
  (testing "POST /api/query/events with AND-composed having"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest events: 10 fast for svc-x, 3 fast for svc-y, 8 slow for svc-z
      (h/ingest-sample-events! 10 {:service "having-and-svc-x"
                                   (keyword "span.duration_ms") 50.0})
      (h/ingest-sample-events! 3 {:service "having-and-svc-y"
                                  (keyword "span.duration_ms") 50.0})
      (h/ingest-sample-events! 8 {:service "having-and-svc-z"
                                  (keyword "span.duration_ms") 900.0})

      ;; Having: count > 5 AND avg(duration) < 500
      ;; svc-x passes (count=10 > 5, avg=50 < 500)
      ;; svc-y fails (count=3, not > 5)
      ;; svc-z fails (avg=900, not < 500)
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-ms 3600000)
                                                :end (+ now-ms 60000)}
                                   :aggregations [{:id "A" :field "*" :function "count"}
                                                  {:id "B" :field "span.duration_ms" :function "avg"}]
                                   :group_by ["service"]
                                   :having {:and [{:ref "A" :op ">" :value 5}
                                                  {:ref "B" :op "<" :value 500}]}
                                   :visualization {:type "table"}})]
        (is (= 200 (h/status response)))

        (let [rows (get-in response [:body :data :rows])]
          (is (= 1 (count rows)))
          (is (= "having-and-svc-x" (:service (first rows)))))))))

;; ---------------------------------------------------------
;; Boolean Filter Coercion

(deftest events-query-boolean-filter-coercion-test
  (testing "POST /api/query/events coerces string boolean values for boolean fields"
    (let [now-ms (current-epoch-ms)]

      ;; Ingest error spans (span.status_code :error -> enrichment sets error=true)
      (h/ingest-sample-events! 2 {:service "bool-test-svc" :span.status_code :error})
      ;; Ingest ok spans (span.status_code :ok -> enrichment sets error=false)
      (h/ingest-sample-events! 3 {:service "bool-test-svc" :span.status_code :ok})

      ;; Ensure events-schema cache has the error field's boolean type
      @(events-schema-cache/refresh! (:cache/events-schema h/*system*))

      (let [time-range {:start (- now-ms 3600000) :end (+ now-ms 60000)}
            query-bool (fn [value]
                         (h/post-json "/api/query/events"
                                      {:time_range time-range
                                       :filter {:and [{:field "service" :op "=" :value "bool-test-svc"}
                                                      {:field "error" :op "=" :value value}]}
                                       :limit 10
                                       :visualization {:type "table"}}))]

        ;; String "true" — simulates what the frontend sends
        (let [rows (get-in (query-bool "true") [:body :data :rows])]
          (is (= 2 (count rows)) "Should return only the 2 error spans")
          (is (every? #(true? (:error %)) rows)))

        ;; String "false"
        (let [rows (get-in (query-bool "false") [:body :data :rows])]
          (is (= 3 (count rows)) "Should return only the 3 non-error spans")
          (is (every? #(false? (:error %)) rows)))))))
