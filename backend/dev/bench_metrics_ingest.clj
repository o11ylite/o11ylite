;; ---------------------------------------------------------
;; bench-metrics-ingest
;;
;; Benchmark for metric ingestion pipeline performance.
;; Measures persist-batch! throughput at various data point counts
;; using the DuckDB Appender API + staging table approach.
;;
;; Usage (from REPL, after starting system with (go)):
;;   (require 'bench-metrics-ingest)
;;   (bench-metrics-ingest/run-benchmark)
;;   (bench-metrics-ingest/run-benchmark {:data-point-counts [100 500 1000]})
;; ---------------------------------------------------------

(ns bench-metrics-ingest
  (:require
    [integrant.repl.state :as state]
    [o11ylite.store.metrics.ingest :as metrics.ingest]
    [o11ylite.test-helpers.metric-ingest :as metric-gen]))

;; ---------------------------------------------------------
;; Private Helpers

(def ^:private default-data-point-counts
  "Default data point counts to benchmark."
  [100 1000 5000 10000 50000])

(defn- -system
  "Return the running system, or throw if not started."
  []
  (or state/system
      (throw (ex-info "System not started. Run (go) first." {}))))

(defn- -generate-metric-batch
  "Generate n random metric data points with metadata and extracted fields.
   Returns {:data-points [...] :metadata {...} :fields #{...}}."
  [n]
  (let [data-points (vec (metric-gen/make-random-metric-data-points n))
        metadata (metric-gen/make-metrics-metadata data-points)
        fields (#'metrics.ingest/-extract-fields data-points)]
    {:data-points data-points
     :metadata metadata
     :fields fields}))

(defn- -time-ms
  "Execute f and return [elapsed-ms result]."
  [f]
  (let [start (System/nanoTime)
        result (f)
        elapsed-ns (- (System/nanoTime) start)]
    [(/ elapsed-ns 1e6) result]))

(defn- -print-table-header
  "Print the benchmark result table header."
  []
  (println)
  (println (format "  %-12s  %12s  %14s" "Data Points" "Elapsed (ms)" "Throughput/sec"))
  (println (format "  %-12s  %12s  %14s" "───────────" "────────────" "──────────────")))

(defn- -print-table-row
  "Print a single benchmark result row."
  [data-point-count elapsed-ms]
  (let [throughput (if (pos? elapsed-ms)
                     (/ data-point-count (/ elapsed-ms 1000.0))
                     ##Inf)]
    (println (format "  %-12d  %12.1f  %14.0f" data-point-count elapsed-ms throughput))))

(defn- -run-persist-batch!
  "Run persist-batch! and return elapsed-ms."
  [duckdb sqlite norm data-points fields metadata]
  (let [[elapsed-ms _] (-time-ms
                         #(metrics.ingest/persist-batch!
                            duckdb sqlite norm data-points fields metadata []))]
    elapsed-ms))

;; ---------------------------------------------------------
;; Public API

(defn run-benchmark
  "Benchmark persist-batch! throughput at various data point counts.

   Runs a warmup pass first, then measures wall-clock time for each
   data point count. Prints a formatted result table.

   Options (all optional):
     :data-point-counts  - vector of data point counts to test (default: [100 1000 5000 10000 50000])
     :system             - system map (default: @integrant.repl.state/system)"
  ([] (run-benchmark {}))
  ([{:keys [data-point-counts system]
     :or {data-point-counts default-data-point-counts}}]
   (let [system (or system (-system))
         duckdb (:db/duckdb system)
         sqlite (:db/sqlite system)
         norm (:norm/metric-temporality system)]

     ;; Warmup
     (print "  Warming up (100 data points)... ")
     (flush)
     (let [{:keys [data-points fields metadata]} (-generate-metric-batch 100)]
       (-run-persist-batch! duckdb sqlite norm data-points fields metadata))
     (println "done.")

     ;; Benchmark runs
     (println "\n  Metric persist-batch! benchmark")
     (-print-table-header)

     (doseq [n data-point-counts]
       (let [{:keys [data-points fields metadata]} (-generate-metric-batch n)
             elapsed-ms (-run-persist-batch! duckdb sqlite norm data-points fields metadata)]
         (-print-table-row n elapsed-ms)))

     (println))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run the standard benchmark (make sure system is started with (go) first)
  (run-benchmark)

  ;; Run with custom data point counts
  (run-benchmark {:data-point-counts [100 500 1000]})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
