;; ---------------------------------------------------------
;; o11ylite.integration.api.query-test
;;
;; Integration tests for query API endpoints.
;; Tests HTTP behavior, not schema details (see store/events/query_test).
;; ---------------------------------------------------------

(ns o11ylite.integration.api.query-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

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
      (is (number? (get-in response [:body :data :bucket_ms]))))))

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
