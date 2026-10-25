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
;; Also provides an advisory per-metric check: whether the query's
;; filters can match at all, given each metric's known attributes.
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

;; ---------------------------------------------------------
;; Per-Metric Filter Diagnostics
;;
;; `filter` is shared by every metric in the query, but each metric
;; records its own attribute set (the one `describe_metric` returns).
;; A filter on an attribute a metric never records cannot match that
;; metric's rows. This is not an invalid query: attributes are sparse and
;; can appear later. It is only worth reporting when the metric returned
;; no series at all.

(defn- -attr-field?
  "True for attr.* fields. Built-in fields such as service are not part
   of a metric's attribute set and are always applicable."
  [field]
  (and (string? field) (str/starts-with? field "attr.")))

(defn- -bare-attr
  "Drop the attr. prefix so metadata stored with or without it matches."
  [field]
  (if (str/starts-with? field "attr.")
    (subs field 5)
    field))

(defn- -known-attribute?
  [attributes field]
  (or (contains? attributes field)
      (contains? attributes (-bare-attr field))))

(defn- -impossible-filter
  "Return {:fields #{...}} when `filter-expr` can never match a metric
   with `attributes`, else nil.

   AND is impossible when any branch is impossible. OR is impossible only
   when every branch is impossible; one usable branch keeps the metric
   queryable."
  [attributes filter-expr]
  (cond
    (nil? filter-expr) nil

    (:and filter-expr)
    (let [issues (keep #(-impossible-filter attributes %) (:and filter-expr))]
      (when (seq issues)
        {:fields (into #{} (mapcat :fields issues))}))

    (:or filter-expr)
    (let [issues (mapv #(-impossible-filter attributes %) (:or filter-expr))]
      (when (and (seq issues) (every? some? issues))
        {:fields (into #{} (mapcat :fields issues))}))

    :else
    (let [field (:field filter-expr)]
      (when (and (-attr-field? field)
                 (not (-known-attribute? attributes field)))
        {:fields #{field}}))))

(defn- -filter-issue
  "Combine the global filter and a metric's own filter. Either can make
   the metric unmatchable; both are merged when both do."
  [attributes global-filter metric-filter]
  (let [global-issue (-impossible-filter attributes global-filter)
        metric-issue (-impossible-filter attributes metric-filter)]
    (cond
      (and global-issue metric-issue)
      {:fields (into (:fields global-issue) (:fields metric-issue))}

      global-issue global-issue
      metric-issue metric-issue)))

(defn- -diagnostic-message
  [metric-name id fields]
  (let [multiple? (> (count fields) 1)]
    (format (str "Metric '%s' (id: %s) returned no series: filter %s %s %s not present in "
                 "this metric's recorded attributes. The query is still valid; call "
                 "describe_metric to check the metric's attributes.")
            metric-name
            id
            (if multiple? "fields" "field")
            (str/join ", " (map #(str "'" % "'") fields))
            (if multiple? "are" "is"))))

(defn filter-diagnostics
  "Return non-fatal diagnostics for requested metrics that returned no
   series and whose filter references attributes the metric does not have.

   `series` is the executed result, used to avoid warning when data came
   back (for example because metadata was stale).

   Unknown metrics have no metadata to check and produce no diagnostic."
  [sqlite {:keys [filter metrics]} series]
  (let [ids-with-series (into #{} (map :id) series)]
    (->> metrics
         (keep (fn [{:keys [id name] :as metric}]
                 (when-not (contains? ids-with-series id)
                   (let [attributes (:attributes (metadata/get-metric sqlite name))
                         issue (when (some? attributes)
                                 (-filter-issue attributes filter (:filter metric)))]
                     (when issue
                       (let [fields (vec (sort (:fields issue)))]
                         {:severity "warning"
                          :code "metric_filter_not_applicable"
                          :metric name
                          :id id
                          :fields fields
                          :message (-diagnostic-message name id fields)}))))))
         vec)))

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
