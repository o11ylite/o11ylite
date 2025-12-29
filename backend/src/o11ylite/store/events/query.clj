;; ---------------------------------------------------------
;; o11ylite.store.events.query
;;
;; Event querying: search and retrieval for observability events
;; (spans, span-events, logs).
;;
;; Exports:
;; - validate function for validating query requests
;; - execute function for running queries against DuckDB
;; ---------------------------------------------------------

(ns o11ylite.store.events.query
  (:require
   [clojure.string :as str]
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [o11ylite.store.events.query-schema :as query-schema]))

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate an events query request.
   Returns nil if valid, or error map with :error key if invalid."
  [query]
  (query-schema/validate query-schema/events-query query))

;; ---------------------------------------------------------
;; Column Name Handling

(defn- -field->col
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
    "exists" :is-not))

(defn- -build-simple-filter
  "Build a HoneySQL clause from a simple filter."
  [{:keys [field op value]}]
  (let [sql-op (-filter-op->sql op)
        col (-field->col field)]
    (case op
      "contains" [sql-op col (str "%" value "%")]
      "exists" [sql-op col nil]
      [sql-op col value])))

(defn- -build-filter-clause
  "Recursively build HoneySQL WHERE clause from filter expression."
  [filter-expr]
  (cond
    ;; Compound AND
    (:and filter-expr)
    (into [:and] (map -build-filter-clause (:and filter-expr)))

    ;; Compound OR
    (:or filter-expr)
    (into [:or] (map -build-filter-clause (:or filter-expr)))

    ;; Simple filter
    :else
    (-build-simple-filter filter-expr)))

;; ---------------------------------------------------------
;; Aggregation Building

(defn- -format-agg-alias
  "Format aggregation alias as function(field), e.g., count(*), avg(duration)."
  [function field]
  (str function "(" field ")"))

(defn- -build-aggregation-expr
  "Build a HoneySQL aggregation expression.
   Returns [expr alias] for use in select."
  [{:keys [field function]}]
  (let [col (if (= field "*") :* (-field->col field))
        agg-fn (keyword function)
        expr (case function
               ;; Percentiles use approx_quantile in DuckDB
               "p50" [:approx_quantile col 0.50]
               "p90" [:approx_quantile col 0.90]
               "p99" [:approx_quantile col 0.99]
               ;; Standard aggregations
               [agg-fn col])
        result-alias (-format-agg-alias function field)]
    [expr (keyword result-alias)]))

;; ---------------------------------------------------------
;; Query Building

(defn- -epoch-s->timestamp
  "Convert epoch seconds to DuckDB TIMESTAMP.
   Uses to_timestamp(epoch_s)::TIMESTAMP to handle timezone correctly.
   to_timestamp returns TIMESTAMPTZ, casting to TIMESTAMP converts to local time
   which matches how TIMESTAMP_NS data is stored."
  [epoch-s]
  [:cast [:to_timestamp epoch-s] :timestamp])

(defn- -build-base-query
  "Build the base HoneySQL query with time range filter.
   time_range start/end are Unix epoch seconds."
  [{:keys [time_range]}]
  {:select [:*]
   :from [:events]
   :where [:and
           [:>= :timestamp (-epoch-s->timestamp (:start time_range))]
           [:< :timestamp (-epoch-s->timestamp (:end time_range))]]})

(defn- -add-filter
  "Add filter clause to query if present."
  [hsql-query filter-expr]
  (if filter-expr
    (update hsql-query :where conj (-build-filter-clause filter-expr))
    hsql-query))

(defn- -add-aggregations
  "Add aggregations and group-by to query if present."
  [hsql-query {:keys [aggregations group_by]}]
  (if (seq aggregations)
    (let [agg-exprs (map -build-aggregation-expr aggregations)
          group-cols (map -field->col group_by)
          ;; Build select clause: [col alias] for each group column
          ;; The alias is the original field name as a keyword
          group-select (map (fn [field col] [col (keyword field)]) group_by group-cols)
          select-clause (concat group-select agg-exprs)]
      (cond-> (assoc hsql-query :select (vec select-clause))
        (seq group-cols) (assoc :group-by (vec group-cols))))
    hsql-query))

(defn- -add-order-and-limit
  "Add ORDER BY and LIMIT to query."
  [hsql-query {:keys [visualization aggregations]}]
  (let [limit (or (:limit visualization) 200)
        ;; If aggregating, don't add default order
        ;; Otherwise order by timestamp desc
        has-aggregations? (seq aggregations)]
    (cond-> hsql-query
      (not has-aggregations?) (assoc :order-by [[:timestamp :desc]])
      true (assoc :limit limit))))

