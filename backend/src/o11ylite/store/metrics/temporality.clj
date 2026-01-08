;; ---------------------------------------------------------
;; o11ylite.store.metrics.temporality
;;
;; Temporality normalization for metric data points.
;;
;; Converts cumulative sum metrics to delta by computing the difference
;; from the previous value. First-seen cumulative observations are dropped
;; (no previous value to compute delta from) but tracked for state initialization.
;;
;; This module works with the metric-temporality-normalizer component,
;; which maintains the in-memory state of previous values per series.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.temporality
  (:require
   [o11ylite.components.metric-temporality-normalizer :as normalizer]))

;; ---------------------------------------------------------
;; Normalization

(defn- -normalize-cumulative
  "Normalize a cumulative data point to delta.
   
   Returns a map with:
     :normalized - data point with delta value, or nil if first-seen
     :original   - original data point (always, for normalizer state tracking)"
  [norm data-point]
  (if-let [{:keys [delta]} (normalizer/compute-delta norm data-point)]
    ;; Has previous state - convert to delta
    {:normalized (-> data-point
                     (assoc :value delta)
                     (dissoc :temporality))
     :original data-point}
    ;; First observation - drop but track for state initialization
    {:normalized nil
     :original data-point}))

(defn normalize-temporality
  "Normalize temporality for all data points.
   
   Processing by temporality type:
   - :cumulative - Convert to delta using normalizer state
                   First-seen series are dropped but tracked for state commit
   - :delta      - Pass through, remove :temporality key
   - nil/other   - Pass through unchanged (gauges, etc.)
   
   Arguments:
     normalizer  - The metric-temporality-normalizer component
     data-points - Collection of data point maps (may include :temporality key)
   
   Returns:
     {:normalized           [...] - Data points to persist (delta values, gauges)
      :cumulative-to-commit [...] - All cumulative data points with original values
                                    (for normalizer state update after persist)}"
  [normalizer data-points]
  (let [results (map (fn [dp]
                       (case (:temporality dp)
                         :cumulative (-normalize-cumulative normalizer dp)
                         :delta {:normalized (dissoc dp :temporality)
                                 :original nil}
                         ;; Gauges and other types pass through unchanged
                         {:normalized dp
                          :original nil}))
                     data-points)]
    {:normalized (vec (keep :normalized results))
     :cumulative-to-commit (vec (keep :original results))}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: normalizing cumulative to delta
  ;; (requires a running normalizer component)

  ;; First observation (cumulative = 100)
  ;; normalize-temporality returns:
  ;;   {:normalized []
  ;;    :cumulative-to-commit [{:name "m" :value 100 :temporality :cumulative}]}
  ;; -> Nothing persisted, but commit-batch! will track value=100

  ;; Second observation (cumulative = 150)
  ;; normalize-temporality returns:
  ;;   {:normalized [{:name "m" :value 50}]  ; delta = 150 - 100
  ;;    :cumulative-to-commit [{:name "m" :value 150 :temporality :cumulative}]}
  ;; -> Persist delta=50, then commit-batch! updates state to value=150

  ;; Delta metrics pass through unchanged (just remove :temporality)
  ;; {:name "m" :value 10 :temporality :delta}
  ;; -> in :normalized as {:name "m" :value 10}

  ;; Gauges have no :temporality, pass through as-is
  ;; {:name "g" :value 42}
  ;; -> in :normalized as {:name "g" :value 42}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
