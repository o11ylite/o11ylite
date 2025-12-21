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
  (testing "POST /api/query/events returns ingested spans"
    (let [now-s (current-epoch-seconds)
          now-ns (* (System/currentTimeMillis) 1000000)
          trace-id "0123456789abcdef0123456789abcdef"
          span-id-1 "0123456789abcdef"
          span-id-2 "fedcba9876543210"]

      ;; Ingest test spans
      (h/export-traces!
       {:service-name "test-query-service"
        :tracer-name "test-tracer"
        :spans [{:trace-id trace-id
                 :span-id span-id-1
                 :name "GET /api/users"
                 :kind :server
                 :start-time-ns now-ns
                 :end-time-ns (+ now-ns 50000000)
                 :status :ok
                 :attributes {:http.method "GET"
                              :http.route "/api/users"}}
                {:trace-id trace-id
                 :span-id span-id-2
                 :parent-span-id span-id-1
                 :name "db.query"
                 :kind :client
                 :start-time-ns (+ now-ns 10000000)
                 :end-time-ns (+ now-ns 40000000)
                 :status :ok
                 :attributes {:db.system "postgresql"}}]})

      ;; Query the data
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-s 60)
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
          (is (some #(= "GET /api/users" (:name %)) rows))
          (is (some #(= "db.query" (:name %)) rows))
          (is (every? #(= "test-query-service" (:service %)) rows)))))))

(deftest events-query-table-with-aggregation-test
  (testing "POST /api/query/events with aggregation returns grouped results"
    (let [now-s (current-epoch-seconds)
          now-ns (* (System/currentTimeMillis) 1000000)]

      ;; Ingest spans from two services
      (h/export-traces!
       {:service-name "service-a"
        :tracer-name "test-tracer"
        :spans [{:trace-id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"
                 :span-id "aaaaaaaaaaaaaaaa"
                 :name "request-1"
                 :start-time-ns now-ns
                 :end-time-ns (+ now-ns 10000000)}
                {:trace-id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa2"
                 :span-id "aaaaaaaaaaaaaaab"
                 :name "request-2"
                 :start-time-ns now-ns
                 :end-time-ns (+ now-ns 20000000)}]})

      (h/export-traces!
       {:service-name "service-b"
        :tracer-name "test-tracer"
        :spans [{:trace-id "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb1"
                 :span-id "bbbbbbbbbbbbbbbb"
                 :name "request-1"
                 :start-time-ns now-ns
                 :end-time-ns (+ now-ns 30000000)}]})

      ;; Query with count aggregation grouped by service
      (let [response (h/post-json "/api/query/events"
                                  {:time_range {:start (- now-s 60)
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
