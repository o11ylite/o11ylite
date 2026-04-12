;; ---------------------------------------------------------
;; o11ylite.store.query-util
;;
;; Shared query utilities for events and metrics queries.
;; Provides time bucketing, alignment, filter building, and common SQL helpers.
;; ---------------------------------------------------------

(ns o11ylite.store.query-util
  (:require
    [clojure.string :as str]))

;; ---------------------------------------------------------
;; Time Bucket Selection

(def bucket-sizes-ms
  "Allowed bucket sizes in milliseconds, ascending order.
   These are 'nice' intervals that align naturally to time boundaries."
  [1000        ; 1s
   5000        ; 5s
   10000       ; 10s
   20000       ; 20s
   30000       ; 30s
   60000       ; 1m
   120000      ; 2m
   300000      ; 5m
   600000      ; 10m
   1200000     ; 20m
   1800000     ; 30m
   3600000     ; 1h
   7200000     ; 2h
   14400000    ; 4h
   21600000    ; 6h
   43200000    ; 12h
   86400000])  ; 1d

(defn select-bucket-ms
  "Select the smallest 'nice' bucket size that yields ~100 buckets for the given range.
   Returns a bucket size from bucket-sizes-ms that produces approximately 100 buckets."
  [range-ms]
  (let [target-buckets 100
        ideal-bucket (quot range-ms target-buckets)]
    (or (first (filter #(>= % ideal-bucket) bucket-sizes-ms))
        (last bucket-sizes-ms))))

(defn align-to-bucket
  "Align a timestamp (in milliseconds) down to the nearest bucket boundary.
   Returns the aligned timestamp in milliseconds."
  [epoch-ms bucket-ms]
  (- epoch-ms (mod epoch-ms bucket-ms)))

;; ---------------------------------------------------------
;; Column Name Handling

(defn field->col
  "Convert a field name string to a HoneySQL column reference.
   Field names containing dots need special handling because HoneySQL
   interprets dots as namespace separators. For example, :attr.http.method
   becomes \"attr\".\"http\".\"method\" which is incorrect.
   Instead, we use [:raw ...] to preserve the literal column name."
  [field]
  (if (str/includes? field ".")
    [:raw (str "\"" field "\"")]
    (keyword field)))

;; ---------------------------------------------------------
;; Timestamp Conversion

(defn epoch-ms->timestamp
  "Convert epoch milliseconds to DuckDB TIMESTAMP.
   Uses epoch_ms() which interprets the input as milliseconds since Unix epoch (UTC).
   This matches how events and metrics are stored."
  [epoch-ms]
  [:epoch_ms epoch-ms])

;; ---------------------------------------------------------
;; Interval Conversion

(defn bucket-ms->interval
  "Convert bucket size in milliseconds to DuckDB INTERVAL expression.
   Uses raw SQL since HoneySQL doesn't have built-in interval support."
  [bucket-ms]
  (let [seconds (quot bucket-ms 1000)]
    [:raw (str "INTERVAL '" seconds " seconds'")]))

;; ---------------------------------------------------------
;; Filter Value Coercion

(defn- -coerce-value
  "Coerce a filter value to match the expected field type.
   Handles string values coming from JSON where the frontend sent e.g. \"true\"
   instead of the boolean true. Returns the coerced value, or the original
   if coercion is not needed or not possible."
  [value field-type]
  (cond
    (nil? field-type) value
    (not (string? value)) value

    (= :boolean field-type)
    (case (str/lower-case value)
      "true" true
      "false" false
      value)

    (= :integer field-type)
    (try (Long/parseLong value) (catch NumberFormatException _ value))

    (= :float field-type)
    (try (Double/parseDouble value) (catch NumberFormatException _ value))

    :else value))

(defn- -coerce-filter-expr
  "Recursively walk a filter expression and coerce values based on field metadata."
  [field-metadata filter-expr]
  (cond
    (:and filter-expr)
    (update filter-expr :and (partial mapv #(-coerce-filter-expr field-metadata %)))

    (:or filter-expr)
    (update filter-expr :or (partial mapv #(-coerce-filter-expr field-metadata %)))

    :else
    (if-let [field-meta (get field-metadata (keyword (:field filter-expr)))]
      (update filter-expr :value -coerce-value (:type field-meta))
      filter-expr)))

(defn coerce-filter-values
  "Coerce filter values in a query to match their field types.
   field-metadata is a map of keyword -> {:type app-type}.
   Returns the query with coerced filter values."
  [field-metadata query]
  (if (:filter query)
    (update query :filter (partial -coerce-filter-expr field-metadata))
    query))

;; ---------------------------------------------------------
;; Filter Building

(defn- -filter-op->sql
  "Convert filter operator string to HoneySQL operator."
  [op]
  (case op
    "=" :=
    "!=" :<>
    ">" :>
    "<" :<
    ">=" :>=
    "<=" :<=
    "contains" :like
    "starts-with" :like
    "exists" :is-not))

(defn- -build-simple-filter
  "Build a HoneySQL clause from a simple filter.
   Boolean values use IS/IS NOT instead of =/!= to work around
   a DuckLake JDBC bug where parameterized `col = ?` with boolean
   returns no results (confirmed on duckdb_jdbc 1.5.1.0)."
  [{:keys [field op value]}]
  (let [sql-op (-filter-op->sql op)
        col (field->col field)]
    (cond
      (= op "contains") [sql-op col (str "%" value "%")]
      (= op "starts-with") [sql-op col (str value "%")]
      (= op "exists") [sql-op col nil]
      (and (boolean? value) (= op "=")) [:is col value]
      (and (boolean? value) (= op "!=")) [:is-not col value]
      :else [sql-op col value])))

(defn build-filter-clause
  "Recursively build HoneySQL WHERE clause from filter expression.
   Handles simple filters and compound AND/OR expressions."
  [filter-expr]
  (cond
    ;; Compound AND
    (:and filter-expr)
    (into [:and] (map build-filter-clause (:and filter-expr)))

    ;; Compound OR
    (:or filter-expr)
    (into [:or] (map build-filter-clause (:or filter-expr)))

    ;; Simple filter
    :else
    (-build-simple-filter filter-expr)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[honey.sql :as sql])

  ;; Bucket size selection examples
  (select-bucket-ms 3600000)    ;; 1 hour => 60000 (1 min buckets, ~60 buckets)
  (select-bucket-ms 300000)     ;; 5 minutes => 5000 (5s buckets, ~60 buckets)
  (select-bucket-ms 86400000)   ;; 1 day => 1200000 (20 min buckets, ~72 buckets)
  (select-bucket-ms 604800000)  ;; 1 week => 7200000 (2 hour buckets, ~84 buckets)

  ;; Bucket alignment examples
  (align-to-bucket 1702000000123 60000)  ;; => 1702000000000 (aligned to minute)
  (align-to-bucket 1702000045000 60000)  ;; => 1702000000000 (aligned to minute)
  (align-to-bucket 1702000060000 60000)  ;; => 1702000060000 (already aligned)

  ;; Column name handling
  (field->col "service")          ;; => :service
  (field->col "attr.http.method") ;; => [:raw "\"attr.http.method\""]

  ;; Epoch milliseconds to timestamp conversion
  (epoch-ms->timestamp 1702000000000)
  ;; => [:epoch_ms 1702000000000]

  (sql/format {:where [:>= :timestamp (epoch-ms->timestamp 1702000000000)]}
              {:dialect :ansi})
  ;; => ["WHERE \"timestamp\" >= EPOCH_MS(?)" 1702000000000]

  ;; Bucket interval conversion
  (bucket-ms->interval 60000)  ;; => [:raw "INTERVAL '60 seconds'"]

  ;; Filter building
  (build-filter-clause {:field "service" :op "=" :value "api"})
  ;; => [:= :service "api"]

  (build-filter-clause {:and [{:field "service" :op "=" :value "api"}
                              {:field "status" :op ">" :value 400}]})
  ;; => [:and [:= :service "api"] [:> :status 400]]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
