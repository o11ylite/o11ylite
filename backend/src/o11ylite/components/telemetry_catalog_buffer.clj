;; ---------------------------------------------------------
;; o11ylite.components.telemetry-catalog-buffer
;;
;; Actor-model buffer that persists service-level telemetry ownership +
;; liveness into SQLite:
;;   - service_metrics       (service, metric_name, last_seen_at)
;;   - service_event_fields  (service, field,       last_seen_at)
;;   - service_metadata      (.last_seen_at updated during sweep)
;;
;; Producers (event-batcher, metric-batcher persist-batch!) call the
;; fire-and-forget track-* helpers, which `a/put!` a message on a dropping
;; channel. A single event-loop thread accumulates messages into an
;; in-memory state; a ticker triggers periodic sweeps (default 5 minutes)
;; that diff the accumulator against an in-memory cache and batch-UPSERT
;; the deltas + bump last_seen_at for everything observed.
;;
;; Naming: this is the telemetry *catalog*. Not to be confused with:
;;   - :cache/events-schema             (DuckDB column schema cache)
;;   - o11ylite.store.metrics.metadata  (per-metric definitions)
;; See the plan at .opencode/plans/telemetry-catalog.md for context.
;;
;; Design notes:
;;   - Dropping buffer: tracking is opportunistic. If the producer outpaces
;;     the consumer, drop messages silently — the next ingest will re-add
;;     the same (service, metric/field) pairs.
;;   - No delivery promise: producers never block and never learn whether
;;     their put! succeeded.
;;   - Best-effort sweep: on SQLite error we log, reset the accumulator,
;;     and move on. Re-adding the failed batch would risk unbounded growth
;;     if SQLite is persistently broken.
;;   - Cache hydration at startup means the first sweep writes almost
;;     nothing beyond last_seen_at bumps — expected.
;; ---------------------------------------------------------

(ns o11ylite.components.telemetry-catalog-buffer
  (:require
    [clojure.core.async :as a]
    [com.brunobonacci.mulog :as mulog]
    [integrant.core :as ig]
    [o11ylite.store.services :as services]
    [o11ylite.store.telemetry-catalog :as catalog]
    [o11ylite.util.telemetry :as telemetry]
    [o11ylite.util.ticker :as ticker]
    [steffan-westcott.clj-otel.api.trace.span :as span]))

;; ---------------------------------------------------------
;; Configuration

(def ^:private default-sweep-interval-ms
  "How often to flush accumulated tracking into SQLite (5 minutes)."
  (* 5 60 1000))

(def ^:private ingest-channel-size
  "Max pending track-* messages before drop-buffer starts discarding.
   Sized generously; producers only put! once per batch flush, not per event."
  8192)

;; ---------------------------------------------------------
;; Accumulator & cache helpers

