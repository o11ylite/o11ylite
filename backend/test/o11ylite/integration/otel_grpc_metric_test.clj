;; ---------------------------------------------------------
;; o11ylite.integration.otel-grpc-metric-test
;;
;; Integration tests for OpenTelemetry Metrics gRPC endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-grpc-metric-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [next.jdbc :as jdbc]
   [jsonista.core :as json]
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

(deftest metric-ingestion-persists-to-duckdb-test
  (testing "Metrics are persisted to DuckDB after flush"
    (let [service-name "persist-test-service"
          metric-name "system.cpu.usage"
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "system-meter"
                      :metrics [(h/build-gauge-metric
                                 {:name metric-name
                                  :description "System CPU usage"
                                  :unit "%"
                                  :data-points [{:value 75.5
                                                 :attributes {"host.name" "server-1"}}]})]})]
      ;; Wait for batcher to flush (test config uses 100ms interval)
      (Thread/sleep 200)
      ;; Query DuckDB for persisted data
      (let [duckdb (:db/duckdb h/*system*)
            rows (jdbc/execute! duckdb
                                ["SELECT name, service, value FROM o11ylite.metrics WHERE name = ?"
                                 metric-name])]
        (is (= 1 (count rows)) "Should have one metric row")
        (is (= metric-name (:name (first rows))))
        (is (= service-name (:service (first rows))))
        (is (= 75.5 (:value (first rows))))))))

(deftest metric-metadata-persists-to-sqlite-test
  (testing "Metric metadata is persisted to SQLite"
    (let [service-name "metadata-test-service"
          metric-name "process.memory.heap"
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "process-meter"
                      :metrics [(h/build-gauge-metric
                                 {:name metric-name
                                  :description "Heap memory usage"
                                  :unit "bytes"
                                  :data-points [{:value 1024000
                                                 :attributes {"process.pid" "12345"}}]})]})]
      ;; Wait for batcher to flush
      (Thread/sleep 200)
      ;; Query SQLite for metadata
      (let [sqlite (:db/sqlite h/*system*)
            rows (jdbc/execute! sqlite
                                ["SELECT * FROM metrics_metadata WHERE name = ?"
                                 metric-name])]
        (is (= 1 (count rows)) "Should have one metadata row")
        (let [row (first rows)]
          (is (= metric-name (:metrics_metadata/name row)))
          (is (= "Heap memory usage" (:metrics_metadata/description row)))
          (is (= "bytes" (:metrics_metadata/unit row)))
          (is (= "gauge" (:metrics_metadata/metric_type row)))
          ;; Attributes are stored as JSON array string
          (let [attrs (json/read-value (:metrics_metadata/attributes row))]
            (is (some #{"process.pid"} attrs))))))))

(deftest metric-metadata-merges-attributes-test
  (testing "Metric metadata merges attributes from multiple exports"
    (let [service-name "merge-test-service"
          metric-name "http.request.duration"
          ;; First export with one attribute
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "http-meter"
              :metrics [(h/build-gauge-metric
                         {:name metric-name
                          :description "HTTP request duration"
                          :unit "ms"
                          :data-points [{:value 100
                                         :attributes {"http.method" "GET"}}]})]})
          ;; Second export with different attribute
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "http-meter"
              :metrics [(h/build-gauge-metric
                         {:name metric-name
                          :unit "ms"
                          :data-points [{:value 200
                                         :attributes {"http.status_code" "200"}}]})]})]
      ;; Wait for batcher to flush
      (Thread/sleep 200)
      ;; Query SQLite for merged metadata
      (let [sqlite (:db/sqlite h/*system*)
            rows (jdbc/execute! sqlite
                                ["SELECT * FROM metrics_metadata WHERE name = ?"
                                 metric-name])]
        (is (= 1 (count rows)) "Should still have one metadata row")
        (let [attrs-json (:metrics_metadata/attributes (first rows))
              attrs (set (json/read-value attrs-json))]
          ;; Both attributes should be merged
          (is (contains? attrs "http.method"))
          (is (contains? attrs "http.status_code")))))))

(deftest metric-schema-evolution-adds-attr-columns-test
  (testing "Schema evolution adds attr.* columns to metrics table"
    (let [service-name "schema-evo-test-service"
          metric-name "disk.io.reads"
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "disk-meter"
                      :metrics [(h/build-gauge-metric
                                 {:name metric-name
                                  :description "Disk read operations"
                                  :unit "ops"
                                  :data-points [{:value 1500
                                                 :attributes {"disk.device" "sda1"
                                                              "disk.type" "ssd"}}]})]})]
      ;; Wait for batcher to flush
      (Thread/sleep 200)
      ;; Query DuckDB to verify attr columns exist and have values
      (let [duckdb (:db/duckdb h/*system*)
            rows (jdbc/execute! duckdb
                                ["SELECT name, service, value, \"attr.disk.device\", \"attr.disk.type\"
                                  FROM o11ylite.metrics WHERE name = ?"
                                 metric-name])]
        (is (= 1 (count rows)) "Should have one metric row")
        (let [row (first rows)]
          (is (= "sda1" (:attr.disk.device row)) "attr.disk.device should be populated")
          (is (= "ssd" (:attr.disk.type row)) "attr.disk.type should be populated"))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-grpc-metric-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
