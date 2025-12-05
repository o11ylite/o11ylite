;; ---------------------------------------------------------
;; o11ylite.ducklake.schema
;;
;; DuckLake schema introspection utilities.
;; Provides field metadata with normalized application-level types.
;; ---------------------------------------------------------

(ns o11ylite.ducklake.schema
  (:require
   [next.jdbc :as jdbc]))

;; ---------------------------------------------------------
;; Type Normalization
;;
;; Maps DuckDB types to application-level types:
;;   :string  - text data (VARCHAR, etc.)
;;   :instant - timestamps (TIMESTAMP, TIMESTAMP_NS, etc.)
;;   :integer - whole numbers (INTEGER, BIGINT, SMALLINT, etc.)
;;   :float   - decimal numbers (FLOAT, DOUBLE, DECIMAL, etc.)
;;   :boolean - true/false (BOOLEAN)

(defn- normalize-type
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

;; ---------------------------------------------------------
;; Public API

(defn fetch-event-fields
  "Fetch field metadata from the events table.
   Returns a map of field-name -> {:type normalized-type}.
   
   Types are normalized to: :string, :instant, :integer, :float, :boolean"
  [duckdb-ds]
  (let [rows (jdbc/execute! duckdb-ds ["DESCRIBE ducklake.events"])]
    (->> rows
         (map (fn [row]
                [(:column_name row)
                 {:type (normalize-type (:column_type row))}]))
         (into {}))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start DuckDB
  (def ds (ig/init-key :db/duckdb {:data-path "./.tmp"}))

  ;; Fetch fields with normalized types
  (fetch-event-fields ds)
  ;; => {"service" {:type :string}
  ;;     "timestamp" {:type :instant}
  ;;     "span.duration_ns" {:type :integer}
  ;;     ...}

  ;; Test type normalization
  (normalize-type "VARCHAR")       ;; => :string
  (normalize-type "TIMESTAMP_NS")  ;; => :instant
  (normalize-type "BIGINT")        ;; => :integer
  (normalize-type "DOUBLE")        ;; => :float
  (normalize-type "BOOLEAN")       ;; => :boolean

  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
