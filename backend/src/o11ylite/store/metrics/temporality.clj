;; ---------------------------------------------------------
;; o11ylite.store.metrics.temporality
;;
;; Temporality normalization for metric data points.
;;
;; Converts cumulative sum metrics to delta by computing the difference
;; from the previous value. First-seen cumulative observations are dropped
;; (no previous value to compute delta from) but tracked for state initialization.
;;
;; For monotonic cumulative sums, reset detection is performed:
;; when current < previous, it indicates a counter reset (e.g., service restart).
;; In this case, the current value is used as the delta instead of a negative value.
;;
;; This module works with the metric-temporality-normalizer component,
;; which maintains the in-memory state of previous values per series.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.temporality
  (:require
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.components.metric-temporality-normalizer :as normalizer]))

;; ---------------------------------------------------------
;; Normalization

(defn- -normalize-cumulative
  "Normalize a cumulative data point to delta.
   
   For monotonic sums, detects resets (current < previous) and uses
   the current value as delta instead of computing a negative delta.
   
   Arguments:
     norm          - The normalizer component
     data-point    - The cumulative data point
     is-monotonic? - Whether this metric is monotonic (from metadata)
   
   Returns a map with:
     :normalized - data point with delta value, or nil if first-seen
     :original   - original data point (always, for normalizer state tracking)"
  [norm data-point is-monotonic?]
  (if-let [{:keys [delta]} (normalizer/compute-delta norm data-point)]
    ;; Has previous state - convert to delta
    (let [reset? (and is-monotonic? (neg? delta))
          effective-delta (if reset? (:value data-point) delta)]
      (when reset?
        (mulog/log ::monotonic-reset-detected
                   :metric-name (:name data-point)
                   :previous-value (+ (:value data-point) (- delta)) ; reconstruct previous
                   :current-value (:value data-point)))
      {:normalized (-> data-point
                       (assoc :value effective-delta)
                       (dissoc :temporality))
       :original data-point})
    ;; First observation - drop but track for state initialization
    {:normalized nil
     :original data-point}))

(defn normalize-temporality
  "Normalize temporality for all data points.
   
   Processing by temporality type:
   - :cumulative - Convert to delta using normalizer state
                   First-seen series are dropped but tracked for state commit
                   For monotonic sums, resets are detected and handled
   - :delta      - Pass through, remove :temporality key
   - nil/other   - Pass through unchanged (gauges, etc.)
   
   Arguments:
     normalizer       - The metric-temporality-normalizer component
     data-points      - Collection of data point maps (may include :temporality key)
     metrics-metadata - Map of metric-name -> {:is_monotonic bool, ...}
   
   Returns:
     {:normalized           [...] - Data points to persist (delta values, gauges)
      :cumulative-to-commit [...] - All cumulative data points with original values
                                    (for normalizer state update after persist)}"
  [normalizer data-points metrics-metadata]
  (let [results (map (fn [dp]
                       (case (:temporality dp)
                         :cumulative (let [is-monotonic? (get-in metrics-metadata [(:name dp) :is_monotonic] false)]
                                       (-normalize-cumulative normalizer dp is-monotonic?))
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

  ;; Reset detection (monotonic sum, current < previous):
  ;; Previous state = 1000, current observation = 50 (service restarted)
  ;; normalize-temporality returns:
  ;;   {:normalized [{:name "m" :value 50}]  ; use current as delta, not -950
  ;;    :cumulative-to-commit [{:name "m" :value 50 :temporality :cumulative}]}
  ;; -> Logs ::monotonic-reset-detected, persists delta=50

  ;; Delta metrics pass through unchanged (just remove :temporality)
  ;; {:name "m" :value 10 :temporality :delta}
  ;; -> in :normalized as {:name "m" :value 10}

  ;; Gauges have no :temporality, pass through as-is
  ;; {:name "g" :value 42}
  ;; -> in :normalized as {:name "g" :value 42}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
