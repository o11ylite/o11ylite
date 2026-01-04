;; ---------------------------------------------------------
;; o11ylite.otel-grpc.metric-proto-test
;;
;; Unit tests for OTLP metric transformation.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.metric-proto-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [o11ylite.otel-grpc.metric-proto :as metric-proto]
   [o11ylite.test-helpers.otlp :as otlp])
  (:import
   [java.time Instant]))

;; ---------------------------------------------------------
;; Test Data Builders

(def ^:private test-timestamp
  "Fixed timestamp for deterministic tests: 2001-09-09T01:46:40Z"
  (Instant/ofEpochSecond 1000000000))

(defn- build-sample-gauge-request
  "Build a sample metrics request with gauge metrics."
  []
  (otlp/build-metrics-request
   {:service-name "test-service"
    :resource-attrs {"host.name" "localhost"}
    :meter-name "test-meter"
    :meter-version "1.0.0"
    :metrics [(otlp/build-gauge-metric
               {:name "cpu.utilization"
                :description "CPU utilization percentage"
                :unit "%"
                :data-points [{:value 42.5
                               :time-ns 1000000000000000000
                               :attributes {"cpu.core" "0"}}
                              {:value 38.2
                               :time-ns 1000000000000000000
                               :attributes {"cpu.core" "1"}}]})
              (otlp/build-gauge-metric
               {:name "memory.used"
                :description "Memory used"
                :unit "bytes"
                :data-points [{:value 1024000.0
                               :time-ns 1000000000000000000}]})]}))

(defn- build-request-without-service
  "Build a request without service.name - should be rejected."
  []
  (otlp/build-metrics-request
   {:meter-name "test-meter"
    :metrics [(otlp/build-gauge-metric
               {:name "orphan.metric"
                :data-points [{:value 100}]})]}))

;; ---------------------------------------------------------
;; Tests

(deftest parse-metrics-request-data-points-test
  (testing "Parses gauge data points with correct structure"
    (let [{:keys [data-points]} (metric-proto/parse-metrics-request (build-sample-gauge-request))
          sorted (sort-by (juxt :name :attr.cpu.core) data-points)]

      (is (= 3 (count data-points)))

      ;; First CPU metric (core 0)
      (is (= {:name "cpu.utilization"
              :service "test-service"
              :timestamp test-timestamp
              :value 42.5
              :scope.name "test-meter"
              :scope.version "1.0.0"
              :attr.service.name "test-service"
              :attr.host.name "localhost"
              :attr.cpu.core "0"}
             (dissoc (nth sorted 0) :meta.observed_time)))

      ;; Second CPU metric (core 1)
      (is (= {:name "cpu.utilization"
              :service "test-service"
              :timestamp test-timestamp
              :value 38.2
              :scope.name "test-meter"
              :scope.version "1.0.0"
              :attr.service.name "test-service"
              :attr.host.name "localhost"
              :attr.cpu.core "1"}
             (dissoc (nth sorted 1) :meta.observed_time)))

      ;; Memory metric (no cpu.core attribute)
      (is (= {:name "memory.used"
              :service "test-service"
              :timestamp test-timestamp
              :value 1024000.0
              :scope.name "test-meter"
              :scope.version "1.0.0"
              :attr.service.name "test-service"
              :attr.host.name "localhost"}
             (dissoc (nth sorted 2) :meta.observed_time)))

      ;; meta.observed_time should be present
      (is (every? #(instance? Instant (:meta.observed_time %)) data-points)))))

(deftest parse-metrics-request-metadata-test
  (testing "Extracts metrics metadata with attribute names"
    (let [{:keys [metrics-metadata]} (metric-proto/parse-metrics-request (build-sample-gauge-request))
          sorted (sort-by :name metrics-metadata)]

      (is (= 2 (count metrics-metadata)))

      ;; CPU metric metadata
      (is (= {:name "cpu.utilization"
              :description "CPU utilization percentage"
              :unit "%"
              :metric_type :gauge
              :attributes #{"cpu.core"}}
             (nth sorted 0)))

      ;; Memory metric metadata
      (is (= {:name "memory.used"
              :description "Memory used"
              :unit "bytes"
              :metric_type :gauge
              :attributes #{}}
             (nth sorted 1))))))

(deftest parse-metrics-request-metadata-merge-test
  (testing "Merges metadata when same metric appears multiple times"
    ;; Build request with same metric name but different attributes
    (let [request (otlp/build-metrics-request
                   {:service-name "test-service"
                    :meter-name "test-meter"
                    :metrics [(otlp/build-gauge-metric
                               {:name "http.requests"
                                :description "HTTP request count"
                                :unit "1"
                                :data-points [{:value 100
                                               :attributes {"method" "GET"}}]})
                              (otlp/build-gauge-metric
                               {:name "http.requests"
                                :description "HTTP request count"
                                :unit "1"
                                :data-points [{:value 50
                                               :attributes {"status" "200"}}]})]})
          {:keys [metrics-metadata]} (metric-proto/parse-metrics-request request)]

      (is (= 1 (count metrics-metadata)))
      (is (= {:name "http.requests"
              :description "HTTP request count"
              :unit "1"
              :metric_type :gauge
              :attributes #{"method" "status"}}
             (first metrics-metadata))))))

(deftest rejects-metrics-without-service-test
  (testing "Rejects metrics without service.name"
    (let [{:keys [data-points metrics-metadata]}
          (metric-proto/parse-metrics-request (build-request-without-service))]
      (is (= [] data-points))
      (is (= [] metrics-metadata)))))

(deftest count-rejected-data-points-test
  (testing "Counts rejected data points correctly"
    (is (= 0 (metric-proto/count-rejected-data-points (build-sample-gauge-request))))
    (is (= 1 (metric-proto/count-rejected-data-points (build-request-without-service))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.otel-grpc.metric-proto-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
