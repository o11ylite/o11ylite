;; ---------------------------------------------------------
;; o11ylite.store.events.ingest
;;
;; Event ingestion: orchestration and storage for observability events
;; (spans, span-events, logs).
;;
;; Flow:
;;   gRPC/HTTP handler
;;       │
;;       v
;;   ingest-events! (hot path)
;;       1. Cleanse events (via cleanse.clj):
;;          - Skip fields with type conflicts (vs current schema)
;;          - Skip new fields if event exceeds 200 field limit
;;       2. Enrich events (via enrich.clj):
;;          - Compute derived fields (e.g., error boolean)
;;       3. Extract fields with inferred types
;;       4. Submit to batcher
;;       │
;;       v
;;   batcher accumulates {:events [...] :fields {name {:type t} ...}}
;;       │
;;       v (periodic flush)
;;   persist-batch! (cold path)
;;       1. Schema diff - compare batch fields against current DuckDB schema
;;       2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
;;       3. Insert via temp staging table + DuckDB Appender + INSERT FROM SELECT
;; ---------------------------------------------------------

(ns o11ylite.store.events.ingest
  (:require
    [o11ylite.components.blocked-fields :as blocked-fields]
    [o11ylite.store.batcher :as batcher]
    [o11ylite.store.events.cleanse :as cleanse]
    [o11ylite.store.events.enrich :as enrich]
    [o11ylite.store.ingest-util :as ingest-util]
    [o11ylite.store.schema :as schema]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.time Instant LocalDateTime ZoneOffset]))

;; ---------------------------------------------------------
;; Private Helpers - Field Extraction

