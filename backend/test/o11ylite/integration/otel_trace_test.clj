;; ---------------------------------------------------------
;; o11ylite.integration.otel-trace-test
;;
;; Integration tests for OpenTelemetry trace ingestion.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-trace-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [o11ylite.test-helpers :as h]))

;; ---------------------------------------------------------
;; Helpers

(defn- duckdb [] (:db/duckdb h/*system*))

(defn- query-events-by-trace
  "Query events from DuckLake by trace_id."
  [trace-id]
  (jdbc/execute! (duckdb)
                 ["SELECT * FROM o11ylite.events WHERE trace_id = ? ORDER BY name"
                  trace-id]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Tests

(deftest trace-export-single-span-test
  (testing "TraceService/Export accepts a single span and persists to DuckDB"
    (let [trace-id "0af7651916cd43dd8448eb211c80319c"
          response (h/export-traces!
                    {:service-name "test-service"
                     :tracer-name "test-tracer"
                     :spans [{:trace-id trace-id
                              :span-id "b7ad6b7169203331"
                              :name "GET /api/users"
                              :kind :server
                              :start-time-ns 1000000000
                              :end-time-ns   1000100000
                              :attributes {"http.method" "GET"
                                           "http.status_code" 200
                                           "http.enabled" true
                                           "http.latency" 3.14}
                              :status :ok}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedSpans)))
      (let [rows (query-events-by-trace trace-id)
            row (first rows)]
        (is (= 1 (count rows)))
        (is (= "GET /api/users" (:name row)))
        (is (= "test-service" (:service row)))
        (is (= "span" (:meta.signal_type row)))
        ;; Verify various attribute types
        (is (= "GET" (:attr.http.method row)))
        (is (= 200 (:attr.http.status_code row)))
        (is (= true (:attr.http.enabled row)))
        (is (= 3.14 (:attr.http.latency row)))))))

(deftest trace-export-multiple-spans-test
  (testing "TraceService/Export accepts multiple spans with parent-child relationship"
    (let [trace-id "1af7651916cd43dd8448eb211c80319c"
          parent-span-id "b7ad6b7169203331"
          child-span-id "00f067aa0ba902b7"
          response (h/export-traces!
                    {:service-name "test-service"
                     :tracer-name "test-tracer"
                     :spans [{:trace-id trace-id
                              :span-id parent-span-id
                              :name "parent-operation"
                              :kind :server
                              :start-time-ns 1000000000
                              :end-time-ns   1000200000
                              :status :ok}
                             {:trace-id trace-id
                              :span-id child-span-id
                              :parent-span-id parent-span-id
                              :name "child-operation"
                              :kind :internal
                              :start-time-ns 1000050000
                              :end-time-ns   1000150000
                              :status :ok}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedSpans)))
      (let [rows (query-events-by-trace trace-id)]
        (is (= 2 (count rows)))
        ;; Rows ordered by name: child-operation, parent-operation
        (is (= "child-operation" (:name (first rows))))
        (is (= "parent-operation" (:name (second rows))))
        (is (= parent-span-id (:parent_span_id (first rows))))))))

(deftest trace-export-span-events-test
  (testing "TraceService/Export collects span events attached to spans"
    (let [trace-id "3af7651916cd43dd8448eb211c80319c"
          span-id "c8ad6b7169203331"
          response (h/export-traces!
                    {:service-name "test-service"
                     :tracer-name "test-tracer"
                     :spans [{:trace-id trace-id
                              :span-id span-id
                              :name "http-request"
                              :kind :server
                              :start-time-ns 1000000000
                              :end-time-ns   1000500000
                              :status :ok
                              :events [{:name "request.received"
                                        :time-ns 1000100000
                                        :attributes {"request.size" 1024}}
                                       {:name "response.sent"
                                        :time-ns 1000400000
                                        :attributes {"response.size" 2048}}]}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedSpans)))
      (let [rows (query-events-by-trace trace-id)
            by-type (group-by :meta.signal_type rows)]
        ;; Should have 1 span + 2 span_events = 3 total events
        (is (= 3 (count rows)))
        (is (= 1 (count (get by-type "span"))))
        (is (= 2 (count (get by-type "span_event"))))
        ;; Verify span event details
        (let [span-events (get by-type "span_event")]
          (is (some #(= "request.received" (:name %)) span-events))
          (is (some #(= "response.sent" (:name %)) span-events))
          ;; Span events should reference the parent span
          (is (every? #(= span-id (:span_id %)) span-events)))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-trace-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
