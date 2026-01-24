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
   [honey.sql :as sql]
   [next.jdbc :as jdbc]
   [o11ylite.store.events.query-cursor :as query-cursor]
   [o11ylite.store.events.query-schema :as query-schema]
   [o11ylite.store.query-util :as query-util]))

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate an events query request.
   Returns nil if valid, or error map with :error key if invalid."
  [query]
  (query-schema/validate query-schema/events-query query))

;; ---------------------------------------------------------
;; Column Name Handling (delegated to query-util)

(def ^:private -field->col query-util/field->col)

;; ---------------------------------------------------------
;; Filter Building (delegated to query-util)

(def ^:private -build-filter-clause query-util/build-filter-clause)

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

(def ^:private -epoch-ms->timestamp query-util/epoch-ms->timestamp)

(defn- -build-base-query
  "Build the base HoneySQL query with time range filter.
   time_range start/end are Unix epoch milliseconds."
  [{:keys [time_range]}]
  {:select [:*]
   :from [:events]
   :where [:and
           [:>= :timestamp (-epoch-ms->timestamp (:start time_range))]
           [:< :timestamp (-epoch-ms->timestamp (:end time_range))]]})

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

(def ^:private default-limit
  "Default limit for query results when not specified."
  100)

(defn- -add-order-and-limit
  "Add ORDER BY and LIMIT to query."
  [hsql-query {:keys [limit aggregations]}]
  (let [limit (or limit default-limit)
        ;; If aggregating, don't add default order
        ;; Otherwise order by timestamp desc, id desc for stable pagination
        has-aggregations? (seq aggregations)]
    (cond-> hsql-query
      (not has-aggregations?) (assoc :order-by [[:timestamp :desc] [:id :desc]])
      true (assoc :limit limit))))

(defn- -add-cursor-filter
  "Add cursor-based pagination filter to query.
   Uses keyset pagination: WHERE (timestamp, id) < (cursor_ts, cursor_id)"
  [hsql-query cursor-str]
  (if-let [{:keys [ts id]} (when cursor-str (query-cursor/decode cursor-str))]
    (update hsql-query :where conj
            [:or
             [:< :timestamp (-epoch-ms->timestamp ts)]
             [:and
              [:= :timestamp (-epoch-ms->timestamp ts)]
              [:< :id id]]])
    hsql-query))

;; ---------------------------------------------------------
;; Query Execution

(defn- -execute-table
  "Execute a table visualization query.
   Supports cursor-based pagination for non-aggregated queries."
  [duckdb {:keys [cursor limit] :as query}]
  (let [limit (or limit default-limit)
        ;; Fetch one extra row to detect if there are more results
        fetch-limit (inc limit)
        hsql-query (-> (-build-base-query query)
                       (-add-filter (:filter query))
                       (-add-cursor-filter cursor)
                       (-add-aggregations query)
                       (-add-order-and-limit (assoc query :limit fetch-limit)))
        [sql-str & params] (sql/format hsql-query {:dialect :ansi})
        all-rows (jdbc/execute! duckdb (into [sql-str] params))
        has-more? (> (count all-rows) limit)
        rows (if has-more? (take limit all-rows) all-rows)
        ;; Build next cursor from last row if there are more results
        next-cursor (when (and has-more? (seq rows))
                      (let [last-row (last rows)
                            ts (:timestamp last-row)
                            id (:id last-row)]
                        (when (and ts id)
                          (query-cursor/encode {:ts ts :id id}))))]
    {:rows (vec rows)
     :total_count (count rows)
     :has_more has-more?
     :next_cursor next-cursor}))

(def ^:private -bucket-ms->interval query-util/bucket-ms->interval)

(defn- -build-time-series-query
  "Build HoneySQL query for time series visualization.
   Auto-injects time bucketing; user's group_by fields become series labels."
  [{:keys [time_range filter aggregations group_by visualization bucket-ms]}]
  (let [bucket-interval (-bucket-ms->interval bucket-ms)
        ;; Time bucket expression wrapped with epoch_ms to get correct UTC milliseconds
        ;; (avoids JDBC timezone conversion issues when reading TIMESTAMP values)
        time-bucket-expr [:time_bucket bucket-interval :timestamp]
        bucket-expr [[:epoch_ms time-bucket-expr] :bucket]
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
                 [:>= :timestamp (-epoch-ms->timestamp (:start time_range))]
                 [:< :timestamp (-epoch-ms->timestamp (:end time_range))]]
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
                    ;; bucket is already epoch_ms (number) from the SQL query
                    {:timestamp (:bucket row)
                     :value (get row agg-alias)}))})))

