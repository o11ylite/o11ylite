;; ---------------------------------------------------------
;; o11ylite.store.jdbc-types
;;
;; JDBC type conversions for DuckDB queries.
;;
;; Provides a custom result set builder that converts java.sql.Timestamp
;; to epoch milliseconds (as a double) for JSON-friendly serialization
;; with microsecond precision preserved in the fractional part.
;;
;; Usage: Wrap a DuckDB datasource with next.jdbc/with-options using
;; the builder-fn defined here. See duckdb_pool.clj for integration.
;; ---------------------------------------------------------

(ns o11ylite.store.jdbc-types
  (:require
   [next.jdbc.result-set :as rs])
  (:import
   [java.sql ResultSet ResultSetMetaData Timestamp]))

;; ---------------------------------------------------------
;; Timestamp Conversion

(defn- -timestamp->epoch-ms
  "Convert java.sql.Timestamp to epoch milliseconds as a double.
   Preserves microsecond precision in the fractional part.
   
   Example: 2024-01-01T00:00:00.123456Z -> 1704067200123.456"
  [^Timestamp ts]
  (let [epoch-ms (.getTime ts)
        nanos (.getNanos ts)
        ;; getNanos includes the ms portion (0-999999999)
        ;; Extract only the sub-millisecond part (0-999999 nanoseconds)
        sub-ms-nanos (mod nanos 1000000)
        ;; Convert to microseconds (0-999)
        sub-ms-micros (quot sub-ms-nanos 1000)]
    ;; Combine: whole milliseconds + fractional microseconds
    (+ (double epoch-ms) (/ sub-ms-micros 1000.0))))

;; ---------------------------------------------------------
;; Result Set Builder

(defn- -read-column-value
  "Read a column value, converting Timestamps to epoch-ms floats.
   All other types delegate to the default ReadableColumn implementation.
   
   This is a column-by-index-fn for use with rs/builder-adapter.
   Args: builder (contains :rsmeta), ResultSet, column index (1-based)."
  [builder ^ResultSet rs ^Integer i]
  (let [rsmeta ^ResultSetMetaData (:rsmeta builder)
        obj (.getObject rs i)]
    (if (instance? Timestamp obj)
      (-timestamp->epoch-ms obj)
      (rs/read-column-by-index obj rsmeta i))))

(def as-unqualified-maps
  "A builder-fn for next.jdbc that produces unqualified keyword maps
   with automatic Timestamp -> epoch milliseconds (float) conversion.
   
   Use with next.jdbc/with-options to apply to all queries on a datasource."
  (rs/builder-adapter rs/as-unqualified-maps -read-column-value))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[next.jdbc :as jdbc])
  (require '[integrant.core :as ig])

  ;; Example: wrap a datasource with timestamp conversion
  (def raw-ds (ig/init-key :db/duckdb {:data-path "./.tmp"}))
  (def ds (jdbc/with-options raw-ds {:builder-fn as-unqualified-maps}))

  ;; Now all queries automatically convert Timestamps
  (jdbc/execute! ds ["SELECT TIMESTAMP '2024-01-01 12:00:00.123456' AS ts"])
  ;; => [{:ts 1704106800123.456}]

  (ig/halt-key! :db/duckdb raw-ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
