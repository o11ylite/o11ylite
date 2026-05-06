;; ---------------------------------------------------------
;; o11ylite.components.metric-batcher
;;
;; Batches incoming metric data points and flushes them periodically.
;; Similar structure to event-batcher but for metrics.
;;
;; Batch structure:
;;   {:data-points [...]          - Flat maps with attr.* keys (normalized, ready to persist)
;;    :fields #{...}              - Set of field names for schema evolution
;;    :metadata {...}             - Map of metric-name -> {:description :unit :metric_type :attributes}
;;    :cumulative-to-commit [...] - Cumulative data points for normalizer state update
;;    :promises [...]}            - Delivery promises for callers
;; ---------------------------------------------------------

(ns o11ylite.components.metric-batcher
  (:require
    [clojure.core.async :as a]
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.components.app-config :as app-config]
    [o11ylite.components.telemetry-catalog-buffer :as catalog-buffer]
    [o11ylite.store.metrics.ingest :as metrics.ingest]
    [o11ylite.util.telemetry :as telemetry]
    [o11ylite.util.ticker :as ticker]))

;; ---------------------------------------------------------
;; Private Helpers

;; Max messages in channel before backpressure kicks in
(def ^:private ingest-channel-size-limit 1000000)

(defn- -flush!
  "Flush the batch to storage. Returns true on success, false on failure.
   On success, all pending promises are delivered true.
   On failure, all pending promises are delivered false."
  [{:keys [duckdb sqlite norm catalog-buffer]} batch]
  (let [{:keys [data-points fields metadata promises cumulative-to-commit]} @batch]
    (vreset! batch {:data-points [] :fields #{} :metadata {} :promises [] :cumulative-to-commit []})
    (if (and (empty? data-points) (empty? metadata) (empty? cumulative-to-commit))
      true
      (try
        ;; persist-batch! handles both persistence and normalizer state update
        (metrics.ingest/persist-batch! duckdb sqlite norm data-points fields metadata cumulative-to-commit)
        ;; Fire-and-forget: buffer extracts per-service metric names internally.
        (catalog-buffer/track-data-points! catalog-buffer data-points)
        (mulog/log ::batch-flushed
                   :o11ylite.metric_batcher.data_point_count (count data-points)
                   :o11ylite.metric_batcher.field_count (count fields)
                   :o11ylite.metric_batcher.metadata_count (count metadata))
        ;; Notify all callers of success
        (doseq [done promises]
          (deliver done true))
        true
        (catch Exception e
          (telemetry/report-error! ::flush-error e
                                   :o11ylite.metric_batcher.data_point_count (count data-points))
          ;; Notify all callers of failure
          (doseq [done promises]
            (deliver done false))
          false)))))

(defn- -accumulate!
  "Accumulate an ingest message into the batch.
   Concatenates data-points, unions fields, merges metadata (last-write-wins), tracks promise.
   Also accumulates cumulative-to-commit for normalizer state tracking."
  [batch {:keys [data-points fields metadata done cumulative-to-commit]}]
  (vswap! batch (fn [b]
                  (-> b
                      (update :data-points into data-points)
                      (update :fields into fields)
                      (update :metadata merge metadata)
                      (update :promises conj done)
                      (update :cumulative-to-commit into (or cumulative-to-commit []))))))

(defn- -drain-channel!
  "Drain remaining messages from channel into batch.
   Called during shutdown to capture in-flight messages."
  [ch batch]
  (loop []
    (when-let [msg (a/poll! ch)]
      (-accumulate! batch msg)
      (recur))))

(defn- -start-event-loop
  "Start the event loop that handles both ingest and periodic flush.
   Single thread owns the batch - no contention.
   Returns component state map."
  [{:keys [flush-interval-ms] :as deps}]
  (let [ingest-ch (a/chan ingest-channel-size-limit)
        ticker (ticker/ticker flush-interval-ms)
        ticker-ch (:ch ticker)
        batch (volatile! {:data-points [] :fields #{} :metadata {} :promises [] :cumulative-to-commit []})
        stopped? (promise)
        stop-called? (atom false)]
    ;; Start the event loop
    (future
      (loop []
        ;; Priority true: when both ready, prefer ticker (flush) over ingest
        (let [[v port] (a/alts!! [ticker-ch ingest-ch] :priority true)]
          (cond
            ;; Channel closed - exit loop
            (nil? v)
            (do
              (mulog/log ::event-loop-stopped)
              (deliver stopped? true))

            ;; Ticker fired - flush batch
            (= port ticker-ch)
            (do
              (-flush! deps batch)
              (recur))

            ;; Ingest message - accumulate into batch
            (= port ingest-ch)
            (do
              (-accumulate! batch v)
              (recur))))))
    ;; Return component state
    {:ingest-ch ingest-ch
     :batch batch
     :stop! (fn []
              (when (compare-and-set! stop-called? false true)
                ;; Stop ticker first (closes ticker-ch)
                (ticker/stop! ticker)
                ;; Close ingest channel to signal loop to exit
                (a/close! ingest-ch)
                ;; Wait for loop to exit
                (if (deref stopped? 5000 false)
                  (do
                    ;; Loop exited cleanly - drain and flush remaining
                    (-drain-channel! ingest-ch batch)
                    (-flush! deps batch)
                    (mulog/log ::metric-batcher-stopped))
                  ;; Loop did not exit in time - log error
                  (mulog/log ::metric-batcher-stop-timeout
                             :o11ylite.metric_batcher.error "Failed to stop gracefully"))))}))

;; ---------------------------------------------------------
;; Public API

(defn stop!
  "Stop the batcher. Flushes remaining data and stops the event loop."
  [batcher]
  ((:stop! batcher)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :ingest/metric-batcher
  [_ {:keys [duckdb sqlite normalizer telemetry-catalog-buffer app-config]}]
  (let [flush-interval-ms (app-config/get-setting-value app-config :metric-flush-interval-ms)]
    (mulog/log ::metric-batcher-starting :o11ylite.metric_batcher.flush_interval_ms flush-interval-ms)
    (let [state (-start-event-loop {:duckdb duckdb
                                    :sqlite sqlite
                                    :norm normalizer
                                    :catalog-buffer telemetry-catalog-buffer
                                    :flush-interval-ms flush-interval-ms})]
      (mulog/log ::metric-batcher-started)
      state)))

(defmethod ig/halt-key! :ingest/metric-batcher
  [_ batcher]
  (stop! batcher))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Start the batcher (requires duckdb, sqlite)
  ;; (def mb (ig/init-key :ingest/metric-batcher {:duckdb ds :sqlite sq}))

  ;; Ingest example using store.batcher/->batcher! (will block until flushed!)
  ;; (require '[o11ylite.store.batcher :as batcher])
  ;; (batcher/->batcher! mb {:data-points [...] :fields #{...} :metadata {...}})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
