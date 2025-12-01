;; ---------------------------------------------------------
;; o11ylite.integration.otel-log-test
;;
;; Integration tests for OpenTelemetry Logs gRPC endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-log-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Tests

(deftest log-export-single-log-test
  (testing "LogsService/Export accepts a single log record"
    (let [response (h/export-logs!
                    {:service-name "test-service"
                     :logger-name "test-logger"
                     :logs [{:body "Test log message"
                             :severity :info
                             :severity-text "INFO"
                             :attributes {"user.id" "12345"}}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedLogRecords))))))

(deftest log-export-multiple-logs-test
  (testing "LogsService/Export accepts multiple log records"
    (let [response (h/export-logs!
                    {:service-name "test-service"
                     :logger-name "test-logger"
                     :logs [{:body "First log message"
                             :severity :info}
                            {:body "Second log message"
                             :severity :warn}
                            {:body "Third log message"
                             :severity :error}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedLogRecords))))))

(deftest log-export-with-trace-context-test
  (testing "LogsService/Export accepts logs with trace context"
    (let [response (h/export-logs!
                    {:service-name "test-service"
                     :logger-name "test-logger"
                     :logs [{:body "Log with trace context"
                             :severity :info
                             :trace-id "0af7651916cd43dd8448eb211c80319c"
                             :span-id "b7ad6b7169203331"
                             :attributes {"operation" "checkout"}}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedLogRecords))))))

(deftest log-export-rejects-without-service-test
  (testing "LogsService/Export rejects logs without service.name"
    (let [response (h/export-logs!
                    {:logger-name "test-logger"
                     :logs [{:body "Orphan log"
                             :severity :info}]})]
      (is (some? response))
      (is (= 1 (-> response .getPartialSuccess .getRejectedLogRecords))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-log-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
