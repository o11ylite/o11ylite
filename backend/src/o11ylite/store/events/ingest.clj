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
;;          - Skip fields with type conflicts (vs cached metadata)
;;          - Skip new fields if event exceeds 200 field limit
;;       2. Extract fields with inferred types
;;       3. Submit to batcher
;;       │
;;       v
;;   batcher accumulates {:events [...] :fields {name {:type t} ...}}
;;       │
;;       v (periodic flush)
;;   persist-batch! (cold path)
;;       1. Schema diff - compare batch fields against event-metadata cache
;;       2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
;;       3. Bulk INSERT all events into the events table
;;       4. Refresh event-metadata cache if schema changed
;; ---------------------------------------------------------

(ns o11ylite.store.events.ingest
  (:require
   [com.brunobonacci.mulog :as mulog]
   [next.jdbc.sql :as sql]
   [next.jdbc.quoted]
   [o11ylite.components.event-metadata :as event-metadata]
   [o11ylite.store.batcher :as batcher]
   [o11ylite.store.events.cleanse :as cleanse]
   [o11ylite.store.schema :as schema])
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
  "Compare batch fields against current event-metadata cache.
   Returns a map of new-field-name -> {:type ...} for fields not in cache.
   Returns nil if no new fields.

   The field types come from the batch's inferred types."
  [event-metadata fields]
  (let [known-fields (event-metadata/get-fields event-metadata)
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
  "Convert event to a row vector for insert-multi!.
   Values are ordered by columns, missing keys become nil,
   values are coerced for JDBC compatibility."
  [event columns]
  (mapv (fn [k] (-coerce-value (get event k))) columns))

(defn- -events->rows
  "Convert events to row vectors for insert-multi!.
   Returns a vector of value vectors ordered by columns.
   
   Performance: `(into [] (map ...))` with transducer is efficient.
   If profiling shows this as a bottleneck, consider loop-recur with transient.
   However, DuckDB INSERT is likely the real bottleneck, not this transformation."
  [events columns]
  (into [] (map #(-event->row % columns)) events))

;; ---------------------------------------------------------
;; Public API

(defn ingest-events!
  "Ingest events into the observability store.

   Called by gRPC/HTTP handlers to submit events. Cleanses events (skipping
   fields with type conflicts or exceeding field limit), extracts fields with
   inferred types, then submits events + fields to batcher. Blocks until the
   batch is flushed to storage.

   Arguments:
     event-metadata  - The event metadata cache component (for cleansing)
     event-batcher   - The event batcher component
     events          - Collection of event maps to ingest

   Returns:
     {:success true/false
      :rejected-count N       ;; always 0 (we skip fields, not reject events)
      :error-message \"...\" or nil}"
  [event-metadata event-batcher events]
  (let [{:keys [events skipped-field-count]} (cleanse/cleanse-events event-metadata events)
        fields (-extract-fields events)
        success (batcher/->batcher! event-batcher {:events events
                                                   :fields fields})]
    (when (pos? skipped-field-count)
      (mulog/log ::cleanse-summary :skipped-field-count skipped-field-count))
    {:success success
     :rejected-count 0
     :error-message nil}))

(defn persist-batch!
  "Persist a batch of events to DuckLake.

   Called by the ingest batcher during flush. Handles:
     1. Schema diff - compare batch fields against event-metadata cache
     2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
     3. Bulk insert - INSERT all events into the events table
     4. Cache refresh - update event-metadata if schema changed

   Arguments:
     duckdb         - DuckDB datasource
     event-metadata - Event metadata cache (for diff and refresh)
     events         - Collection of event maps to insert
     fields         - Map of field-name -> {:type ...} for all fields in batch

   Returns:
     true on success

   Throws:
     Exception on failure (batcher will catch and notify callers).
     OTLP clients are expected to retry on transient failures."
  [duckdb event-metadata events fields]
  (let [new-fields (-compute-schema-diff event-metadata fields)]
    ;; Step 1: Schema evolution (if needed)
    (when new-fields
      ;; Log field names only (not the nested {:type ...} maps) to avoid
      ;; recursive schema evolution when dogfooding mulog -> otel -> o11ylite
      (mulog/log ::schema-evolution :new-field-names (vec (keys new-fields)))
      (schema/add-event-fields! duckdb new-fields)
      (event-metadata/refresh! event-metadata))

    ;; Step 2: Bulk INSERT using [columns rows] form for efficiency
    ;; (avoids next.jdbc decomposing hash maps)
    (let [columns (vec (keys fields))
          rows (-events->rows events columns)]
      (sql/insert-multi! duckdb :o11ylite.events columns rows
                         {:column-fn next.jdbc.quoted/ansi})
      (mulog/log ::persist-batch :event-count (count events)))

    true))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example event structure (from gRPC handler)
  ;; All keys are keywords (core fields and attributes)
  {:service "my-service"
   :timestamp #inst "2024-01-01T00:00:00Z"
   :trace_id "abc123"
   :span_id "def456"
   :name "HTTP GET /api/users"
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
