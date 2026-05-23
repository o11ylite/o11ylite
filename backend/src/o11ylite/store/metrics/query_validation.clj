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
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.store.schema :as schema]))

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

;; ---------------------------------------------------------
;; Field-Existence Validation
;;
;; Unknown attribute columns in `group_by` or `filter` cause a DuckDB
;; binder error at execution time (HTTP 500). We reject them here as
;; 400 with a clear "Field 'X' does not exist" message so the frontend
;; renders the same error UI it already uses for schema validation
;; failures.
;;
;; Metric *names* are intentionally NOT validated: by historical design
;; unknown metrics are skipped silently so dashboards/alerts can be
;; authored before the first data point lands.

(defn- -collect-filter-fields
  [filter-expr]
  (cond
    (nil? filter-expr) []
    (:and filter-expr) (mapcat -collect-filter-fields (:and filter-expr))
    (:or filter-expr) (mapcat -collect-filter-fields (:or filter-expr))
    :else [(:field filter-expr)]))

(defn- -collect-referenced-fields
  "Field names a metrics query expects to resolve to real columns:
   global filter, per-metric filters, and group_by."
  [{:keys [filter group_by metrics]}]
  (concat (-collect-filter-fields filter)
          (mapcat #(-collect-filter-fields (:filter %)) metrics)
          group_by))

(defn- -validate-fields-exist
  "Reject queries that reference attribute fields not present in the
   metrics table. Returns nil if all referenced fields exist, or
   {:error ...} naming the first unknown one."
  [duckdb query]
  (let [referenced (-collect-referenced-fields query)]
    (when (seq referenced)
      (let [known (schema/fetch-metrics-field-names duckdb)]
        (when-let [unknown (->> referenced
                                (remove #(contains? known (keyword %)))
                                first)]
          {:error (format "Field '%s' does not exist" unknown)})))))

(defn validate-with-metadata
  "Validate query against metric metadata and the metrics table schema.
   Returns nil if valid, {:error ...} if invalid.

   - Unknown metric *names* are skipped (allows querying before data exists).
   - Unknown *fields* (group_by, filter) are rejected — they'd otherwise
     produce a DuckDB binder error at execution time.
   - Aggregation compatibility is checked for known metrics."
  [sqlite duckdb {:keys [metrics] :as query}]
  (or (first (keep #(-validate-metric-aggregation sqlite %) metrics))
      (-validate-fields-exist duckdb query)))

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
