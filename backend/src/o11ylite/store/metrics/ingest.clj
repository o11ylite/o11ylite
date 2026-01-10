;; ---------------------------------------------------------
;; o11ylite.store.metrics.ingest
;;
;; Metrics ingestion: validation and storage for time-series metrics.
;;
;; Flow:
;;   gRPC handler
;;       │
;;       v
;;   ingest-metrics! (hot path)
;;       1. Deduplicate data points by series key (keep latest timestamp)
;;       2. Normalize temporality (cumulative → delta for sums)
;;       3. Extract field names from data-points
;;       4. Filter unchanged metadata (TTL-cached lookup, ~IO-free)
;;       5. Submit to batcher
;;       │
;;       v
;;   batcher accumulates {:data-points [...] :fields #{...} :metadata {...}}
;;       │
;;       v (periodic flush)
;;   persist-batch! (cold path)
;;       1. DESCRIBE TABLE to get current columns
;;       2. ALTER TABLE ADD COLUMN for new attr.* fields
;;       3. UPSERT metadata to SQLite (only changed entries)
;;       4. Bulk INSERT data points
;;       5. Update normalizer state (commit-batch!)
;;
;; Key differences from events:
;;   - fields: Set of field names (all attr.* are strings, no type tracking)
;;   - metadata: Map of metric-name -> {:description :unit :metric_type :attributes}
;;   - TTL-cached metadata lookups to filter unchanged metadata in hot path
;;   - Deduplication ensures one data point per series per batch
;;   - Cumulative sums are converted to delta via normalizer
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.ingest
  (:require
   [clojure.set :as set]
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc.sql :as sql]
   [next.jdbc.quoted]
   [o11ylite.components.metric-temporality-normalizer :as normalizer]
   [o11ylite.store.batcher :as batcher]
   [o11ylite.store.metrics.dedupe :as dedupe]
   [o11ylite.store.metrics.metadata :as metadata]
   [o11ylite.store.metrics.temporality :as temporality]
   [o11ylite.store.schema :as schema])
  (:import
   [java.time Instant LocalDateTime ZoneOffset]))

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

;; ---------------------------------------------------------
;; Private Helpers - Metadata

(defn- -metadata-changed?
  "Check if incoming metadata differs from existing cached metadata.
   Returns true if this is a new metric or if description/attributes changed.
   Unit and metric_type are immutable so we don't check them."
  [sqlite metric-name {:keys [description attributes]}]
  (if-let [existing (metadata/get-metric sqlite metric-name)]
    (or (not= description (:description existing))
        (not= attributes (:attributes existing)))
    true))

(defn- -filter-changed-metadata
  "Filter metrics-metadata to only include new or changed entries.
   Uses TTL-cached get-metric to avoid IO on repeated calls."
  [sqlite metrics-metadata]
  (->> metrics-metadata
       (filter (fn [[metric-name meta-entry]]
                 (-metadata-changed? sqlite metric-name meta-entry)))
       (into {})))

;; ---------------------------------------------------------
;; Private Helpers - Value Coercion

(defn- -coerce-value
  "Coerce a value for JDBC insertion.
   - Keywords are converted to strings
   - Instants are converted to LocalDateTime at UTC
   - Other values pass through unchanged"
  [v]
  (cond
    (keyword? v) (name v)
    (instance? Instant v) (LocalDateTime/ofInstant v ZoneOffset/UTC)
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
   (deduplication, temporality normalization, field extraction, filtering)
   then submits to batcher. Blocks until the batch is flushed by persist-batch!.

   Design rationale - CPU vs IO split:
     The batcher exists to batch IO operations (one flush per second).
     CPU work (validation, filtering) happens here in the hot path because:
     - Multiple virtual threads can do CPU work concurrently while waiting
     - Keeps persist-batch! focused on pure IO operations
     - Validation errors can be returned to client (they're waiting on flush)
     - TTL-cached lookups are effectively IO-free after first call per second

   Arguments:
     metric-batcher   - The metric batcher component
     sqlite           - SQLite datasource (for cached metadata lookups)
     norm             - Temporality normalizer component (for cumulative→delta)
     data-points      - Collection of data point maps (with attr.* keys)
     metrics-metadata - Map of metric-name -> {:description :unit :metric_type :attributes}

   Returns:
     true if all data was persisted successfully
     false if flush failed (caller should handle retry/logging)"
  [metric-batcher sqlite norm data-points metrics-metadata]
  (let [;; Step 1: Deduplicate by series (sums only, gauges pass through)
        deduped (dedupe/dedupe-by-series data-points)
        ;; Step 2: Normalize temporality (cumulative → delta, with reset detection)
        ;; Returns {:normalized [...] :cumulative-to-commit [...]}
        {:keys [normalized cumulative-to-commit]} (temporality/normalize-temporality norm deduped metrics-metadata)
        ;; Step 3: Extract fields and filter metadata
        fields (-extract-fields normalized)
        changed-metadata (-filter-changed-metadata sqlite metrics-metadata)]
    ;; Send to batcher if we have data to persist or cumulative state to track
    (if (or (seq normalized) (seq cumulative-to-commit))
      (batcher/->batcher! metric-batcher {:data-points normalized
                                          :fields fields
                                          :metadata changed-metadata
                                          ;; Cumulative data points for normalizer state update
                                          :cumulative-to-commit cumulative-to-commit})
      true)))

(defn persist-batch!
  "Persist a batch of metrics to storage (cold path).

   Called by the metric batcher during flush. Performs IO-bound work:
     1. Schema diff - DESCRIBE TABLE to find new attr.* columns needed
     2. Schema evolution - ALTER TABLE ADD COLUMN for new attr.* fields
     3. Metadata upsert - UPSERT metric metadata to SQLite
     4. Bulk INSERT - INSERT all data points into DuckDB metrics table
     5. Update normalizer state - commit cumulative values for next delta calc

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
  ;; Step 1: Schema evolution - add new attr.* columns if needed
  (let [existing-columns (schema/fetch-metrics-field-names duckdb)
        batch-attr-fields (-attr-fields fields)
        new-attr-fields (set/difference batch-attr-fields existing-columns)]
    (when (seq new-attr-fields)
      (mulog/log ::schema-evolution :new-fields (vec new-attr-fields))
      (schema/add-metrics-fields! duckdb new-attr-fields)))

  ;; Step 2: UPSERT metadata to SQLite
  (when (seq metrics-metadata)
    (metadata/upsert-metrics! sqlite metrics-metadata))

  ;; Step 3: Bulk INSERT data points
  (when (seq data-points)
    (let [columns (vec fields)
          rows (-data-points->rows data-points columns)]
      (sql/insert-multi! duckdb :o11ylite.metrics columns rows
                         {:column-fn next.jdbc.quoted/ansi})))

  ;; Step 4: Update normalizer state AFTER successful persistence
  ;; Commit all cumulative data points (original values) for next delta calculation
  (when (seq cumulative-to-commit)
    (normalizer/commit-batch! norm cumulative-to-commit))

  (mulog/log ::persist-batch
             :data-point-count (count data-points)
             :field-count (count fields)
             :metadata-count (count metrics-metadata)
             :cumulative-committed-count (count cumulative-to-commit))
  true)

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
    :attributes #{"host.name" "cpu.core"}}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
