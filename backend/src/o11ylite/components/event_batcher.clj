;; ---------------------------------------------------------
;; o11ylite.components.event-batcher
;;
;; Batches incoming events and flushes them periodically.
;; Uses actor model: single loop handles both ingest and flush via alts!!
;;
;; Backpressure & Delivery Guarantees:
;; -----------------------------------
;; Callers submit via batcher/->batcher! which blocks until events are
;; flushed to storage. This provides:
;;   1. Backpressure - if storage is slow, callers slow down naturally
;;   2. Delivery guarantee - when ->batcher! returns true, data is persisted
;;
;; Error Handling Strategy:
;; ------------------------
;; Flush operations are treated as atomic - either all events in a batch
;; succeed or all fail. This is a deliberate simplification:
;;   - DuckDB batch inserts are transactional (all-or-nothing)
;;   - Partial failure handling would add significant complexity
;;   - Validation should happen at the caller side before submission
;;   - On flush failure, all callers receive false and can decide to retry
;;
;; Batch Accumulation:
;; -------------------
;; Each submission contains {:events [...] :fields {name {:type t} ...}}.
;; The batcher accumulates:
;;   - events: concatenated into a single vector
;;   - fields: merged map of field-name -> {:type ...}
;;   - promises: tracked to notify callers on flush
;; ---------------------------------------------------------

(ns o11ylite.components.event-batcher
  (:require
    [clojure.core.async :as a]
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.components.app-config :as app-config]
    [o11ylite.components.telemetry-catalog-buffer :as catalog-buffer]
    [o11ylite.store.events.ingest :as events.ingest]
    [o11ylite.util.telemetry :as telemetry]
    [o11ylite.util.ticker :as ticker]
    [steffan-westcott.clj-otel.api.trace.span :as span]))

;; ---------------------------------------------------------
;; Private Helpers

;; Max messages in channel before backpressure kicks in
(def ^:private ingest-channel-size-limit 1000000)

(defn- -flush!
  "Flush the batch to storage. Returns true on success, false on failure.
   On success, all pending promises are delivered true.
   On failure, all pending promises are delivered false.
   Only called from the single event loop thread - no contention."
  [duckdb events-schema catalog-buffer batch]
  (let [{:keys [events fields promises]} @batch]
    (vreset! batch {:events [] :fields {} :promises []})
    (if (empty? events)
      true
      (span/with-span! [::flush-batch {:o11ylite.event_batcher.event_count (count events)
                                       :o11ylite.event_batcher.field_count (count fields)}]
        (try
          (events.ingest/persist-batch! duckdb events-schema events fields)
          ;; Fire-and-forget: buffer extracts per-service field sets internally.
          (catalog-buffer/track-events! catalog-buffer events)
          ;; Notify all callers of success
          (doseq [done promises]
            (deliver done true))
          true
          (catch Exception e
            (telemetry/report-error! ::flush-error e)
            ;; Notify all callers of failure
            (doseq [done promises]
              (deliver done false))
            false))))))

(defn- -accumulate!
  "Accumulate an ingest message into the batch.
   Concatenates events, merges fields, and tracks the promise."
  [batch {:keys [events fields done]}]
  (vswap! batch (fn [b]
                  (-> b
                      (update :events into events)
                      (update :fields merge fields)
                      (update :promises conj done)))))

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
  [{:keys [duckdb events-schema catalog-buffer flush-interval-ms]}]
  (let [ingest-ch (a/chan ingest-channel-size-limit)
        ticker (ticker/ticker flush-interval-ms)
        ticker-ch (:ch ticker)
        batch (volatile! {:events [] :fields {} :promises []})
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
              (-flush! duckdb events-schema catalog-buffer batch)
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
                ;; Note: stop fn can be called from another thread, so we must
                ;; ensure event loop stops before touching batch (volatile! isn't thread-safe)
                (if (deref stopped? 5000 false)
                  (do
                    ;; Loop exited cleanly - drain and flush remaining
                    (-drain-channel! ingest-ch batch)
                    (-flush! duckdb events-schema catalog-buffer batch)
                    (mulog/log ::event-batcher-stopped))
                  ;; Loop did not exit in time - log error, don't drain/flush
                  (mulog/log ::event-batcher-stop-timeout
                             :o11ylite.event_batcher.error "Failed to stop gracefully, event loop did not stop within timeout"))))}))

;; ---------------------------------------------------------
;; Public API

(defn stop!
  "Stop the batcher. Flushes remaining events and stops the event loop."
  [batcher]
  ((:stop! batcher)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :ingest/event-batcher
  [_ {:keys [duckdb events-schema telemetry-catalog-buffer app-config]}]
  (let [flush-interval-ms (app-config/get-setting-value app-config :ingest-flush-interval-ms)]
    (mulog/log ::event-batcher-starting :o11ylite.event_batcher.flush_interval_ms flush-interval-ms)
    (let [state (-start-event-loop {:duckdb duckdb
                                    :events-schema events-schema
                                    :catalog-buffer telemetry-catalog-buffer
                                    :flush-interval-ms flush-interval-ms})]
      (mulog/log ::event-batcher-started)
      state)))

(defmethod ig/halt-key! :ingest/event-batcher
  [_ batcher]
  (stop! batcher))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig]
           '[o11ylite.store.batcher :as batcher])

  ;; Start the batcher with 5 second flush interval
  (def event-batcher
    (ig/init-key :ingest/event-batcher {:flush-interval-ms 5000}))

  ;; Ingest some events (will block until flushed!)
  ;; Run in separate threads to avoid blocking REPL
  (future
    (println "ingest result:"
             (batcher/->batcher! event-batcher
                                 {:events [{:service "test" :name "span-1"}
                                           {:service "test" :name "span-2"}]
                                  :fields {"service" {:type :string}
                                           "name" {:type :string}}})))

  ;; Check batch contents (for debugging only)
  @(:batch event-batcher)

  ;; Stop the batcher (will flush remaining events)
  (ig/halt-key! :ingest/event-batcher event-batcher)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
