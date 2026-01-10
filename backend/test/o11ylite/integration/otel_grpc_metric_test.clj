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

(deftest metric-export-without-service-test
  (testing "MetricsService/Export silently drops metrics without service.name"
    (let [response (h/export-metrics!
                    {:meter-name "test-meter"
                     :metrics [(h/build-gauge-metric
                                {:name "orphan.metric"
                                 :data-points [{:value 100}]})]})]
      ;; Metrics without service.name are filtered at parse time
      ;; No rejection count since nothing was attempted to ingest
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedDataPoints))))))

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
                                                 :attributes {"host.name" "server-1"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT name, service, value FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should have one metric row")
      (is (= metric-name (:name (first rows))))
      (is (= service-name (:service (first rows))))
      (is (= 75.5 (:value (first rows)))))))

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
                                                 :attributes {"process.pid" "12345"}}]})]})
          sqlite (:db/sqlite h/*system*)
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
          (is (some #{"process.pid"} attrs)))))))

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
                                         :attributes {"http.status_code" "200"}}]})]})
          sqlite (:db/sqlite h/*system*)
          rows (jdbc/execute! sqlite
                              ["SELECT * FROM metrics_metadata WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should still have one metadata row")
      (let [attrs-json (:metrics_metadata/attributes (first rows))
            attrs (set (json/read-value attrs-json))]
        ;; Both attributes should be merged
        (is (contains? attrs "http.method"))
        (is (contains? attrs "http.status_code"))))))

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
                                                              "disk.type" "ssd"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT name, service, value, \"attr.disk.device\", \"attr.disk.type\"
                                FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should have one metric row")
      (let [row (first rows)]
        (is (= "sda1" (:attr.disk.device row)) "attr.disk.device should be populated")
        (is (= "ssd" (:attr.disk.type row)) "attr.disk.type should be populated")))))

;; ---------------------------------------------------------
;; Sum Metric Tests

(deftest sum-metric-delta-export-test
  (testing "MetricsService/Export accepts delta sum metrics"
    (let [service-name "sum-delta-test-service"
          metric-name "http.requests.count"
          response (h/export-metrics!
                    {:service-name service-name
                     :meter-name "http-meter"
                     :metrics [(h/build-sum-metric
                                {:name metric-name
                                 :description "Total HTTP requests"
                                 :unit "requests"
                                 :temporality :delta
                                 :monotonic? true
                                 :data-points [{:value 100
                                                :attributes {"http.method" "GET"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT name, value FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedDataPoints)))
      (is (= 1 (count rows)))
      (is (= 100.0 (:value (first rows)))))))

(deftest sum-metric-cumulative-first-observation-dropped-test
  (testing "First cumulative observation is dropped (no previous value)"
    (let [service-name "sum-cumulative-first-service"
          metric-name "process.cpu.time.first"
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "process-meter"
                      :metrics [(h/build-sum-metric
                                 {:name metric-name
                                  :description "CPU time"
                                  :unit "seconds"
                                  :temporality :cumulative
                                  :monotonic? true
                                  :data-points [{:value 1000
                                                 :attributes {"cpu.core" "0"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT * FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 0 (count rows)) "First cumulative observation should be dropped"))))

(deftest sum-metric-cumulative-to-delta-conversion-test
  (testing "Cumulative sum is converted to delta on subsequent observations"
    (let [service-name "sum-cumulative-delta-service"
          metric-name "process.cpu.time.delta"
          ;; First export: cumulative value 1000 (will be dropped, but state stored)
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "process-meter"
              :metrics [(h/build-sum-metric
                         {:name metric-name
                          :description "CPU time"
                          :unit "seconds"
                          :temporality :cumulative
                          :monotonic? true
                          :data-points [{:value 1000
                                         :attributes {"cpu.core" "0"}}]})]})
          ;; Second export: cumulative value 1500 → delta should be 500
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "process-meter"
              :metrics [(h/build-sum-metric
                         {:name metric-name
                          :unit "seconds"
                          :temporality :cumulative
                          :data-points [{:value 1500
                                         :attributes {"cpu.core" "0"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT value FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should have one row (delta from second observation)")
      (is (= 500.0 (:value (first rows))) "Delta should be 1500 - 1000 = 500"))))

(deftest sum-metric-metadata-persists-test
  (testing "Sum metric metadata includes metric_type as 'sum'"
    (let [service-name "sum-metadata-test-service"
          metric-name "network.bytes.sent"
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "network-meter"
                      :metrics [(h/build-sum-metric
                                 {:name metric-name
                                  :description "Bytes sent over network"
                                  :unit "bytes"
                                  :temporality :delta
                                  :monotonic? true
                                  :data-points [{:value 1024
                                                 :attributes {"interface" "eth0"}}]})]})
          sqlite (:db/sqlite h/*system*)
          rows (jdbc/execute! sqlite
                              ["SELECT * FROM metrics_metadata WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (= "sum" (:metrics_metadata/metric_type row)))
        (is (= "Bytes sent over network" (:metrics_metadata/description row)))
        (is (= "bytes" (:metrics_metadata/unit row)))))))

(deftest sum-metric-monotonic-reset-detection-test
  (testing "Monotonic sum reset is detected and handled correctly"
    (let [service-name "sum-reset-test-service"
          metric-name "process.requests.total"
          ;; First export: cumulative value 1000 (dropped, state stored)
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "process-meter"
              :metrics [(h/build-sum-metric
                         {:name metric-name
                          :description "Total requests processed"
                          :unit "requests"
                          :temporality :cumulative
                          :monotonic? true
                          :data-points [{:value 1000
                                         :attributes {"instance" "pod-1"}}]})]})
          ;; Second export: cumulative value 50 (simulates service restart)
          ;; Without reset detection, delta would be 50 - 1000 = -950
          ;; With reset detection, delta should be 50 (the current value)
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "process-meter"
              :metrics [(h/build-sum-metric
                         {:name metric-name
                          :unit "requests"
                          :temporality :cumulative
                          :monotonic? true
                          :data-points [{:value 50
                                         :attributes {"instance" "pod-1"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT value FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should have one row from reset observation")
      (is (= 50.0 (:value (first rows)))
          "Reset detection should use current value (50) as delta, not -950"))))

(deftest sum-metric-deduplication-test
  (testing "Multiple data points for same series in one batch are deduplicated"
    (let [service-name "sum-dedup-test-service"
          metric-name "api.calls.dedup"
          now-ns (System/nanoTime)
          ;; Send two data points for same series in one request
          ;; Should keep the one with later timestamp
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "api-meter"
                      :metrics [(h/build-sum-metric
                                 {:name metric-name
                                  :unit "calls"
                                  :temporality :delta
                                  :data-points [{:value 10
                                                 :time-ns now-ns
                                                 :attributes {"endpoint" "/api/v1"}}
                                                {:value 20
                                                 :time-ns (+ now-ns 1000000)  ; 1ms later
                                                 :attributes {"endpoint" "/api/v1"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT value FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should deduplicate to one row")
      (is (= 20.0 (:value (first rows))) "Should keep the later timestamp (value 20)"))))

;; ---------------------------------------------------------
;; Histogram Metric Tests

(deftest histogram-metric-delta-export-test
  (testing "MetricsService/Export accepts delta histogram metrics"
    (let [service-name "histogram-delta-test-service"
          metric-name "http.request.duration"
          boundaries [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0]
          response (h/export-metrics!
                    {:service-name service-name
                     :meter-name "http-meter"
                     :metrics [(h/build-histogram-metric
                                {:name metric-name
                                 :description "HTTP request duration"
                                 :unit "s"
                                 :temporality :delta
                                 :boundaries boundaries
                                 :data-points [{:bucket-counts [10 20 30 25 10 3 1 0 1]
                                                :count 100
                                                :sum 15.5
                                                :min 0.001
                                                :max 1.5
                                                :attributes {"http.method" "GET"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT name, service, \"hist.count\", \"hist.sum\", \"hist.min\", \"hist.max\"
                                FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedDataPoints)))
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (= metric-name (:name row)))
        (is (= service-name (:service row)))
        (is (= 100 (:hist.count row)))
        (is (= 15.5 (:hist.sum row)))
        (is (= 0.001 (:hist.min row)))
        (is (= 1.5 (:hist.max row)))))))

(deftest histogram-metric-metadata-persists-test
  (testing "Histogram metadata includes boundaries"
    (let [service-name "histogram-metadata-test-service"
          metric-name "db.query.duration"
          boundaries [0.001 0.005 0.01 0.05 0.1 0.5 1.0 5.0]
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "db-meter"
                      :metrics [(h/build-histogram-metric
                                 {:name metric-name
                                  :description "Database query duration"
                                  :unit "s"
                                  :temporality :delta
                                  :boundaries boundaries
                                  :data-points [{:bucket-counts [5 10 15 20 15 10 5 3 2]
                                                 :count 85
                                                 :sum 42.5
                                                 :attributes {"db.system" "postgresql"}}]})]})
          sqlite (:db/sqlite h/*system*)
          rows (jdbc/execute! sqlite
                              ["SELECT * FROM metrics_metadata WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)))
      (let [row (first rows)]
        (is (= "histogram" (:metrics_metadata/metric_type row)))
        (is (= "Database query duration" (:metrics_metadata/description row)))
        (is (= "s" (:metrics_metadata/unit row)))
        ;; Boundaries stored as JSON array
        (let [stored-boundaries (json/read-value (:metrics_metadata/hist_boundaries row))]
          (is (= boundaries stored-boundaries)))))))

(deftest histogram-cumulative-first-observation-dropped-test
  (testing "First cumulative histogram observation is dropped"
    (let [service-name "histogram-cumulative-first-service"
          metric-name "cache.latency.first"
          boundaries [0.001 0.01 0.1 1.0]
          _response (h/export-metrics!
                     {:service-name service-name
                      :meter-name "cache-meter"
                      :metrics [(h/build-histogram-metric
                                 {:name metric-name
                                  :unit "s"
                                  :temporality :cumulative
                                  :boundaries boundaries
                                  :data-points [{:bucket-counts [100 200 50 25 25]
                                                 :count 400
                                                 :sum 45.0}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT * FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 0 (count rows)) "First cumulative histogram observation should be dropped"))))

(deftest histogram-cumulative-to-delta-conversion-test
  (testing "Cumulative histogram is converted to delta"
    (let [service-name "histogram-cumulative-delta-service"
          metric-name "rpc.latency.delta"
          boundaries [0.01 0.05 0.1 0.5 1.0]
          ;; First export: cumulative (will be dropped, state stored)
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "rpc-meter"
              :metrics [(h/build-histogram-metric
                         {:name metric-name
                          :unit "s"
                          :temporality :cumulative
                          :boundaries boundaries
                          :data-points [{:bucket-counts [10 20 30 20 15 5]
                                         :count 100
                                         :sum 25.0
                                         :attributes {"rpc.method" "GetUser"}}]})]})
          ;; Second export: cumulative values increased
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "rpc-meter"
              :metrics [(h/build-histogram-metric
                         {:name metric-name
                          :unit "s"
                          :temporality :cumulative
                          :boundaries boundaries
                          :data-points [{:bucket-counts [15 30 45 30 20 10]
                                         :count 150
                                         :sum 40.0
                                         :attributes {"rpc.method" "GetUser"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT \"hist.count\", \"hist.sum\" FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should have one row (delta from second observation)")
      (let [row (first rows)]
        ;; Delta: 150 - 100 = 50
        (is (= 50 (:hist.count row)) "hist.count delta should be 150 - 100 = 50")
        ;; Delta: 40.0 - 25.0 = 15.0
        (is (= 15.0 (:hist.sum row)) "hist.sum delta should be 40.0 - 25.0 = 15.0")))))

(deftest histogram-reset-detection-test
  (testing "Histogram reset is detected when bucket counts decrease"
    (let [service-name "histogram-reset-test-service"
          metric-name "worker.task.duration"
          boundaries [0.1 0.5 1.0 5.0]
          ;; First export: cumulative (dropped, state stored)
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "worker-meter"
              :metrics [(h/build-histogram-metric
                         {:name metric-name
                          :unit "s"
                          :temporality :cumulative
                          :boundaries boundaries
                          :data-points [{:bucket-counts [100 200 150 50 25]
                                         :count 525
                                         :sum 500.0
                                         :attributes {"worker.id" "w1"}}]})]})
          ;; Second export: lower cumulative values (service restart)
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "worker-meter"
              :metrics [(h/build-histogram-metric
                         {:name metric-name
                          :unit "s"
                          :temporality :cumulative
                          :boundaries boundaries
                          :data-points [{:bucket-counts [5 10 8 3 2]
                                         :count 28
                                         :sum 25.0
                                         :attributes {"worker.id" "w1"}}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT \"hist.count\", \"hist.sum\" FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      (is (= 1 (count rows)) "Should have one row from reset observation")
      (let [row (first rows)]
        ;; Reset detection should use current values as delta
        (is (= 28 (:hist.count row)) "Reset should use current count (28) as delta")
        (is (= 25.0 (:hist.sum row)) "Reset should use current sum (25.0) as delta")))))

;; ---------------------------------------------------------
;; Immutable Field Validation Tests

(deftest immutable-field-unit-conflict-test
  (testing "Metrics with conflicting unit are rejected"
    (let [service-name "unit-conflict-test-service"
          metric-name "request.latency.unit"
          ;; First export: unit is "ms"
          _ (h/export-metrics!
             {:service-name service-name
              :meter-name "test-meter"
              :metrics [(h/build-gauge-metric
                         {:name metric-name
                          :description "Request latency"
                          :unit "ms"
                          :data-points [{:value 100}]})]})
          ;; Second export: try to change unit to "s" (should be rejected)
          response (h/export-metrics!
                    {:service-name service-name
                     :meter-name "test-meter"
                     :metrics [(h/build-gauge-metric
                                {:name metric-name
                                 :unit "s"
                                 :data-points [{:value 0.1}]})]})
          duckdb (:db/duckdb h/*system*)
          rows (jdbc/execute! duckdb
                              ["SELECT value FROM o11ylite.metrics WHERE name = ?"
                               metric-name])]
      ;; Should have rejected the second export
      (is (= 1 (-> response .getPartialSuccess .getRejectedDataPoints)))
      ;; Only first data point should be persisted
      (is (= 1 (count rows)))
      (is (= 100.0 (:value (first rows)))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-grpc-metric-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