(defn- -execute-time-series
  "Execute a time series visualization query."
  [duckdb {:keys [visualization aggregations group_by time_range] :as query}]
  (let [range-ms (- (:end time_range) (:start time_range))
        bucket-ms (or (:bucket_ms visualization)
                      (query-util/select-bucket-ms range-ms))
        start-ms (query-util/align-to-bucket (:start time_range) bucket-ms)
        end-ms (query-util/align-to-bucket (:end time_range) bucket-ms)
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

(def ^:private trace-limit
  "Maximum number of spans to return for a trace query."
  1000)

(defn- -build-trace-query
  "Build HoneySQL query for trace visualization.
   Fetches spans for a single trace, optimized for waterfall rendering.
   Timestamp conversion to epoch-ms is handled by jdbc-types/as-unqualified-maps."
  [{:keys [time_range filter]}]
  (let [trace-id (:value filter)
        signal-type-col (-field->col "meta.signal_type")]
    {:select [:span_id
              :parent_span_id
              :name
              :service
              [signal-type-col :meta.signal_type]
              [(-field->col "span.status_code") :span.status_code]
              [(-field->col "span.duration_ms") :span.duration_ms]
              :timestamp]
     :from [:events]
     :where [:and
             ; DuckLake has some weird bug, if I reorder these few lines it crashes or spitting out wrong result!
             [:or
              [:= signal-type-col "span"]
              [:= signal-type-col "span_event"]]
             [:= :trace_id trace-id]
             [:>= :timestamp (-epoch-ms->timestamp (:start time_range))]
             [:< :timestamp (-epoch-ms->timestamp (:end time_range))]]
     :order-by [[:timestamp :asc]]
     :limit trace-limit}))

(defn- -execute-trace
  "Execute a trace visualization query.
   Returns spans optimized for waterfall rendering with sub-ms timestamp precision."
  [duckdb query]
  (let [hsql-query (-build-trace-query query)
        [sql-str & params] (sql/format hsql-query {:dialect :ansi})
        rows (jdbc/execute! duckdb (into [sql-str] params))]
    {:spans (vec rows)
     :total_count (count rows)}))

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
                :has_more (get result :has_more false)}}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[honey.sql :as sql])
  (require '[integrant.core :as ig])
  (require '[o11ylite.components.duckdb-pool])
  (require '[o11ylite.store.init :as init])

  ;; Epoch milliseconds to timestamp conversion
  ;; Uses epoch_ms() which interprets input as milliseconds since Unix epoch (UTC)
  (-epoch-ms->timestamp 1702000000000)
  ;; => [:epoch_ms 1702000000000]

  (sql/format {:where [:>= :timestamp (-epoch-ms->timestamp 1702000000000)]}
              {:dialect :ansi})
  ;; => ["WHERE \"timestamp\" >= EPOCH_MS(?)" 1702000000000]

  ;; Build filter clause
  (-build-filter-clause {:field "service" :op "=" :value "api"})
  ;; => [:= :service "api"]

  (-build-filter-clause {:and [{:field "service" :op "=" :value "api"}
                               {:field "status" :op ">" :value 400}]})
  ;; => [:and [:= :service "api"] [:> :status 400]]

  ;; Full integration test
  (require '[integrant.repl.state :refer [system]])
  (def ds (:db/duckdb system))

  ;; Table query (timestamps in Unix epoch milliseconds)
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :limit 100
            :visualization {:type "table"}})
  ;; => {:data {:rows [...] :total_count 100 :has_more true
  ;;            :next_cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="}
  ;;     :metadata {:query_time_ms N :has_more true}}

  ;; Paginated query using cursor
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :limit 100
            :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
            :visualization {:type "table"}})
  ;; => {:data {:rows [...] :total_count 100 :has_more false :next_cursor nil}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Time series query - count events per minute
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:field "*" :function "count"}]
            :visualization {:type "time_series" :bucket_ms 60000}})
  ;; => {:data {:bucket_ms 60000
  ;;            :series [{:labels {} :name "count(*)"
  ;;                      :data [{:timestamp N :value N} ...]}]}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Time series with group_by - count per service per minute
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:field "*" :function "count"}]
            :group_by ["service"]
            :visualization {:type "time_series" :bucket_ms 60000}})
  ;; => {:data {:bucket_ms 60000
  ;;            :series [{:labels {:service "api"} :name "count(*)" :data [...]}
  ;;                     {:labels {:service "web"} :name "count(*)" :data [...]}]}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Trace query - fetch spans for a specific trace
  ;; Timestamp conversion handled by jdbc-types/as-unqualified-maps
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :filter {:field "trace_id" :op "=" :value "abc123"}
            :visualization {:type "trace"}})
  ;; => {:data {:spans [{:span_id "def456"
  ;;                     :parent_span_id nil
  ;;                     :name "HTTP GET /users"
  ;;                     :service "api-gateway"
  ;;                     :status_code "OK"
  ;;                     :duration_ms 45.2
  ;;                     :timestamp 1702000000123.456}
  ;;                    ...]
  ;;            :total_count 5}
  ;;     :metadata {:query_time_ms N :has_more false}}

  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
