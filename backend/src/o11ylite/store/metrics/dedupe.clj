;; ---------------------------------------------------------
;; o11ylite.store.metrics.dedupe
;;
;; Deduplication logic for sum metric data points.
;;
;; Only sum metrics (those with :temporality key) are deduplicated:
;; - Cumulative sums need a single value per series for delta calculation
;; - Delta sums with duplicates are likely client bugs
;;
;; Gauges are NOT deduplicated because each value represents a valid
;; point-in-time measurement - losing intermediate values loses information.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.dedupe
  (:require
   [o11ylite.store.metrics.series :as series]))

;; ---------------------------------------------------------
;; Deduplication

(defn- -dedupe-sum-metrics
  "Deduplicate sum metrics by series key, keeping the one with latest timestamp.
   Only processes data points that have :temporality (sum metrics).
   
   Arguments:
     data-points - Collection of sum metric data points (with :temporality)
   
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
  "Deduplicate data points by series, but only for sum metrics.
   
   Processing:
   - Sum metrics (have :temporality): deduplicate, keep latest timestamp per series
   - Gauges (no :temporality): pass through unchanged, all values preserved
   
   Rationale:
   - Sums need deduplication for correct cumulative→delta conversion
   - Gauges represent point-in-time values; losing intermediate states loses information
   
   Arguments:
     data-points - Collection of data point maps
   
   Returns:
     Vector of data points (sums deduplicated, gauges unchanged)"
  [data-points]
  (let [{sums true, gauges false} (group-by #(contains? % :temporality) data-points)
        deduped-sums (when (seq sums) (-dedupe-sum-metrics sums))]
    (into (vec gauges) deduped-sums)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: gauges are NOT deduplicated
  (def gauge-dps [{:name "balance" :timestamp 1 :value 100}
                  {:name "balance" :timestamp 2 :value 150}
                  {:name "balance" :timestamp 3 :value 120}])

  (dedupe-by-series gauge-dps)
  ;; => [{:name "balance" :timestamp 1 :value 100}
  ;;     {:name "balance" :timestamp 2 :value 150}
  ;;     {:name "balance" :timestamp 3 :value 120}]
  ;; All three values preserved!

  ;; Example: sums ARE deduplicated
  (def sum-dps [{:name "requests" :timestamp 1 :value 100 :temporality :cumulative}
                {:name "requests" :timestamp 2 :value 150 :temporality :cumulative}])

  (dedupe-by-series sum-dps)
  ;; => [{:name "requests" :timestamp 2 :value 150 :temporality :cumulative}]
  ;; Only latest kept for delta calculation

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