(defn- -extract-fields
  "Extract all fields from a collection of events with inferred types.
   Returns a map of keyword -> {:type normalized-type}.

   When the same field appears with different types across events,
   the last occurrence wins (merge behavior).

   Note: Keys are normalized to keywords to prevent duplicate column errors
   (e.g., both :timestamp and \"timestamp\" would map to the same SQL column)."
  [events]
  (->> events
       (mapcat (fn [event]
                 (map (fn [[k v]]
                        [(keyword k) {:type (schema/infer-type v)}])
                      event)))
       (into {})))

(defn- -compute-schema-diff
  "Compare batch fields against the current events-table schema.
   Returns a map of new-field-name -> {:type ...} for fields not yet in
   the table. Returns nil if no new fields.

   The field types come from the batch's inferred types."
  [duckdb fields]
  (let [known-fields (schema/fetch-event-fields duckdb)
        new-fields (reduce-kv (fn [acc field-name field-meta]
                                (if (contains? known-fields field-name)
                                  acc
                                  (assoc acc field-name field-meta)))
                              {}
                              fields)]
    (when (seq new-fields)
      new-fields)))

(defn- -coerce-value
  "Coerce a value for JDBC insertion.
   - Keywords are converted to strings
   - Instants are converted to LocalDateTime at UTC (see note below)
   - Other values pass through unchanged

   Note on Instant conversion: Works around a timezone bug in DuckDB JDBC.
   See: https://github.com/duckdb/duckdb-java/issues/508"
  [v]
  (cond
    (keyword? v) (name v)
    (instance? Instant v) (LocalDateTime/ofInstant v ZoneOffset/UTC)
    :else v))

(defn- -event->row
  "Convert event to a row vector ordered by columns.
   Missing keys become nil, values are coerced for DuckDB compatibility."
  [event columns]
  (mapv (fn [k] (-coerce-value (get event k))) columns))

(defn- -events->rows
  "Convert events to row vectors ordered by columns.
   Returns a vector of value vectors, one per event."
  [events columns]
  (into [] (map #(-event->row % columns)) events))

;; ---------------------------------------------------------
;; Public API

(defn ingest-events!
  "Ingest events into the observability store.

   Called by gRPC/HTTP handlers to submit events. Cleanses events (skipping
   blocked fields, type conflicts, or exceeding field limit), enriches with
   derived fields (including Snowflake ID), extracts fields with inferred types,
   then submits events + fields to batcher. Blocks until the batch is flushed.

   Arguments:
     duckdb          - DuckDB datasource used to look up the current
                       events-table schema (for cleansing).
     blocked-fields  - The blocked-fields cache component (atom deref, no I/O)
     event-batcher   - The event batcher component
     id-generator    - The ID generator component (for Snowflake IDs)
     events          - Collection of event maps to ingest

   Returns:
     {:success true/false
      :rejected-count N       ;; always 0 (we skip fields, not reject events)
      :error-message \"...\" or nil}"
  [duckdb blocked-fields event-batcher id-generator events]
  (let [blocked-set (blocked-fields/get-blocked-event-fields blocked-fields)
        {:keys [events skipped-field-count skipped-reason-counts skipped-field-counts]}
        (cleanse/cleanse-events duckdb blocked-set events)
        enriched (enrich/enrich-events id-generator events)
        fields (-extract-fields enriched)
        success (batcher/->batcher! event-batcher {:events enriched
                                                   :fields fields})]
    (when (pos? skipped-field-count)
      (span/add-span-data!
        {:attributes {:o11ylite.ingest.skipped_field_count skipped-field-count
                      :o11ylite.ingest.skipped_reason_counts (pr-str skipped-reason-counts)
                      :o11ylite.ingest.skipped_field_counts  (pr-str skipped-field-counts)}}))
    {:success success
     :rejected-count 0
     :error-message nil}))

(defn persist-batch!
  "Persist a batch of events to DuckLake via staged insert.

   Called by the ingest batcher during flush. Handles:
     1. Schema diff - compare batch fields against current events-table schema
     2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
     3. Staged insert - temp table + DuckDB Appender + INSERT FROM SELECT

   Blocked-field filtering is NOT done here. The hot path (cleanse-events)
   strips blocked fields before they reach the batcher, so they never appear
   in the fields map. Filtering here would be redundant and could cause INSERT
   failures in race conditions (field blocked between cleanse and flush).

   Arguments:
     duckdb        - DuckDB datasource (must be the events single-writer pool;
                     ALTER and INSERT run through it). Also used for the
                     pre-insert schema read.
     events        - Collection of event maps to insert
     fields        - Map of field-name -> {:type ...} for all fields in batch

   Returns:
     true on success

   Throws:
     Exception on failure (batcher will catch and notify callers).
     OTLP clients are expected to retry on transient failures."
  [duckdb events fields]
  (span/with-span!
    [::persist-batch {:o11ylite.ingest.event_count (count events)}]
    (let [new-fields (-compute-schema-diff duckdb fields)]
      (span/add-span-data! {:attributes {:o11ylite.ingest.new_field_count (count new-fields)}})

      ;; Step 1: Schema evolution (if needed)
      (when new-fields
        (schema/add-event-fields! duckdb new-fields))

      ;; Step 2: Build columns and rows
      (let [columns (vec (keys fields))
            rows (-events->rows events columns)]

        ;; Step 3: Insert via staged Appender bulk load
        (ingest-util/insert-staged! duckdb
                                    {:target-table "o11ylite.events"
                                     :columns columns
                                     :column-type-fn (fn [col]
                                                       (schema/app-type->duckdb (get-in fields [col :type] :string)))
                                     :rows rows})
        true))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example event structure (after enrichment)
  ;; All keys are keywords (core fields and attributes)
  {:service "my-service"
   :timestamp #inst "2024-01-01T00:00:00Z"
   :trace_id "abc123"
   :span_id "def456"
   :name "HTTP GET /api/users"
   :error false  ; derived: true when span.status_code=:error, log.severity in #{:error :fatal}, etc.
   :meta.signal_type :span
   :span.kind :server
   :span.status_code :ok
   :span.duration_ms 1.234567
   :meta.observed_time #inst "2024-01-01T00:00:01Z"
   ;; Dynamic attributes (also keywords)
   :attr.http.method "GET"
   :attr.http.status_code 200}

  ;; Test field extraction (uses schema/infer-type internally)
  (-extract-fields [{:service "test" :count 42 :active true}])
  ;; => {:service {:type :string}
  ;;     :count {:type :integer}
  ;;     :active {:type :boolean}}

  ;; Test event->row conversion (coerce + order by columns)
  (-event->row {:service "svc1" :meta.signal_type :span}
               [:service :meta.signal_type :missing_field])
  ;; => ["svc1" "span" nil]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