(defn- -empty-accumulator
  []
  {:metrics #{}         ; #{[service metric-name] ...}
   :event-fields {}     ; {service -> #{field ...}}
   :services #{}})      ; observed service names (union of both above)

(defn- -normalize-field
  "Coerce an event key (keyword or string) into the string form we store in
   service_event_fields."
  [k]
  (if (keyword? k) (name k) (str k)))

(defn- -accumulate-events
  "Walk a batch of events once, folding each event's (service, field-set)
   into the accumulator. Events without :service are skipped."
  [acc events]
  (reduce
    (fn [a event]
      (if-let [service (:service event)]
        (-> a
            (update :services conj service)
            (update-in [:event-fields service]
                       (fn [existing]
                         (reduce (fn [s k] (conj s (-normalize-field k)))
                                 (or existing #{})
                                 (keys event)))))
        a))
    acc
    events))

(defn- -accumulate-data-points
  "Walk a batch of metric data points once, folding each (service, metric-name)
   pair into the accumulator. Data points missing :service or :name are skipped."
  [acc data-points]
  (reduce
    (fn [a dp]
      (let [service (:service dp)
            metric-name (:name dp)]
        (if (and service metric-name)
          (-> a
              (update :services conj service)
              (update :metrics conj [service metric-name]))
          a)))
    acc
    data-points))

(defn- -accumulate!
  "Merge an incoming track-* message into the accumulator.

   Messages carry raw telemetry; extraction of the (service, metric-name)
   pairs and (service, field-set) map happens here on the event-loop
   thread, keeping producers dumb."
  [acc {:keys [signal] :as msg}]
  (case signal
    :events (-accumulate-events acc (:events msg))
    :metrics (-accumulate-data-points acc (:data-points msg))
    ;; Unknown signal — ignore. Surface via log for debugging.
    (do (mulog/log ::catalog-buffer-unknown-signal :o11ylite.catalog_buffer.signal signal)
        acc)))

(defn- -diff-metrics
  "Return (service, metric) pairs in `observed` that aren't in `cache`."
  [cache observed]
  (into #{} (remove cache) observed))

(defn- -diff-event-fields
  "Return (service, field) pairs observed that aren't in cache.
   cache and observed both shape as {service -> #{field ...}}."
  [cache observed]
  (reduce-kv
    (fn [acc service fields]
      (let [known (get cache service #{})
            new-fields (into #{} (remove known) fields)]
        (if (seq new-fields)
          (assoc acc service new-fields)
          acc)))
    {}
    observed))

(defn- -merge-event-fields-into-cache
  "Cache shape: {service -> #{field ...}}. Observed has the same shape.
   Returns a new cache with all observed entries merged in."
  [cache observed]
  (reduce-kv
    (fn [acc service fields]
      (update acc service (fnil into #{}) fields))
    cache
    observed))

(defn- -now-ms
  []
  (System/currentTimeMillis))

;; ---------------------------------------------------------
;; Sweep

(defn- -sweep!
  "Flush the accumulator to SQLite and update the in-memory cache.
   Single-threaded: only called from the event loop (via ticker or the
   explicit sweep request). `accumulator` and `cache` are volatiles owned
   by that loop.

   Best-effort: on SQLite error we log, reset the accumulator, and
   continue. The cache stays in its last-good state so the next sweep
   re-attempts the deltas that just failed."
  [sqlite accumulator cache]
  (let [{:keys [metrics event-fields services]} @accumulator
        now (-now-ms)]
    (vreset! accumulator (-empty-accumulator))
    (try
      (let [metric-deltas (-diff-metrics (:metrics @cache) metrics)
            field-deltas (-diff-event-fields (:event-fields @cache) event-fields)
            ;; UPSERT everything observed, not just deltas, so last_seen_at
            ;; is bumped for existing rows too.
            metric-rows (mapv (fn [[service metric-name]]
                                {:service service
                                 :metric-name metric-name
                                 :last-seen-at now})
                              metrics)
            field-rows (into []
                             (mapcat (fn [[service fields]]
                                       (map (fn [field]
                                              {:service service
                                               :field field
                                               :last-seen-at now})
                                            fields)))
                             event-fields)]
        (catalog/upsert-service-metrics! sqlite metric-rows)
        (catalog/upsert-service-event-fields! sqlite field-rows)
        (services/upsert-services! sqlite services now)
        (vswap! cache (fn [c]
                        (-> c
                            (update :metrics into metrics)
                            (update :event-fields -merge-event-fields-into-cache event-fields))))
        (mulog/log ::catalog-sweep-ok
                   :o11ylite.catalog_buffer.service_count (count services)
                   :o11ylite.catalog_buffer.metric_row_count (count metric-rows)
                   :o11ylite.catalog_buffer.field_row_count (count field-rows)
                   :o11ylite.catalog_buffer.new_metric_pair_count (count metric-deltas)
                   :o11ylite.catalog_buffer.new_field_pair_count (reduce + (map count (vals field-deltas)))))
      (catch Exception e
        (telemetry/report-error! ::catalog-sweep-failed e)))))

;; ---------------------------------------------------------
;; Event loop

(defn- -drain-channel!
  "Drain remaining messages from `ch` into `accumulator`. Used during
   shutdown so in-flight tracking isn't lost."
  [ch accumulator]
  (loop []
    (when-let [msg (a/poll! ch)]
      (vswap! accumulator -accumulate! msg)
      (recur))))

(defn- -hydrate-cache
  "Initialize the in-memory cache from SQLite so the first sweep after
   restart is mostly no-op (only `last_seen_at` bumps + genuinely new
   entries)."
  [sqlite]
  {:metrics (catalog/get-all-service-metrics sqlite)
   :event-fields (catalog/get-all-service-event-fields sqlite)})

(defn- -start-event-loop
  "Run the actor loop in a virtual thread. Returns component state map."
  [sqlite sweep-interval-ms]
  (let [ingest-ch (a/chan (a/dropping-buffer ingest-channel-size))
        sweep-ch (a/chan)
        ticker (ticker/ticker sweep-interval-ms)
        ticker-ch (:ch ticker)
        accumulator (volatile! (-empty-accumulator))
        cache (volatile! (-hydrate-cache sqlite))
        stopped? (promise)
        stop-called? (atom false)]
    (future
      (loop []
        (let [[v port] (a/alts!! [ticker-ch sweep-ch ingest-ch] :priority true)]
          (cond
            (nil? v)
            (do
              (mulog/log ::catalog-buffer-loop-stopped)
              (deliver stopped? true))

            (or (= port ticker-ch) (= port sweep-ch))
            (do
              (span/with-span!
                [::sweep {:o11ylite.catalog_buffer.sweep_trigger (if (= port sweep-ch) :manual :ticker)}]
                (-sweep! sqlite accumulator cache))
              (when (= port sweep-ch)
                (deliver v true))
              (recur))

            (= port ingest-ch)
            (do
              (span/with-span!
                [::accumulate {:o11ylite.catalog_buffer.signal (:signal v)}]
                (vswap! accumulator -accumulate! v))
              (recur))))))
    {:ingest-ch ingest-ch
     :sweep-ch sweep-ch
     :accumulator accumulator
     :cache cache
     :stop! (fn []
              (when (compare-and-set! stop-called? false true)
                (ticker/stop! ticker)
                (a/close! sweep-ch)
                (a/close! ingest-ch)
                (if (deref stopped? 5000 false)
                  (do
                    (-drain-channel! ingest-ch accumulator)
                    (-sweep! sqlite accumulator cache)
                    (mulog/log ::catalog-buffer-stopped))
                  (mulog/log ::catalog-buffer-stop-timeout
                             :o11ylite.catalog_buffer.error "Event loop did not stop within timeout"))))}))

;; ---------------------------------------------------------
;; Public fire-and-forget API
;;
;; Producers hand over raw collections. The actor does the per-service
;; extraction on its event-loop thread. This keeps producers ignorant of
;; the catalog's internal shape and makes it trivial to add new call sites.

(defn track-events!
  "Record that the given events were successfully persisted. The buffer
   extracts (service, field-set) pairs from each event's :service key and
   top-level keys. Fire-and-forget (dropping buffer)."
  [catalog-buffer events]
  (when (seq events)
    (a/put! (:ingest-ch catalog-buffer)
            {:signal :events :events events})))

(defn track-data-points!
  "Record that the given metric data points were successfully persisted.
   The buffer extracts (service, metric-name) pairs from each data point's
   :service and :name. Fire-and-forget (dropping buffer)."
  [catalog-buffer data-points]
  (when (seq data-points)
    (a/put! (:ingest-ch catalog-buffer)
            {:signal :metrics :data-points data-points})))

(defn sweep!
  "Trigger a synchronous sweep and block until it completes. Intended for
   tests and manual REPL use — production relies on the ticker. Returns
   true once the event loop signals completion; returns false if the
   catalog buffer is already stopped."
  [catalog-buffer]
  (let [done (promise)]
    (if (a/put! (:sweep-ch catalog-buffer) done)
      (deref done 5000 false)
      false)))

(defn stop!
  "Stop the buffer. Drains pending messages and performs one final sweep."
  [catalog-buffer]
  ((:stop! catalog-buffer)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :discovery/telemetry-catalog-buffer
  [_ {:keys [sqlite sweep-interval-ms]}]
  (let [interval (or sweep-interval-ms default-sweep-interval-ms)]
    (mulog/log ::catalog-buffer-starting :o11ylite.catalog_buffer.sweep_interval_ms interval)
    (let [state (-start-event-loop sqlite interval)]
      (mulog/log ::catalog-buffer-started)
      state)))

(defmethod ig/halt-key! :discovery/telemetry-catalog-buffer
  [_ buffer]
  (when buffer
    (stop! buffer)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def buf (:discovery/telemetry-catalog-buffer system))

  (track-data-points! buf [{:service "svc-a" :name "cpu.utilization"}
                           {:service "svc-a" :name "http.request.duration"}])
  (track-events! buf [{:service "svc-a" :trace_id "abc" :attr.http.method "GET"}])

  ;; Force sweep (otherwise waits for ticker)
  (sweep! buf)

  @(:accumulator buf)
  @(:cache buf)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
