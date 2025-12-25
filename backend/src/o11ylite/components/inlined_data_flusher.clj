;; ---------------------------------------------------------
;; o11ylite.components.inlined-data-flusher
;;
;; Periodic worker that flushes DuckLake inlined data to Parquet files.
;; DuckLake's data inlining stores small writes in the metadata catalog
;; for efficiency. This component periodically flushes that data to
;; proper Parquet files in the data path.
;;
;; See: https://ducklake.select/docs/stable/duckdb/advanced_features/data_inlining
;; ---------------------------------------------------------

(ns o11ylite.components.inlined-data-flusher
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.store.ducklake :as ducklake]
   [o11ylite.util.ticker :as ticker]))

;; ---------------------------------------------------------
;; Configuration

(def ^:private default-flush-interval-ms
  "Default flush interval (15 minutes)."
  (* 15 60 1000))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -flush!
  "Flush inlined data to Parquet files."
  [duckdb]
  (try
    (ducklake/flush-inlined-data! duckdb)
    (catch Exception e
      ;; TODO Ideally we should log the issue to application level so user can diagnose from UI.
      (mulog/log ::inlined-data-flush-failed :error (.getMessage e)))))

(defn- -start-flush-loop
  "Start background flush loop using ticker."
  [duckdb flush-interval-ms]
  ;; TODO This isn't our final design.
  ;; e.g. if the app restart once between the internal then we might never get the flush happen.
  ;; e.g. we also don't have much visibility on when the last flush happens etc. So we might need an audit log.
  ;; Ideally we should have a deterministic scheduling system that survives restarting.
  (let [t (ticker/ticker flush-interval-ms)]
    (future
      (loop []
        (when (ticker/tick! t)
          (-flush! duckdb)
          (recur))))
    t))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :ingest/inlined-data-flusher
  [_ {:keys [duckdb flush-interval-ms]
      :or {flush-interval-ms default-flush-interval-ms}}]
  (mulog/log ::inlined-data-flusher-starting :flush-interval-ms flush-interval-ms)
  ;; Start background loop (no initial flush - let data accumulate first)
  (let [ticker (-start-flush-loop duckdb flush-interval-ms)]
    (mulog/log ::inlined-data-flusher-started)
    {:ticker ticker}))

(defmethod ig/halt-key! :ingest/inlined-data-flusher
  [_ {:keys [ticker]}]
  (when ticker
    (ticker/stop! ticker))
  (mulog/log ::inlined-data-flusher-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])
  (require '[o11ylite.store.ducklake :as ducklake])

  ;; Start DuckDB pool
  (def duckdb (ig/init-key :db/duckdb {:data-path "./.tmp"}))
  (def storage (ig/init-key :storage/init {:sqlite nil :duckdb duckdb}))

  ;; Start inlined data flusher (with fast interval for testing)
  (def flusher
    (ig/init-key :ingest/inlined-data-flusher
                 {:duckdb duckdb
                  :flush-interval-ms 10000}))

  ;; Manual flush
  (ducklake/flush-inlined-data! duckdb)

  ;; Cleanup
  (ig/halt-key! :ingest/inlined-data-flusher flusher)
  (ig/halt-key! :storage/init storage)
  (ig/halt-key! :db/duckdb duckdb)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
