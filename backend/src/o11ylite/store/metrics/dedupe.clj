;; ---------------------------------------------------------
;; o11ylite.store.metrics.dedupe
;;
;; Deduplication logic for sum and histogram metric data points.
;;
;; Sum and histogram metrics are deduplicated:
;; - Cumulative sums/histograms need a single value per series for delta calculation
;; - Delta sums/histograms with duplicates are likely client bugs
;;
;; Gauges are NOT deduplicated because each value represents a valid
;; point-in-time measurement - losing intermediate values loses information.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.dedupe
  (:require
    [o11ylite.store.metrics.series :as series]))

;; ---------------------------------------------------------
;; Deduplication

(defn- -dedupe-metrics
  "Deduplicate metrics by series key, keeping the one with latest timestamp.
   
   Arguments:
     data-points - Collection of metric data points
   
   Returns:
     Vector of deduplicated data points (one per series)"
  [data-points]
  (->> data-points
       (group-by series/series-key)
       (vals)
       (map (fn [dps]
              ;; Sort by timestamp descending, take first (latest)
              ;; For equal timestamps, last in original order wins
              (->> dps
                   (sort-by :timestamp #(compare %2 %1))
                   first)))
       vec))

(defn dedupe-by-series
  "Deduplicate data points by series, but only for sums and histograms.
   
   Processing:
   - Sum/histogram metrics: deduplicate, keep latest timestamp per series
   - Gauges: pass through unchanged, all values preserved
   
   Rationale:
   - Sums/histograms need deduplication for correct cumulative→delta conversion
   - Gauges represent point-in-time values; losing intermediate states loses information
   
   Arguments:
     data-points       - Collection of data point maps
     metrics-metadata  - Map of metric-name -> {:metric_type :gauge/:sum/:histogram, ...}
   
   Returns:
     Vector of data points (sums/histograms deduplicated, gauges unchanged)"
  [data-points metrics-metadata]
  (let [gauge? (fn [dp] (= :gauge (get-in metrics-metadata [(:name dp) :metric_type])))
        {gauges true, others false} (group-by gauge? data-points)
        deduped-others (when (seq others) (-dedupe-metrics others))]
    (into (vec gauges) deduped-others)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: gauges are NOT deduplicated
  (def gauge-dps [{:name "balance" :timestamp 1 :value 100}
                  {:name "balance" :timestamp 2 :value 150}
                  {:name "balance" :timestamp 3 :value 120}])

  (def gauge-meta {"balance" {:metric_type :gauge}})

  (dedupe-by-series gauge-dps gauge-meta)
  ;; => [{:name "balance" :timestamp 1 :value 100}
  ;;     {:name "balance" :timestamp 2 :value 150}
  ;;     {:name "balance" :timestamp 3 :value 120}]
  ;; All three values preserved!

  ;; Example: sums ARE deduplicated
  (def sum-dps [{:name "requests" :timestamp 1 :value 100}
                {:name "requests" :timestamp 2 :value 150}])

  (def sum-meta {"requests" {:metric_type :sum :temporality :cumulative}})

  (dedupe-by-series sum-dps sum-meta)
  ;; => [{:name "requests" :timestamp 2 :value 150}]
  ;; Only latest kept for delta calculation

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
