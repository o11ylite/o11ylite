;; ---------------------------------------------------------
;; o11ylite.ducklake.schema
;;
;; DuckLake schema utilities: introspection, type inference, and type mapping.
;; Provides field metadata with normalized application-level types.
;;
;; Type System:
;;   :string  - text data
;;   :instant - timestamps
;;   :integer - whole numbers
;;   :float   - decimal numbers
;;   :boolean - true/false
;;
;; Three type conversion functions:
;;   normalize-type      - DuckDB type → app type (for introspection)
;;   infer-type          - Clojure value → app type (for ingestion)
;;   app-type->duckdb    - app type → DuckDB type (for ALTER TABLE)
;; ---------------------------------------------------------

(ns o11ylite.ducklake.schema
  (:require
   [next.jdbc :as jdbc])
  (:import
   [java.time Instant]))

;; ---------------------------------------------------------
;; Type Conversions

(defn- -normalize-type
  "Normalize a DuckDB column type to an application-level type."
  [duckdb-type]
  (let [t (-> duckdb-type str .toUpperCase)]
    (cond
      ;; Timestamps
      (or (.startsWith t "TIMESTAMP")
          (.contains t "DATE")
          (.contains t "TIME"))
      :instant

      ;; Integers
      (or (.contains t "INT")      ; INTEGER, BIGINT, SMALLINT, TINYINT, HUGEINT
          (= t "UBIGINT")
          (= t "UINTEGER")
          (= t "USMALLINT")
          (= t "UTINYINT"))
      :integer

      ;; Floats
      (or (.contains t "FLOAT")
          (.contains t "DOUBLE")
          (.contains t "DECIMAL")
          (.contains t "REAL")
          (.contains t "NUMERIC"))
      :float

      ;; Booleans
      (= t "BOOLEAN")
      :boolean

      ;; Everything else is string (VARCHAR, BLOB, UUID, JSON, etc.)
      :else
      :string)))

(defn infer-type
  "Infer normalized app type from a Clojure value.
   Returns one of: :string, :instant, :integer, :float, :boolean"
  [value]
  (cond
    (nil? value)                      :string  ; default to string for nil
    (instance? Instant value)         :instant
    (instance? java.util.Date value)  :instant
    (boolean? value)                  :boolean
    (int? value)                      :integer
    (integer? value)                  :integer ; covers Long, BigInteger, etc.
    (float? value)                    :float
    (number? value)                   :float   ; covers Double, BigDecimal, etc.
    :else                             :string))

(def ^:private app-type->duckdb-type
  "Maps application types to DuckDB column types."
  {:string  "VARCHAR"
   :instant "TIMESTAMP_NS"
   :integer "BIGINT"
   :float   "DOUBLE"
   :boolean "BOOLEAN"})

(defn app-type->duckdb
  "Convert an application type to a DuckDB column type.
   Returns DuckDB type string, or VARCHAR for unknown types."
  [app-type]
  (get app-type->duckdb-type app-type "VARCHAR"))

;; ---------------------------------------------------------
;; Public API

(defn fetch-event-fields
  "Fetch field metadata from the events table.
   Returns a map of keyword -> {:type normalized-type}.

   Types are normalized to: :string, :instant, :integer, :float, :boolean"
  [duckdb-ds]
  (let [rows (jdbc/execute! duckdb-ds ["DESCRIBE ducklake.events"])]
    (->> rows
         (map (fn [row]
                [(keyword (:column_name row))
                 {:type (-normalize-type (:column_type row))}]))
         (into {}))))

(defn add-fields!
  "Add new fields (columns) to the events table for schema evolution.
   Executes ALTER TABLE ADD COLUMN IF NOT EXISTS for each field.
   All fields are added in a single transaction for atomicity.

   Arguments:
     duckdb-ds  - DuckDB datasource
     fields     - Map of keyword -> {:type app-type}

   Example:
     (add-fields! ds {:attr.http.method {:type :string}
                      :attr.http.status_code {:type :integer}})"
  [duckdb-ds fields]
  (jdbc/with-transaction [tx duckdb-ds]
    (doseq [[field-key field-meta] fields]
      (let [duckdb-type (app-type->duckdb (:type field-meta))
            ; Notice the IF NOT EXISTS here.
            ; There is a chance that we swallow a conflicting type error here.
            ; It may cause the whole batch to fail. This a compromise.
            ; But we anticipate this to be rare:
            ; - 1. Conflicting data types from source are rare.
            ; - 2. We had various prevention mechanism before this.
            ; - 3. We anticipate client side to retry error. Retry would work because the metadata
            ;      cache would've catch up, and reject only the bad seed retry.
            sql (format "ALTER TABLE ducklake.events ADD COLUMN IF NOT EXISTS \"%s\" %s"
                        (name field-key)
                        duckdb-type)]
        (jdbc/execute! tx [sql])))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start DuckDB
  (def ds (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  ;; Fetch fields with normalized types
  (fetch-event-fields ds)
  ;; => {:service {:type :string}
  ;;     :timestamp {:type :instant}
  ;;     :span.duration_ns {:type :integer}
  ;;     ...}

  (ig/halt-key! :db/duckdb ds)

  ;; Type inference from Clojure values
  (infer-type "hello")           ;; => :string
  (infer-type 42)                ;; => :integer
  (infer-type 3.14)              ;; => :float
  (infer-type true)              ;; => :boolean
  (infer-type (Instant/now))     ;; => :instant
  (infer-type nil)               ;; => :string

  ;; App type to DuckDB type
  (app-type->duckdb :string)     ;; => "VARCHAR"
  (app-type->duckdb :instant)    ;; => "TIMESTAMP_NS"
  (app-type->duckdb :integer)    ;; => "BIGINT"
  (app-type->duckdb :float)      ;; => "DOUBLE"
  (app-type->duckdb :boolean)    ;; => "BOOLEAN"

  ;; DuckDB type normalization (internal)
  (-normalize-type "VARCHAR")       ;; => :string
  (-normalize-type "TIMESTAMP_NS")  ;; => :instant
  (-normalize-type "BIGINT")        ;; => :integer
  (-normalize-type "DOUBLE")        ;; => :float
  (-normalize-type "BOOLEAN")       ;; => :boolean

  ;; Add fields to events table
  (add-fields! ds {:attr.http.method {:type :string}
                   :attr.http.status_code {:type :integer}})

  ;; Verify columns were added
  (fetch-event-fields ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
