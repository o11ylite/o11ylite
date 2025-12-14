;; ---------------------------------------------------------
;; o11ylite.integration.otel-log-test
;;
;; Integration tests for OpenTelemetry Logs gRPC endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-log-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- duckdb [] (:db/duckdb h/*system*))

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
          (is (= "12345" (:attr.user.id row))))))))

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
      (let [rows (query-events-by-service service-name)]
        (is (= 3 (count rows)))))))

(deftest log-export-rejects-without-service-test
  (testing "LogsService/Export rejects logs without service.name"
    (let [response (h/export-logs!
                    {:logger-name "test-logger"
                     :logs [{:body "Orphan log"
                             :severity :info}]})]
      (is (some? response))
      (is (= 1 (-> response .getPartialSuccess .getRejectedLogRecords)))
      ;; Verify rejected logs are not persisted
      (let [rows (jdbc/execute! (duckdb)
                                ["SELECT * FROM o11ylite.events WHERE \"log.body\" = ?"
                                 "Orphan log"])]
        (is (= 0 (count rows)))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-log-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
