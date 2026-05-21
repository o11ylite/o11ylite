;; ---------------------------------------------------------
;; o11ylite.integration.otel-grpc-log-test
;;
;; Integration tests for OpenTelemetry Logs gRPC endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-grpc-log-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [next.jdbc :as jdbc]
    [o11ylite.test-helpers :as h]))

;; Use a partial system rooted at the gRPC server so the scheduler doesn't
;; race with ingestion. :server/otel-grpc pulls in all required ingestion
;; components transitively but not :scheduler/*.
(use-fixtures :each (h/with-partial-system [:server/otel-grpc]))

;; ---------------------------------------------------------
;; Helpers

(defn- duckdb
  []
  (:db/duckdb-reader h/*system*))

(defn- query-events-by-service
  "Query events from DuckLake by service name."
  [service-name]
  (jdbc/execute! (duckdb)
                 ["SELECT * FROM o11ylite.events WHERE service = ? ORDER BY name"
                  service-name]))

;; ---------------------------------------------------------
;; Tests

(deftest log-export-single-log-test
  (testing "LogsService/Export accepts a single log record and persists to DuckDB"
    (let [service-name "log-single-test-service"
          response (h/export-logs!
                     {:service-name service-name
                      :logger-name "test-logger"
                      :logs [{:body "Test log message"
                              :severity :info
                              :severity-text "INFO"
                              :attributes {"user.id" "12345"}}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedLogRecords)))
      (let [rows (query-events-by-service service-name)]
        (is (= 1 (count rows)))
        (let [row (first rows)]
          (is (= service-name (:service row)))
          (is (= "log" (:meta.signal_type row)))
          (is (= "Test log message" (:log.body row)))
          (is (= "info" (:log.severity row)))
          (is (= "12345" (:attr.user.id row)))
          ;; Verify Snowflake ID is present and positive
          (is (pos-int? (:id row)) "Event should have a positive Snowflake ID"))))))

(deftest log-export-multiple-logs-test
  (testing "LogsService/Export accepts multiple log records and persists to DuckDB"
    (let [service-name "log-multi-test-service"
          response (h/export-logs!
                     {:service-name service-name
                      :logger-name "test-logger"
                      :logs [{:body "First log message"
                              :severity :info}
                             {:body "Second log message"
                              :severity :warn}
                             {:body "Third log message"
                              :severity :error}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedLogRecords)))
      (let [rows (query-events-by-service service-name)
            ids (map :id rows)]
        (is (= 3 (count rows)))
        ;; Verify all IDs are positive integers
        (is (every? pos-int? ids) "All events should have positive Snowflake IDs")
        ;; Verify IDs are unique
        (is (= 3 (count (set ids))) "All IDs should be unique")))))

(deftest log-export-defaults-service-name-test
  (testing "LogsService/Export defaults to unknown_service when service.name is absent"
    (let [response (h/export-logs!
                     {:logger-name "test-logger"
                      :logs [{:body "Orphan log"
                              :severity :info}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedLogRecords)))
      (let [rows (jdbc/execute! (duckdb)
                                ["SELECT * FROM o11ylite.events WHERE \"log.body\" = ?"
                                 "Orphan log"])]
        (is (= 1 (count rows)) "Logs without service.name should be persisted with default")
        (is (= "unknown_service" (:service (first rows))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-grpc-log-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
