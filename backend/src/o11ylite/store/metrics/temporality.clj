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
;; Note on monotonicity:
;; - Sum metrics have an is_monotonic field (from OTLP proto) that indicates
;;   whether the counter can only increase.
;; - Histogram metrics do NOT have an is_monotonic field in OTLP because
;;   histograms are inherently monotonic: bucket counts can only increase
;;   within an aggregation period (you can't "un-record" an observation).
;;   Reset detection always applies to cumulative histograms.
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

(defn- -histogram-data-point?
  "Check if data point is a histogram (has :hist.counts key)."
  [data-point]
  (contains? data-point :hist.counts))

(defn- -normalize-cumulative-sum
  "Normalize a cumulative sum data point to delta.
   
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
      {:normalized (assoc data-point :value effective-delta)
       :original data-point})
    ;; First observation - drop but track for state initialization
    {:normalized nil
     :original data-point}))

(defn- -normalize-cumulative-histogram
  "Normalize a cumulative histogram data point to delta.
   
   Computes element-wise delta for bucket counts, scalar delta for count/sum.
   hist.min and hist.max pass through unchanged (they're per-interval values).
   
   Detects resets (any bucket count goes negative) and uses current values as delta.
   Histograms are inherently monotonic (bucket counts can only increase within an
   aggregation period), so reset detection always applies.
   
   Arguments:
     norm       - The normalizer component
     data-point - The cumulative histogram data point
   
   Returns a map with:
     :normalized - data point with delta values, or nil if first-seen
     :original   - original data point (always, for normalizer state tracking)"
  [norm data-point]
  (if-let [delta-result (normalizer/compute-delta norm data-point)]
    ;; Has previous state - convert to delta
    ;; Check for reset: any bucket count delta is negative indicates a counter reset
    (let [reset? (some neg? (:hist.counts delta-result))]
      (if reset?
        ;; Reset detected - use current values as delta
        (do
          (mulog/log ::histogram-reset-detected
                     :metric-name (:name data-point))
          {:normalized data-point
           :original data-point})
        ;; Normal case - apply computed deltas
        {:normalized (-> data-point
                         (assoc :hist.counts (:hist.counts delta-result))
                         (assoc :hist.count (:hist.count delta-result))
                         (cond-> (:hist.sum delta-result)
                           (assoc :hist.sum (:hist.sum delta-result))))
         :original data-point}))
    ;; First observation - drop but track for state initialization
    {:normalized nil
     :original data-point}))

(defn- -normalize-cumulative
  "Normalize a cumulative data point (sum or histogram) to delta.
   Dispatches to appropriate handler based on data point type."
  [norm data-point is-monotonic?]
  (if (-histogram-data-point? data-point)
    (-normalize-cumulative-histogram norm data-point)
    (-normalize-cumulative-sum norm data-point is-monotonic?)))

(defn normalize-temporality
  "Normalize temporality for all data points.
   
   Processing by temporality (from metrics-metadata):
   - :cumulative - Convert to delta using normalizer state
                   First-seen series are dropped but tracked for state commit
                   For monotonic sums, resets are detected and handled
   - :delta      - Pass through unchanged
   - nil/other   - Pass through unchanged (gauges, etc.)
   
   Arguments:
     normalizer       - The metric-temporality-normalizer component
     data-points      - Collection of data point maps
     metrics-metadata - Map of metric-name -> {:temporality :cumulative/:delta, :is_monotonic bool, ...}
   
   Returns:
     {:normalized           [...] - Data points to persist (delta values, gauges)
      :cumulative-to-commit [...] - All cumulative data points with original values
                                    (for normalizer state update after persist)}"
  [normalizer data-points metrics-metadata]
  (let [results (map (fn [dp]
                       (let [meta (get metrics-metadata (:name dp))
                             temporality (:temporality meta)]
                         (case temporality
                           :cumulative (let [is-monotonic? (:is_monotonic meta false)]
                                         (-normalize-cumulative normalizer dp is-monotonic?))
                           :delta {:normalized dp
                                   :original nil}
                           ;; Gauges and other types pass through unchanged
                           {:normalized dp
                            :original nil})))
                     data-points)]
    {:normalized (vec (keep :normalized results))
     :cumulative-to-commit (vec (keep :original results))}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: normalizing cumulative to delta
  ;; (requires a running normalizer component)
  ;; Note: temporality comes from metrics-metadata, not from data points

  ;; First observation (cumulative = 100)
  ;; Data point: {:name "m" :value 100}
  ;; Metadata: {"m" {:temporality :cumulative}}
  ;; normalize-temporality returns:
  ;;   {:normalized []
  ;;    :cumulative-to-commit [{:name "m" :value 100}]}
  ;; -> Nothing persisted, but commit-batch! will track value=100

  ;; Second observation (cumulative = 150)
  ;; normalize-temporality returns:
  ;;   {:normalized [{:name "m" :value 50}]  ; delta = 150 - 100
  ;;    :cumulative-to-commit [{:name "m" :value 150}]}
  ;; -> Persist delta=50, then commit-batch! updates state to value=150

  ;; Reset detection (monotonic sum, current < previous):
  ;; Previous state = 1000, current observation = 50 (service restarted)
  ;; Metadata: {"m" {:temporality :cumulative :is_monotonic true}}
  ;; normalize-temporality returns:
  ;;   {:normalized [{:name "m" :value 50}]  ; use current as delta, not -950
  ;;    :cumulative-to-commit [{:name "m" :value 50}]}
  ;; -> Logs ::monotonic-reset-detected, persists delta=50

  ;; Delta metrics pass through unchanged
  ;; Data point: {:name "m" :value 10}
  ;; Metadata: {"m" {:temporality :delta}}
  ;; -> in :normalized as {:name "m" :value 10}

  ;; Gauges have no temporality in metadata, pass through as-is
  ;; Data point: {:name "g" :value 42}
  ;; Metadata: {"g" {:metric_type :gauge}}  ; no :temporality key
  ;; -> in :normalized as {:name "g" :value 42}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
