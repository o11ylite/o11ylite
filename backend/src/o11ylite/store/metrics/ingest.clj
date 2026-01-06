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
;;       1. Extract field names from data-points
;;       2. Filter unchanged metadata (TTL-cached lookup, ~IO-free)
;;       3. Submit to batcher
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
;;
;; Key differences from events:
;;   - fields: Set of field names (all attr.* are strings, no type tracking)
;;   - metadata: Map of metric-name -> {:description :unit :metric_type :attributes}
;;   - TTL-cached metadata lookups to filter unchanged metadata in hot path
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.ingest
  (:require
   [clojure.set :as set]
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc.sql :as sql]
   [next.jdbc.quoted]
   [o11ylite.store.batcher :as batcher]
   [o11ylite.store.metrics.metadata :as metadata]
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
   (field extraction, validation, filtering) then submits to batcher.
   Blocks until the batch is flushed by persist-batch!.

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
     data-points      - Collection of data point maps (with attr.* keys)
     metrics-metadata - Map of metric-name -> {:description :unit :metric_type :attributes}

   Returns:
     true if all data was persisted successfully
     false if flush failed (caller should handle retry/logging)"
  [metric-batcher sqlite data-points metrics-metadata]
  (let [fields (-extract-fields data-points)
        changed-metadata (-filter-changed-metadata sqlite metrics-metadata)]
    (batcher/->batcher! metric-batcher {:data-points data-points
                                        :fields fields
                                        :metadata changed-metadata})))

(defn persist-batch!
  "Persist a batch of metrics to storage (cold path).

   Called by the metric batcher during flush. Performs IO-bound work only:
     1. Schema diff - DESCRIBE TABLE to find new attr.* columns needed
     2. Schema evolution - ALTER TABLE ADD COLUMN for new attr.* fields
     3. Metadata upsert - UPSERT metric metadata to SQLite
     4. Bulk INSERT - INSERT all data points into DuckDB metrics table

   Design rationale - CPU vs IO split:
     This function should contain only IO operations. CPU-bound work
     (validation, filtering, transformation) belongs in ingest-metrics!
     so that IO can be batched efficiently (once per flush interval).

   Arguments:
     duckdb           - DuckDB datasource
     sqlite           - SQLite datasource
     data-points      - Collection of data point maps
     fields           - Set of field names in this batch
     metrics-metadata - Map of metric-name -> {:description :unit :metric_type :attributes}

   Returns:
     true on success

   Throws:
     Exception on failure (batcher will catch and notify callers)."
  [duckdb sqlite data-points fields metrics-metadata]
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

  (mulog/log ::persist-batch
             :data-point-count (count data-points)
             :field-count (count fields)
             :metadata-count (count metrics-metadata))
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
