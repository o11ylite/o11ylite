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
;;       1. Schema diff - compare batch fields against event-metadata cache
;;       2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
;;       3. Insert via temp staging table + DuckDB Appender + INSERT FROM SELECT
;;       4. Refresh event-metadata cache if schema changed
;; ---------------------------------------------------------

(ns o11ylite.store.events.ingest
  (:require
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mulog]
    [next.jdbc :as jdbc]
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.store.batcher :as batcher]
    [o11ylite.store.events.cleanse :as cleanse]
    [o11ylite.store.events.enrich :as enrich]
    [o11ylite.store.schema :as schema]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.time Instant LocalDateTime ZoneOffset]
    [org.duckdb DuckDBAppender DuckDBConnection]))

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
;; Private Helpers - Staged Insert via Appender API
;;
;; Insert strategy: temp staging table + DuckDB Appender + INSERT FROM SELECT.
;;
;; 1. Create a temp table (_ingest_staging) with the batch's column schema
;; 2. Bulk-load rows via the DuckDB Appender API (bypasses SQL parsing entirely)
;; 3. INSERT INTO o11ylite.events SELECT FROM _ingest_staging (single DuckLake txn)
;;
;; This achieves ~100-200x throughput improvement over parameterized INSERT VALUES
;; because the Appender writes directly to DuckDB's columnar storage, and the
;; INSERT FROM SELECT pushes data through DuckLake in one bulk operation.

(defn- -create-staging-table!
  "Create (or replace) the temp staging table with columns matching this batch.
   The staging table is a plain in-memory DuckDB table (not DuckLake), so the
   Appender API can write to it directly."
  [conn columns fields]
  (let [col-defs (str/join ", "
                           (map (fn [col]
                                  (let [app-type (get-in fields [col :type] :string)
                                        db-type (schema/app-type->duckdb app-type)]
                                    (str "\"" (name col) "\" " db-type)))
                                columns))
        sql (str "CREATE OR REPLACE TEMP TABLE _ingest_staging (" col-defs ")")]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt sql))))

(defn- -append-value!
  "Append a single coerced value to the DuckDB Appender.
   Dispatches to typed append methods based on the value's Java type.
   Type hints are required because DuckDBAppender.append() has many overloads
   and Clojure's reflector cannot disambiguate without them.
   Nulls use appendNull() — the typed append(Object nil) doesn't work because
   DuckDB can't infer the target column type from a null Object reference."
  [^DuckDBAppender appender v]
  (if (nil? v)
    (.appendNull appender)
    (cond
      (instance? String v)        (.append appender ^String v)
      (instance? Boolean v)       (.append appender (boolean v))
      (instance? Long v)          (.append appender (long v))
      (instance? Double v)        (.append appender (double v))
      (instance? Float v)         (.append appender (double (float v)))
      (instance? LocalDateTime v) (.append appender ^LocalDateTime v)
      (number? v)                 (.append appender (double v))
      :else                       (.append appender ^String (str v)))))

(defn- -load-staging!
  "Bulk-load rows into the staging table via the DuckDB Appender API.
   The Appender bypasses SQL parsing entirely, writing directly to DuckDB's
   columnar storage. Much faster than INSERT VALUES for large batches."
  [^DuckDBConnection duck-conn rows ^long num-cols]
  (with-open [appender (.createAppender duck-conn "temp" "main" "_ingest_staging")]
    (doseq [row rows]
      (.beginRow appender)
      (dotimes [i num-cols]
        (-append-value! appender (nth row i)))
      (.endRow appender))))

(defn- -build-insert-from-staging-sql
  "Build the INSERT INTO ... SELECT FROM staging SQL with explicit column lists.
   Ensures column ordering matches between the DuckLake table and staging table."
  [columns]
  (let [col-list (str/join ", " (map #(str "\"" (name %) "\"") columns))]
    (str "INSERT INTO o11ylite.events (" col-list ") SELECT " col-list " FROM _ingest_staging")))

(defn- -insert-events-staged!
  "Insert events via temp staging table + Appender API + INSERT FROM SELECT.
   All operations use the same connection so the temp table is visible throughout.

   Steps:
     1. Create temp staging table with batch column schema
     2. Bulk-load rows via Appender API (no SQL parsing)
     3. INSERT INTO ducklake SELECT FROM staging (single DuckLake transaction)"
  [duckdb columns fields rows]
  (with-open [conn (jdbc/get-connection duckdb)]
    ;; Unwrap the HikariCP proxy to get the raw DuckDBConnection.
    ;; The Appender API (.createAppender) is DuckDB-specific and not
    ;; part of JDBC, so it's only available on the unwrapped connection.
    (let [duck-conn (.unwrap conn DuckDBConnection)
          num-cols (count columns)]
      ;; Step 1: Create staging table matching batch columns
      (-create-staging-table! conn columns fields)
      ;; Step 2: Load data into staging via Appender (bypasses SQL entirely)
      (-load-staging! duck-conn rows num-cols)
      ;; Step 3: Push from staging into DuckLake in one bulk operation
      (with-open [stmt (.createStatement conn)]
        (.execute stmt (-build-insert-from-staging-sql columns))))))

;; ---------------------------------------------------------
;; Public API

(defn ingest-events!
  "Ingest events into the observability store.

   Called by gRPC/HTTP handlers to submit events. Cleanses events (skipping
   fields with type conflicts or exceeding field limit), enriches with derived
   fields (including Snowflake ID), extracts fields with inferred types, then
   submits events + fields to batcher. Blocks until the batch is flushed to storage.

   Arguments:
     event-metadata  - The event metadata cache component (for cleansing)
     event-batcher   - The event batcher component
     id-generator    - The ID generator component (for Snowflake IDs)
     events          - Collection of event maps to ingest

   Returns:
     {:success true/false
      :rejected-count N       ;; always 0 (we skip fields, not reject events)
      :error-message \"...\" or nil}"
  [event-metadata event-batcher id-generator events]
  (let [{:keys [events skipped-field-count]} (cleanse/cleanse-events event-metadata events)
        events (enrich/enrich-events id-generator events)
        fields (-extract-fields events)
        success (batcher/->batcher! event-batcher {:events events
                                                   :fields fields})]
    (when (pos? skipped-field-count)
      (mulog/log ::cleanse-summary :skipped-field-count skipped-field-count))
    {:success success
     :rejected-count 0
     :error-message nil}))

(defn persist-batch!
  "Persist a batch of events to DuckLake via staged insert.

   Called by the ingest batcher during flush. Handles:
     1. Schema diff - compare batch fields against event-metadata cache
     2. Schema evolution - ALTER TABLE ADD COLUMN for any new fields
     3. Staged insert - temp table + DuckDB Appender + INSERT FROM SELECT
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
  (span/with-span!
    [::persist-batch {:event-count (count events)}]
    (let [new-fields (-compute-schema-diff event-metadata fields)]
      (span/add-span-data! {:attributes {:new-field-count (count new-fields)}})

      ;; Step 1: Schema evolution (if needed)
      (when new-fields
        (schema/add-event-fields! duckdb new-fields)
        (event-metadata/refresh! event-metadata))

      ;; Step 2: Build columns and rows
      (let [columns (vec (keys fields))
            rows (-events->rows events columns)]

        ;; Step 3: Staged INSERT via Appender API + INSERT FROM SELECT
        (-insert-events-staged! duckdb columns fields rows)
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
