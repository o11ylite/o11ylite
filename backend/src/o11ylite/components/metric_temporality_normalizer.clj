;; ---------------------------------------------------------
;; o11ylite.components.metric-temporality-normalizer
;;
;; In-memory normalizer for metric temporality conversion.
;; Converts cumulative sum metrics to delta by tracking previous values.
;;
;; State structure:
;;   {series-key -> {:value <number> :last-seen <instant>}}
;;
;; Series key is derived from (metric-name, sorted-attributes).
;;
;; Design notes:
;; - State is updated AFTER persist-batch! to ensure we don't "commit"
;;   previous values until data is durably written.
;; - TTL-based eviction prevents unbounded memory growth.
;; ---------------------------------------------------------

(ns o11ylite.components.metric-temporality-normalizer
  (:require
   [clojure.core.async :as a]
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.store.metrics.series :as series]
   [o11ylite.util.ticker :as ticker])
  (:import
   [java.time Instant]))

;; ---------------------------------------------------------
;; Private Helpers - TTL Cleanup

(defn- -evict-expired!
  "Remove entries older than TTL from state.
   Returns the number of evicted entries."
  [state-atom ttl-ms]
  (let [now (Instant/now)
        cutoff (.minusMillis now ttl-ms)
        before-count (count @state-atom)]
    (swap! state-atom
           (fn [state]
             (into {}
                   (filter (fn [[_ {:keys [last-seen]}]]
                             (.isAfter last-seen cutoff)))
                   state)))
    (let [evicted (- before-count (count @state-atom))]
      (when (pos? evicted)
        (mulog/log ::ttl-eviction :evicted-count evicted))
      evicted)))

(defn- -start-cleanup-ticker
  "Start background ticker for TTL cleanup.
   Returns ticker state for stopping."
  [state-atom ttl-ms cleanup-interval-ms]
  (let [tick (ticker/ticker cleanup-interval-ms)
        tick-ch (:ch tick)]
    (a/go-loop []
      (when (a/<! tick-ch)
        (-evict-expired! state-atom ttl-ms)
        (recur)))
    tick))

;; ---------------------------------------------------------
;; Public API

(defn compute-delta
  "Compute delta value for a cumulative data point.
   
   Returns:
     {:delta <number>} if previous value exists
     nil if this is the first observation (caller should drop the data point)
   
   Note: Does NOT update state. Call commit-batch! after persist-batch!."
  [normalizer data-point]
  (let [state-atom (:state normalizer)
        key (series/series-key data-point)
        current-value (:value data-point)]
    (when-let [entry (get @state-atom key)]
      {:delta (- current-value (:value entry))})))

(defn commit-batch!
  "Update normalizer state with values from a persisted batch.
   
   Should be called AFTER persist-batch! succeeds to ensure we only
   track values that were durably written.
   
   For each series in the batch, stores the last value seen."
  [normalizer data-points]
  (let [state-atom (:state normalizer)
        now (Instant/now)
        ;; Group by series key, keep last value per series
        by-series (group-by series/series-key data-points)
        updates (into {}
                      (map (fn [[key dps]]
                             [key {:value (:value (last dps))
                                   :last-seen now}]))
                      by-series)]
    (swap! state-atom merge updates)
    (mulog/log ::batch-committed
               :series-count (count updates)
               :total-state-size (count @state-atom))))

(defn clear!
  "Clear all state. For testing only."
  [normalizer]
  (reset! (:state normalizer) {}))

(defn stop!
  "Stop the normalizer. Stops the cleanup ticker."
  [normalizer]
  (when-let [tick (:cleanup-ticker normalizer)]
    (ticker/stop! tick))
  (mulog/log ::normalizer-stopped))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :ingest/metric-normalizer
  [_ {:keys [ttl-ms cleanup-interval-ms]
      :or {ttl-ms 1800000            ; 30 minutes
           cleanup-interval-ms 60000}}] ; 1 minute
  (mulog/log ::normalizer-starting
             :ttl-ms ttl-ms
             :cleanup-interval-ms cleanup-interval-ms)
  (let [state-atom (atom {})
        cleanup-ticker (-start-cleanup-ticker state-atom ttl-ms cleanup-interval-ms)]
    (mulog/log ::normalizer-started)
    {:state state-atom
     :ttl-ms ttl-ms
     :cleanup-ticker cleanup-ticker}))

(defmethod ig/halt-key! :ingest/metric-normalizer
  [_ normalizer]
  (stop! normalizer))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start normalizer
  (def normalizer (ig/init-key :ingest/metric-normalizer {}))

  ;; Example data point
  (def dp {:name "http.requests"
           :value 100
           :attr.method "GET"
           :attr.status "200"})

  ;; Generate series key (use metrics.series namespace directly)
  (series/series-key dp)
  ;; => "http.requests|method=GET,status=200"

  ;; First observation - no previous value
  (compute-delta normalizer dp)
  ;; => nil

  ;; Commit the batch
  (commit-batch! normalizer [dp])

  ;; Second observation with new value
  (def dp2 (assoc dp :value 150))
  (compute-delta normalizer dp2)
  ;; => {:delta 50}

  ;; Cleanup
  (ig/halt-key! :ingest/metric-normalizer normalizer)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
