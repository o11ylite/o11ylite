;; ---------------------------------------------------------
;; o11ylite.store.ingest-util
;;
;; Shared bulk-insert machinery for DuckLake ingestion.
;;
;; Uses temp staging table + DuckDB Appender API + INSERT FROM SELECT
;; to achieve high-throughput writes into DuckLake tables.
;;
;; The Appender API bypasses SQL parsing entirely, writing directly to
;; DuckDB's columnar storage. The INSERT FROM SELECT then pushes data
;; through DuckLake in a single bulk operation. This achieves ~100-200x
;; throughput improvement over parameterized INSERT VALUES.
;;
;; Both the events and metrics ingestion pipelines use this shared util.
;; ---------------------------------------------------------

(ns o11ylite.store.ingest-util
  (:require
    [clojure.string :as str]
    [next.jdbc :as jdbc])
  (:import
    [java.time LocalDateTime]
    [org.duckdb DuckDBAppender DuckDBConnection]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -create-staging-table!
  "Create (or replace) the temp staging table with columns matching this batch.
   The staging table is a plain in-memory DuckDB table (not DuckLake), so the
   Appender API can write to it directly.

   column-type-fn is called with each column keyword and must return a DuckDB
   type string (e.g., \"VARCHAR\", \"BIGINT\", \"TIMESTAMP_NS\", \"BIGINT[]\")."
  [conn columns column-type-fn]
  (let [col-defs (str/join ", "
                           (map (fn [col]
                                  (str "\"" (name col) "\" " (column-type-fn col)))
                                columns))
        sql (str "CREATE OR REPLACE TEMP TABLE _ingest_staging (" col-defs ")")]
    (with-open [stmt (.createStatement conn)]
      (.execute stmt sql))))

(defn- -build-insert-from-staging-sql
  "Build the INSERT INTO ... SELECT FROM staging SQL with explicit column lists.
   Ensures column ordering matches between the DuckLake table and staging table."
  [target-table columns]
  (let [col-list (str/join ", " (map #(str "\"" (name %) "\"") columns))]
    (str "INSERT INTO " target-table " (" col-list ") SELECT " col-list " FROM _ingest_staging")))

;; ---------------------------------------------------------
;; Public API

(defn append-value!
  "Append a single coerced value to the DuckDB Appender.
   Dispatches to typed append methods based on the value's Java type.

   Type hints are required because DuckDBAppender.append() has many overloads
   and Clojure's reflector cannot disambiguate without them.

   Nulls use appendNull() — the typed append(Object nil) doesn't work because
   DuckDB can't infer the target column type from a null Object reference.

   Supports all primitive types plus:
   - LocalDateTime for TIMESTAMP columns
   - long[] (Java arrays) for BIGINT[] / LIST columns (e.g., hist.counts)"
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
      ;; Native array support for BIGINT[] columns (e.g., histogram bucket counts).
      ;; DuckDB Appender v1.4.1+ supports LIST types natively via typed Java arrays.
      (instance? (Class/forName "[J") v) (.append appender ^longs v)
      (number? v)                 (.append appender (double v))
      :else                       (.append appender ^String (str v)))))

(defn load-staging!
  "Bulk-load rows into the staging table via the DuckDB Appender API.
   The Appender bypasses SQL parsing entirely, writing directly to DuckDB's
   columnar storage. Much faster than INSERT VALUES for large batches."
  [^DuckDBConnection duck-conn rows ^long num-cols]
  (with-open [appender (.createAppender duck-conn "temp" "main" "_ingest_staging")]
    (doseq [row rows]
      (.beginRow appender)
      (dotimes [i num-cols]
        (append-value! appender (nth row i)))
      (.endRow appender))))

(defn insert-staged!
  "Insert rows into a DuckLake table via temp staging table + Appender API.

   All operations use the same connection so the temp table is visible throughout.

   Steps:
     1. Create temp staging table with batch column schema
     2. Bulk-load rows via Appender API (no SQL parsing)
     3. INSERT INTO target SELECT FROM staging (single DuckLake transaction)

   Arguments:
     duckdb         - DuckDB datasource (HikariCP-wrapped)
     opts map with:
       :target-table  - Fully-qualified DuckLake table name (e.g., \"o11ylite.events\")
       :columns       - Vector of column keywords in insertion order
       :column-type-fn - Function (keyword -> String) returning DuckDB type for each column
       :rows          - Vector of row vectors (values already coerced)"
  [duckdb {:keys [target-table columns column-type-fn rows]}]
  (with-open [conn (jdbc/get-connection duckdb)]
    ;; Unwrap the HikariCP proxy to get the raw DuckDBConnection.
    ;; The Appender API (.createAppender) is DuckDB-specific and not
    ;; part of JDBC, so it's only available on the unwrapped connection.
    (let [duck-conn (.unwrap conn DuckDBConnection)
          num-cols (count columns)]
      ;; Step 1: Create staging table matching batch columns
      (-create-staging-table! conn columns column-type-fn)
      ;; Step 2: Load data into staging via Appender (bypasses SQL entirely)
      (load-staging! duck-conn rows num-cols)
      ;; Step 3: Push from staging into DuckLake in one bulk operation
      (with-open [stmt (.createStatement conn)]
        (.execute stmt (-build-insert-from-staging-sql target-table columns))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example usage (events):
  ;; (insert-staged! duckdb
  ;;   {:target-table "o11ylite.events"
  ;;    :columns [:service :timestamp :name]
  ;;    :column-type-fn (fn [col] (schema/app-type->duckdb (get-in fields [col :type] :string)))
  ;;    :rows [["svc1" #inst "2024-01-01" "GET /api"] ...]})

  ;; Example usage (metrics):
  ;; (insert-staged! duckdb
  ;;   {:target-table "o11ylite.metrics"
  ;;    :columns [:name :service :timestamp :value :attr.host.name]
  ;;    :column-type-fn (fn [col] (get core-column-types col "VARCHAR"))
  ;;    :rows [["cpu.util" "svc1" #inst "2024-01-01" 42.5 "server-1"] ...]})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
