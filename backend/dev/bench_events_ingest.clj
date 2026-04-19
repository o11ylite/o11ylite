;; ---------------------------------------------------------
;; bench-events-ingest
;;
;; Benchmark for DuckLake event ingestion performance.
;; Measures persist-batch! throughput at various event counts
;; using the DuckDB Appender API + staging table approach.
;;
;; Usage (from REPL, after starting system with (go)):
;;   (require 'bench-events-ingest)
;;   (bench-events-ingest/run-benchmark)
;;   (bench-events-ingest/run-benchmark {:event-counts [100 500 1000]})
;; ---------------------------------------------------------

(ns bench-events-ingest
  (:require
    [integrant.repl.state :as state]
    [o11ylite.store.events.enrich :as enrich]
    [o11ylite.store.events.ingest :as events.ingest]
    [o11ylite.test-helpers.event-ingest :as event-gen]))

;; ---------------------------------------------------------
;; Private Helpers

(def ^:private default-event-counts
  "Default event counts to benchmark."
  [100 1000 5000 10000 50000])

(defn- -system
  "Return the running system, or throw if not started."
  []
  (or state/system
      (throw (ex-info "System not started. Run (go) first." {}))))

(defn- -generate-enriched-events
  "Generate n random events and run them through enrichment + field extraction.
   Returns {:events [...] :fields {...}}."
  [id-generator n]
  (let [raw-events (event-gen/make-random-events n)
        events (enrich/enrich-events id-generator raw-events)
        fields (#'events.ingest/-extract-fields events)]
    {:events events
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
  (println (format "  %-12s  %12s  %14s" "Event Count" "Elapsed (ms)" "Throughput/sec"))
  (println (format "  %-12s  %12s  %14s" "───────────" "────────────" "──────────────")))

(defn- -print-table-row
  "Print a single benchmark result row."
  [event-count elapsed-ms]
  (let [throughput (if (pos? elapsed-ms)
                     (/ event-count (/ elapsed-ms 1000.0))
                     ##Inf)]
    (println (format "  %-12d  %12.1f  %14.0f" event-count elapsed-ms throughput))))

(defn- -run-persist-batch!
  "Run persist-batch! and return elapsed-ms."
  [duckdb events-schema events fields]
  (let [[elapsed-ms _] (-time-ms
                         #(#'events.ingest/persist-batch!
                           duckdb events-schema events fields))]
    elapsed-ms))

;; ---------------------------------------------------------
;; Public API

(defn run-benchmark
  "Benchmark persist-batch! throughput at various event counts.

   Runs a warmup pass first, then measures wall-clock time for each
   event count. Prints a formatted result table.

   Options (all optional):
     :event-counts  - vector of event counts to test (default: [100 1000 5000 10000 50000])
     :system        - system map (default: @integrant.repl.state/system)"
  ([] (run-benchmark {}))
  ([{:keys [event-counts system]
     :or {event-counts default-event-counts}}]
   (let [system (or system (-system))
         duckdb (:db/duckdb system)
         events-schema (:cache/events-schema system)
         id-generator (:id/generator system)]

     ;; Warmup
     (print "  Warming up (100 events)... ")
     (flush)
     (let [{:keys [events fields]} (-generate-enriched-events id-generator 100)]
       (-run-persist-batch! duckdb events-schema events fields))
     (println "done.")

     ;; Benchmark runs
     (println "\n  DuckLake persist-batch! benchmark")
     (-print-table-header)

     (doseq [n event-counts]
       (let [{:keys [events fields]} (-generate-enriched-events id-generator n)
             elapsed-ms (-run-persist-batch! duckdb events-schema events fields)]
         (-print-table-row n elapsed-ms)))

     (println))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run the standard benchmark (make sure system is started with (go) first)
  (run-benchmark)

  ;; Run with custom event counts
  (run-benchmark {:event-counts [100 500 1000]})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