;; ---------------------------------------------------------
;; Query Execution

(defn- -execute-table
  "Execute a table visualization query."
  [duckdb query]
  (let [hsql-query (-> (-build-base-query query)
                       (-add-filter (:filter query))
                       (-add-aggregations query)
                       (-add-order-and-limit query))
        [sql-str & params] (sql/format hsql-query {:dialect :ansi})
        rows (jdbc/execute! duckdb (into [sql-str] params))
        limit (or (get-in query [:visualization :limit]) 200)]
    {:rows rows
     :total_count (count rows)
     :truncated (>= (count rows) limit)}))

(defn- -bucket-ms->interval
  "Convert bucket size in milliseconds to DuckDB INTERVAL expression.
   Uses raw SQL since HoneySQL doesn't have built-in interval support."
  [bucket-ms]
  (let [seconds (quot bucket-ms 1000)]
    [:raw (str "INTERVAL '" seconds " seconds'")]))

(defn- -build-time-series-query
  "Build HoneySQL query for time series visualization.
   Auto-injects time bucketing; user's group_by fields become series labels."
  [{:keys [time_range filter aggregations group_by visualization bucket-ms]}]
  (let [bucket-interval (-bucket-ms->interval bucket-ms)
        ;; Time bucket expression: time_bucket(interval, timestamp)
        bucket-expr [[:time_bucket bucket-interval :timestamp] :bucket]
        ;; Build aggregation expressions
        agg-exprs (map -build-aggregation-expr aggregations)
        ;; Group by columns (series labels)
        group-cols (map -field->col group_by)
        group-select (map (fn [field col] [col (keyword field)]) group_by group-cols)
        ;; Select: bucket, group_by fields, aggregations
        select-clause (into [bucket-expr] (concat group-select agg-exprs))
        ;; Group by: bucket + user's group_by fields
        group-by-clause (into [:bucket] group-cols)]
    (-> {:select (vec select-clause)
         :from [:events]
         :where [:and
                 [:>= :timestamp (-epoch-s->timestamp (:start time_range))]
                 [:< :timestamp (-epoch-s->timestamp (:end time_range))]]
         :group-by group-by-clause
         :order-by [[:bucket :asc]]}
        (-add-filter filter))))

