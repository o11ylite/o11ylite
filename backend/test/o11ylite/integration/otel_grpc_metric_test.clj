;; ---------------------------------------------------------
;; o11ylite.integration.otel-grpc-metric-test
;;
;; Integration tests for OpenTelemetry Metrics gRPC endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-grpc-metric-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Tests

(deftest metric-export-single-gauge-test
  (testing "MetricsService/Export accepts a single gauge metric"
    (let [service-name "metric-single-test-service"
          response (h/export-metrics!
                    {:service-name service-name
                     :meter-name "test-meter"
                     :metrics [(h/build-gauge-metric
                                {:name "cpu.utilization"
                                 :description "CPU utilization percentage"
                                 :unit "%"
                                 :data-points [{:value 42.5}]})]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedDataPoints))))))

(deftest metric-export-multiple-metrics-test
  (testing "MetricsService/Export accepts multiple metrics"
    (let [service-name "metric-multi-test-service"
          response (h/export-metrics!
                    {:service-name service-name
                     :meter-name "test-meter"
                     :metrics [(h/build-gauge-metric
                                {:name "memory.used"
                                 :unit "bytes"
                                 :data-points [{:value 1024000}]})
                               (h/build-gauge-metric
                                {:name "disk.free"
                                 :unit "bytes"
                                 :data-points [{:value 50000000}]})]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedDataPoints))))))

(deftest metric-export-rejects-without-service-test
  (testing "MetricsService/Export rejects metrics without service.name"
    (let [response (h/export-metrics!
                    {:meter-name "test-meter"
                     :metrics [(h/build-gauge-metric
                                {:name "orphan.metric"
                                 :data-points [{:value 100}]})]})]
      (is (some? response))
      (is (= 1 (-> response .getPartialSuccess .getRejectedDataPoints))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-grpc-metric-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
