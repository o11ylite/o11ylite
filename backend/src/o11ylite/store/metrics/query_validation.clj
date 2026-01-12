;; ---------------------------------------------------------
;; o11ylite.store.metrics.query-validation
;;
;; Metadata-aware validation for metrics queries.
;; Validates aggregation compatibility against metric types.
;;
;; V1 Scope:
;;   - Aggregation vs metric type validation (hard error)
;;   - Unknown metrics are skipped (allows querying before data exists)
;;
;; Deferred:
;;   - Attribute existence warnings (group_by, filter fields)
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query-validation
  (:require
   [clojure.string :as str]
   [o11ylite.store.metrics.metadata :as metadata]))

;; ---------------------------------------------------------
;; Aggregation Rules

(def valid-aggregations
  "Valid aggregations per metric type.
   
   Gauge: point-in-time values - all standard aggregations make sense
   Sum (counter): delta values - only sum and rate are meaningful
   Histogram: distribution data - aggregations operate on hist.* columns"
  {:gauge     #{"sum" "avg" "min" "max" "last"}
   :sum       #{"sum" "rate"}
   :histogram #{"count" "sum" "avg" "min" "max"}})

;; ---------------------------------------------------------
;; Validation

(defn- -validate-metric-aggregation
  "Validate a single metric's aggregation against its type.
   Returns nil if valid (or metric unknown), {:error ...} if invalid."
  [sqlite {:keys [id name agg]}]
  (when-let [meta (metadata/get-metric sqlite name)]
    (let [metric-type (:metric_type meta)
          allowed (get valid-aggregations metric-type)]
      (when-not (contains? allowed agg)
        {:error (format "metric '%s' (id: %s): aggregation '%s' is not valid for %s metrics. Allowed: %s"
                        name id agg (clojure.core/name metric-type)
                        (str/join ", " (sort allowed)))}))))

(defn validate-with-metadata
  "Validate query against metric metadata.
   Returns nil if valid, {:error ...} if invalid.
   
   Unknown metrics are skipped (allows querying before data exists).
   Only validates aggregation compatibility for known metrics."
  [sqlite {:keys [metrics]}]
  (first (keep #(-validate-metric-aggregation sqlite %) metrics)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: Valid gauge query
  ;; (-validate-metric-aggregation sqlite {:id "A" :name "cpu.utilization" :agg "avg"})
  ;; => nil (assuming cpu.utilization is a gauge)

  ;; Example: Invalid sum/counter query
  ;; (-validate-metric-aggregation sqlite {:id "A" :name "http.requests" :agg "avg"})
  ;; => {:error "metric 'http.requests' (id: A): aggregation 'avg' is not valid for sum metrics. Allowed: rate, sum"}

  ;; Example: Unknown metric (skipped)
  ;; (-validate-metric-aggregation sqlite {:id "A" :name "unknown.metric" :agg "avg"})
  ;; => nil

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
