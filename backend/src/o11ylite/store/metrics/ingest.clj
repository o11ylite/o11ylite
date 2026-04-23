;; ---------------------------------------------------------
;; o11ylite.store.metrics.ingest
;;
;; Metrics ingestion: validation and storage for time-series metrics.
;;
;; Flow:
;;   gRPC/HTTP handler
;;       │
;;       v
;;   ingest-metrics! (hot path)
;;       1. Deduplicate data points by series key (keep latest timestamp)
;;       2. Partition metadata: valid vs immutable-field-conflicts
;;       3. Reject data points for metrics with immutable field conflicts
;;       4. Normalize temporality (cumulative → delta for sums/histograms)
;;       5. Extract field names and submit to batcher
;;       │
;;       v
;;   batcher accumulates {:data-points [...] :fields #{...} :metadata {...}}
;;       │
;;       v (periodic flush)
;;   persist-batch! (cold path)
;;       1. Schema evolution - ALTER TABLE ADD COLUMN for new attr.* fields
;;       2. UPSERT metadata to SQLite (only changed entries)
;;       3. Insert via staged Appender bulk load into DuckDB
;;       4. Update normalizer state (commit-batch!)
;;
;; Immutable metadata fields (rejected if changed):
;;   - unit, metric_type, temporality, hist_boundaries
;;
;; Mutable metadata fields (updated on change):
;;   - description, attributes
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.ingest
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.components.blocked-fields :as blocked-fields]
    [o11ylite.components.metric-temporality-normalizer :as normalizer]
    [o11ylite.store.batcher :as batcher]
    [o11ylite.store.metrics.dedupe :as dedupe]
    [o11ylite.store.ingest-util :as ingest-util]
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.store.metrics.temporality :as temporality]
    [o11ylite.store.schema :as schema]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.time Instant LocalDateTime ZoneOffset]))

;; ---------------------------------------------------------
;; Private Helpers - Column Types

(def ^:private core-column-types
  "DuckDB types for core metric columns. Dynamic attr.* columns are all VARCHAR."
  {:name "VARCHAR"
   :service "VARCHAR"
   :timestamp "TIMESTAMP"
   :value "DOUBLE"
   :hist.counts "BIGINT[]"
   :hist.count "BIGINT"
   :hist.sum "DOUBLE"
   :hist.min "DOUBLE"
   :hist.max "DOUBLE"
   :scope.name "VARCHAR"
   :scope.version "VARCHAR"
   :meta.observed_time "TIMESTAMP"})

;; ---------------------------------------------------------
;; Private Helpers - Field Extraction

(defn- -extract-fields
  "Extract all field names from data points.
   Returns a set of keywords."
  [data-points]
  (->> data-points
       (mapcat keys)
       (map keyword)
       set))

