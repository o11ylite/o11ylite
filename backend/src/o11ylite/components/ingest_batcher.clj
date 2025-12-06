;; ---------------------------------------------------------
;; o11ylite.components.ingest-batcher
;;
;; Batches incoming events and flushes them periodically.
;; Uses actor model: single loop handles both ingest and flush via alts!!
;;
;; Backpressure & Delivery Guarantees:
;; -----------------------------------
;; Callers of ingest! block until their events are successfully flushed to
;; storage. This provides:
;;   1. Backpressure - if storage is slow, callers slow down naturally
;;   2. Delivery guarantee - when ingest! returns true, data is persisted
;;
;; Error Handling Strategy:
;; ------------------------
;; Flush operations are treated as atomic - either all events in a batch
;; succeed or all fail. This is a deliberate simplification:
;;   - DuckDB batch inserts are transactional (all-or-nothing)
;;   - Partial failure handling would add significant complexity
;;   - Validation should happen at the caller side before ingest!
;;   - On flush failure, all callers receive false and can decide to retry
;;
;; Batch Accumulation:
;; -------------------
;; Each ingest! call submits {:events [...] :fields {name {:type t} ...}}.
;; The batcher accumulates:
;;   - events: concatenated into a single vector
;;   - fields: merged map of field-name -> {:type ...}
;;   - promises: tracked to notify callers on flush
;; ---------------------------------------------------------

(ns o11ylite.components.ingest-batcher
  (:require
   [clojure.core.async :as a]
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.ducklake.events.ingest :as events.ingest]
   [o11ylite.util.ticker :as ticker]))

;; ---------------------------------------------------------
;; Private Helpers

;; Max messages in channel before backpressure kicks in
(def ^:private ingest-channel-size-limit 1000000)

(defn- -flush!
  "Flush the batch to storage. Returns true on success, false on failure.
   On success, all pending promises are delivered true.
   On failure, all pending promises are delivered false.
   Only called from the single event loop thread - no contention."
  [duckdb event-metadata batch]
  (let [{:keys [events fields promises]} @batch]
    (vreset! batch {:events [] :fields {} :promises []})
    (if (empty? events)
      true
      (try
        (events.ingest/persist-batch! duckdb event-metadata events fields)
        (mulog/log ::batch-flushed :event-count (count events)
                   :field-count (count fields))
        ;; Notify all callers of success
        (doseq [done promises]
          (deliver done true))
        true
        (catch Exception e
          (mulog/log ::flush-error :error (.getMessage e) :event-count (count events))
          ;; Notify all callers of failure
          (doseq [done promises]
            (deliver done false))
          false)))))

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
  [duckdb event-metadata flush-interval-ms]
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
              (-flush! duckdb event-metadata batch)
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
                    (-flush! duckdb event-metadata batch)
                    (mulog/log ::ingest-batcher-stopped))
                  ;; Loop did not exit in time - log error, don't drain/flush
                  (mulog/log ::ingest-batcher-stop-timeout
                             :error "Failed to stop gracefully, event loop did not stop within timeout"))))}))

;; ---------------------------------------------------------
;; Public API

(defn ingest!
  "Add events to the batch. Blocks until the events are flushed to storage.
   
   Arguments:
     batcher - The batcher component
     payload - Map with :events (vector of event maps) and 
               :fields (map of field-name -> {:type ...})
   
   Returns:
     true if events were persisted, false if flush failed.
   
   Callers should validate events before calling ingest! - the batcher
   treats flush as atomic and does not handle partial failures."
  [batcher {:keys [events fields]}]
  (let [done (promise)]
    (a/>!! (:ingest-ch batcher) {:events events
                                 :fields (or fields {})
                                 :done done})
    @done))

(defn stop!
  "Stop the batcher. Flushes remaining events and stops the event loop."
  [batcher]
  ((:stop! batcher)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :ingest/batcher
  [_ {:keys [duckdb event-metadata flush-interval-ms]
      :or {flush-interval-ms 60000}}]
  (mulog/log ::ingest-batcher-starting :flush-interval-ms flush-interval-ms)
  (let [state (-start-event-loop duckdb event-metadata flush-interval-ms)]
    (mulog/log ::ingest-batcher-started)
    state))

(defmethod ig/halt-key! :ingest/batcher
  [_ batcher]
  (stop! batcher))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start the batcher with 5 second flush interval
  (def batcher
    (ig/init-key :ingest/batcher {:flush-interval-ms 5000}))

  ;; Ingest some events (will block until flushed!)
  ;; Run in separate threads to avoid blocking REPL
  (future
    (println "ingest result:"
             (ingest! batcher {:events [{:service "test" :name "span-1"}
                                        {:service "test" :name "span-2"}]
                               :fields {"service" {:type :string}
                                        "name" {:type :string}}})))

  ;; Check batch contents (for debugging only)
  @(:batch batcher)

  ;; Stop the batcher (will flush remaining events)
  (ig/halt-key! :ingest/batcher batcher)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