(defn- -rows->series
  "Transform query result rows into series format.
   Creates one series per (labels, aggregation) combination.
   Each series has :labels, :name, and :data with {:timestamp, :value} points."
  [rows group-by-fields agg-aliases]
  (let [label-keys (map keyword group-by-fields)
        grouped (group-by #(select-keys % label-keys) rows)]
    (for [[labels rows-for-series] grouped
          agg-alias agg-aliases]
      {:labels labels
       :name (name agg-alias)
       :data (vec (for [row rows-for-series]
                    {:timestamp (.getTime (:bucket row))
                     :value (get row agg-alias)}))})))

(defn- -align-to-bucket
  "Align a timestamp (in seconds) down to the nearest bucket boundary.
   Returns the aligned timestamp in milliseconds."
  [epoch-s bucket-ms]
  (let [epoch-ms (* epoch-s 1000)]
    (- epoch-ms (mod epoch-ms bucket-ms))))

(defn- -execute-time-series
  "Execute a time series visualization query."
  [duckdb {:keys [visualization aggregations group_by time_range] :as query}]
  (let [bucket-ms (or (:bucket_ms visualization)
                      (max 1000 (* 1000 (quot (- (:end time_range) (:start time_range)) 100))))
        start-ms (-align-to-bucket (:start time_range) bucket-ms)
        end-ms (-align-to-bucket (:end time_range) bucket-ms)
        hsql-query (-build-time-series-query (assoc query :bucket-ms bucket-ms))
        [sql-str & params] (sql/format hsql-query {:dialect :ansi})
        rows (jdbc/execute! duckdb (into [sql-str] params))
        agg-aliases (map (fn [{:keys [field function]}]
                           (keyword (-format-agg-alias function field)))
                         aggregations)
        series (-rows->series rows (or group_by []) agg-aliases)]
    {:bucket_ms bucket-ms
     :start_ms start-ms
     :end_ms end-ms
     :series (vec series)}))

;; DEFERRED: Heatmap visualization is deferred to post-v1.
;; When implemented, this should:
;; 1. Use DuckDB's histogram() or width_bucket() for value bucketing
;; 2. Combine with time_bucket() for 2D (time x value) histogram
;; 3. Return matrix of counts for heatmap rendering
;; The UI will show a "Distribution of: [field]" selector instead of
;; the full aggregation builder.
(defn- -execute-heatmap
  "Execute a heatmap visualization query. (DEFERRED - returns empty data)"
  [_duckdb _query]
  {:x_buckets []
   :y_buckets []
   :values []})

;; Trace visualization: Part of v1, accessed via dedicated /trace/:id page.
;; Uses /api/query/events with filter: {field: "trace_id", op: "=", value: "<id>"}
;; and visualization: {type: "trace"}. Users click trace_id links in table results.
;; TODO: Implement span retrieval and waterfall data structure.
(defn- -execute-trace
  "Execute a trace visualization query. (TODO - returns empty data)"
  [_duckdb _query]
  {:spans []
   :root_span_id nil
   :total_count 0
   :truncated false})

;; ---------------------------------------------------------
;; Public API

(defn execute
  "Execute an events query.
   Assumes query has already been validated against events-query schema.
   Returns {:data <result> :metadata {...}}."
  [duckdb {:keys [visualization] :as query}]
  (let [start-time (System/currentTimeMillis)
        result (case (:type visualization)
                 "table" (-execute-table duckdb query)
                 "time_series" (-execute-time-series duckdb query)
                 "heatmap" (-execute-heatmap duckdb query)
                 "trace" (-execute-trace duckdb query))
        query-time-ms (- (System/currentTimeMillis) start-time)]
    {:data result
     :metadata {:query_time_ms query-time-ms
                :truncated (get result :truncated false)}}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[honey.sql :as sql])
  (require '[integrant.core :as ig])
  (require '[o11ylite.components.duckdb-pool])
  (require '[o11ylite.store.init :as init])

  ;; Epoch seconds to timestamp conversion
  ;; Uses to_timestamp(epoch_s)::TIMESTAMP for proper timezone handling
  ;; to_timestamp returns TIMESTAMPTZ, cast to TIMESTAMP converts to local time
  (-epoch-s->timestamp 1702000000)
  ;; => [:cast [:to_timestamp 1702000000] :timestamp]

  (sql/format {:where [:>= :timestamp (-epoch-s->timestamp 1702000000)]}
              {:dialect :ansi})
  ;; => ["WHERE timestamp >= CAST(TO_TIMESTAMP(?) AS TIMESTAMP)" 1702000000]

  ;; Build filter clause
  (-build-filter-clause {:field "service" :op "=" :value "api"})
  ;; => [:= :service "api"]

  (-build-filter-clause {:and [{:field "service" :op "=" :value "api"}
                               {:field "status" :op ">" :value 400}]})
  ;; => [:and [:= :service "api"] [:> :status 400]]

  ;; Full integration test
  (def ds (ig/init-key :db/duckdb {:data-path "./.tmp"}))
  (init/init-store! ds)

  ;; Table query (timestamps in Unix epoch seconds)
  (execute ds
           {:time_range {:start 1702000000 :end 1702003600}
            :visualization {:type "table" :limit 100}})
  ;; => {:data {:rows [] :total_count 0 :truncated false}
  ;;     :metadata {:query_time_ms N :truncated false}}

  ;; Time series query - count events per minute
  (execute ds
           {:time_range {:start 1702000000 :end 1702003600}
            :aggregations [{:field "*" :function "count"}]
            :visualization {:type "time_series" :bucket_ms 60000}})
  ;; => {:data {:bucket_ms 60000
  ;;            :series [{:labels {} :name "count(*)"
  ;;                      :data [{:timestamp N :value N} ...]}]}
  ;;     :metadata {:query_time_ms N :truncated false}}

  ;; Time series with group_by - count per service per minute
  (execute ds
           {:time_range {:start 1702000000 :end 1702003600}
            :aggregations [{:field "*" :function "count"}]
            :group_by ["service"]
            :visualization {:type "time_series" :bucket_ms 60000}})
  ;; => {:data {:bucket_ms 60000
  ;;            :series [{:labels {:service "api"} :name "count(*)" :data [...]}
  ;;                     {:labels {:service "web"} :name "count(*)" :data [...]}]}
  ;;     :metadata {:query_time_ms N :truncated false}}

  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