(defn- -attr-fields
  "Filter to only attr.* fields."
  [fields]
  (into #{} (filter #(.startsWith (name %) "attr.")) fields))

(defn- -strip-blocked-attrs
  "Remove blocked attr.* keys from each data point.
   blocked-kws is a pre-computed set of keyword keys (e.g. #{:attr.bad.field})."
  [data-points blocked-kws]
  (if (empty? blocked-kws)
    data-points
    (mapv (fn [dp] (apply dissoc dp blocked-kws)) data-points)))

;; ---------------------------------------------------------
;; Private Helpers - Metadata

(defn- -immutable-fields-conflict?
  "Check if incoming metadata has immutable field conflicts with existing metric.
   Immutable fields: unit, metric_type, temporality, hist_boundaries.
   Returns error message string if conflict, nil if OK (new metric or no conflict)."
  [existing incoming]
  (when existing
    (let [conflicts (cond-> []
                      (and (:unit existing) (:unit incoming)
                           (not= (:unit existing) (:unit incoming)))
                      (conj (format "unit: %s → %s" (:unit existing) (:unit incoming)))

                      (and (:metric_type existing) (:metric_type incoming)
                           (not= (:metric_type existing) (:metric_type incoming)))
                      (conj (format "metric_type: %s → %s" (name (:metric_type existing)) (name (:metric_type incoming))))

                      (and (:temporality existing) (:temporality incoming)
                           (not= (:temporality existing) (:temporality incoming)))
                      (conj (format "temporality: %s → %s" (name (:temporality existing)) (name (:temporality incoming))))

                      (and (:hist_boundaries existing) (:hist_boundaries incoming)
                           (not= (:hist_boundaries existing) (:hist_boundaries incoming)))
                      (conj "hist_boundaries changed"))]
      (when (seq conflicts)
        (str/join ", " conflicts)))))

(defn- -categorize-metadata
  "Categorize metrics-metadata into changed (new or mutable updates) and invalid (immutable field conflicts).
   Returns {:changed-metadata {...} :invalid-metrics #{...} :errors [...]}."
  [sqlite metrics-metadata]
  (reduce-kv
    (fn [acc metric-name meta-entry]
      (let [existing (metadata/get-metric sqlite metric-name)]
        (if-let [conflict (-immutable-fields-conflict? existing meta-entry)]
          ;; Immutable field conflict - mark metric as invalid
          (-> acc
              (update :invalid-metrics conj metric-name)
              (update :errors conj (format "'%s': %s" metric-name conflict)))
          ;; Check if metadata actually changed (for new metrics or mutable field changes)
          (if (or (nil? existing)
                  (not= (:description meta-entry) (:description existing))
                  (not= (:attributes meta-entry) (:attributes existing)))
            (update acc :changed-metadata assoc metric-name meta-entry)
            acc))))
    {:changed-metadata {} :invalid-metrics #{} :errors []}
    metrics-metadata))

(defn- -reject-invalid-data-points
  "Remove data points belonging to metrics with immutable field conflicts.
   Returns {:valid [...] :rejected-count N :error-message \"...\"}."
  [data-points invalid-metrics errors]
  (if (empty? invalid-metrics)
    {:valid data-points :rejected-count 0 :error-message nil}
    (let [{valid true rejected false} (group-by #(not (contains? invalid-metrics (:name %))) data-points)
          rejected-count (count rejected)]
      (mulog/log ::immutable-field-conflict
                 :rejected-count rejected-count
                 :metric-names (vec invalid-metrics)
                 :errors errors)
      {:valid (vec (or valid []))
       :rejected-count rejected-count
       :error-message (format "Rejected %d data points due to immutable field conflicts: %s"
                              rejected-count (str/join "; " errors))})))

;; ---------------------------------------------------------
;; Private Helpers - Value Coercion

(defn- -coerce-value
  "Coerce a value for JDBC insertion.
   - Keywords are converted to strings
   - Instants are converted to LocalDateTime at UTC
   - Vectors (for hist.counts) are converted to native long[] arrays
   - Other values pass through unchanged"
  [v]
  (cond
    (keyword? v) (name v)
    (instance? Instant v) (LocalDateTime/ofInstant v ZoneOffset/UTC)
    (vector? v) (long-array v)
    :else v))

(defn- -data-point->row
  "Convert data point to a row vector for insert-multi!.
   Values are ordered by columns, missing keys become nil."
  [dp columns]
  (mapv (fn [k] (-coerce-value (get dp k))) columns))

(defn- -data-points->rows
  "Convert data points to row vectors for insert-multi!."
  [data-points columns]
  (into [] (map #(-data-point->row % columns)) data-points))

;; ---------------------------------------------------------
;; Public API

(defn ingest-metrics!
  "Ingest metrics into the observability store (hot path).

   Called by gRPC handlers to submit metrics. Performs CPU-bound work
   (deduplication, metadata validation, blocked-field stripping, temporality
   normalization, field extraction) then submits to batcher. Blocks until
   the batch is flushed by persist-batch!.

   Design rationale - CPU vs IO split:
     The batcher exists to batch IO operations (one flush per second).
     CPU work (validation, filtering) happens here in the hot path because:
     - Multiple virtual threads can do CPU work concurrently while waiting
     - Keeps persist-batch! focused on pure IO operations
     - Validation errors can be returned to client (they're waiting on flush)
     - TTL-cached lookups are effectively IO-free after first call per second

   Arguments:
     metric-batcher   - The metric batcher component
     blocked-fields   - Blocked-fields cache component (atom deref, no I/O)
     sqlite           - SQLite datasource (for cached metadata lookups)
     norm             - Temporality normalizer component (for cumulative→delta)
     data-points      - Collection of data point maps (with attr.* keys)
     metrics-metadata - Map of metric-name -> {:description :unit :metric_type :attributes :hist_boundaries}

   Returns:
     {:success true/false
      :rejected-count N
      :error-message \"...\" or nil}"
  [metric-batcher blocked-fields sqlite norm data-points metrics-metadata]
  (let [;; Step 0: Read blocked metric fields as keywords (cached, no I/O, no conversion)
        blocked-set (blocked-fields/get-blocked-metric-fields-kw blocked-fields)
        ;; Step 1: Deduplicate by series (sums/histograms dedupe, gauges pass through)
        deduped (dedupe/dedupe-by-series data-points metrics-metadata)
        ;; Step 2: Categorize metadata into changed vs immutable-field-conflicts
        {:keys [changed-metadata invalid-metrics errors]} (-categorize-metadata sqlite metrics-metadata)
        ;; Step 3: Reject data points for metrics with immutable field conflicts
        {:keys [valid rejected-count error-message]} (-reject-invalid-data-points deduped invalid-metrics errors)
        ;; Step 4: Normalize temporality (cumulative → delta, with reset detection)
        {:keys [normalized cumulative-to-commit]} (temporality/normalize-temporality norm valid metrics-metadata)
        ;; Step 5: Strip blocked attr.* fields from data points.
        ;; Done before -extract-fields so blocked attrs are excluded from
        ;; the fields set too (no need to filter fields separately).
        normalized (-strip-blocked-attrs normalized blocked-set)
        ;; Step 6: Extract fields from (already stripped) data points
        fields (-extract-fields normalized)
        ;; Step 7: Send to batcher if we have data to persist or cumulative state to track
        success (if (or (seq normalized) (seq cumulative-to-commit))
                  (batcher/->batcher! metric-batcher {:data-points normalized
                                                      :fields fields
                                                      :metadata changed-metadata
                                                      :cumulative-to-commit cumulative-to-commit})
                  true)]
    {:success success
     :rejected-count rejected-count
     :error-message error-message}))

(defn persist-batch!
  "Persist a batch of metrics to storage (cold path).

   Called by the metric batcher during flush. Performs IO-bound work:
     1. Schema diff - DESCRIBE TABLE to find new attr.* columns needed
     2. Schema evolution - ALTER TABLE ADD COLUMN for new attr.* fields
     3. Metadata upsert - UPSERT metric metadata to SQLite
     4. Staged insert - temp table + DuckDB Appender + INSERT FROM SELECT
     5. Update normalizer state - commit cumulative values for next delta calc

   Blocked-field filtering is NOT done here. The hot path (ingest-metrics!)
   strips blocked attr.* keys from data points before they reach the batcher,
   so they never appear in the fields set.

   Arguments:
     duckdb               - DuckDB datasource
     sqlite               - SQLite datasource
     norm                 - Temporality normalizer component
     data-points          - Collection of data point maps to persist
     fields               - Set of field names in this batch
     metrics-metadata     - Map of metric-name -> {:description :unit :metric_type :attributes}
     cumulative-to-commit - Cumulative data points with original values (for normalizer)

   Returns:
     true on success

   Throws:
     Exception on failure (batcher will catch and notify callers)."
  [duckdb sqlite norm data-points fields metrics-metadata cumulative-to-commit]
  (span/with-span!
    [::persist-batch {:data-point-count (count data-points)
                      :field-count (count fields)
                      :metadata-count (count metrics-metadata)}]
    ;; Step 1: Schema evolution - add new attr.* columns if needed
    (let [existing-columns (schema/fetch-metrics-field-names duckdb)
          batch-attr-fields (-attr-fields fields)
          new-attr-fields (set/difference batch-attr-fields existing-columns)]
      (when (seq new-attr-fields)
        (schema/add-metrics-fields! duckdb new-attr-fields)))

    ;; Step 2: UPSERT metadata to SQLite
    (when (seq metrics-metadata)
      (metadata/upsert-metrics! sqlite metrics-metadata))

    ;; Step 3: Insert via staged Appender bulk load
    (when (seq data-points)
      (let [columns (vec fields)
            rows (-data-points->rows data-points columns)]
        (ingest-util/insert-staged! duckdb
                                    {:target-table "o11ylite.metrics"
                                     :columns columns
                                     :column-type-fn (fn [col] (get core-column-types col "VARCHAR"))
                                     :rows rows})))

    ;; Step 4: Update normalizer state AFTER successful persistence
    ;; Commit all cumulative data points (original values) for next delta calculation
    (when (seq cumulative-to-commit)
      (normalizer/commit-batch! norm cumulative-to-commit))

    true))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example data point (from metric-proto/parse-metrics-request)
  ;; Note: includes attr.* keys that become VARCHAR columns
  {:name "cpu.utilization"
   :service "my-service"
   :timestamp #inst "2024-01-15T10:30:00Z"
   :value 42.5
   :scope.name "system-metrics"
   :scope.version "1.0.0"
   :meta.observed_time #inst "2024-01-15T10:30:01Z"
   :attr.host.name "server-1"
   :attr.cpu.core "0"}

   ;; Example metadata (keyed by metric name)
  {"cpu.utilization"
   {:description "CPU utilization percentage"
    :unit "%"
    :metric_type :gauge
    :attributes #{"attr.host.name" "attr.cpu.core"}}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
