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
    [o11ylite.store.events.query-validation :as query-validation]
    [o11ylite.store.query-util :as query-util]))

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate an events query request.
   Performs schema validation, then field-existence and type-aware filter validation.

   `fields` is the events-table field metadata map
   ({keyword -> {:type t}}).

   Returns nil if valid, or error map with :error key if invalid."
  [fields query]
  (or (query-schema/validate query-schema/events-query query)
      (query-validation/validate-fields-exist fields query)
      (query-validation/validate-filter-ops-with-metadata fields query)))

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
               ;; Percentiles use approx_quantile in DuckDB.
               ;; Cast quantile to float because DuckDB expects FLOAT not DOUBLE.
               "p50" [:approx_quantile col [:cast 0.50 :float]]
               "p90" [:approx_quantile col [:cast 0.90 :float]]
               "p99" [:approx_quantile col [:cast 0.99 :float]]
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
          group-select (map (fn [field col] [col (keyword field)]) group_by group-cols)
          select-clause (concat group-select agg-exprs)]
      (cond-> (assoc hsql-query :select (vec select-clause))
        (seq group-cols) (assoc :group-by (vec group-cols))))
    hsql-query))

(def ^:private default-limit
  "Default limit for query results when not specified."
  100)

(defn- -resolve-ref
  "Resolve an aggregation ref ID to a HoneySQL column reference.
   Looks up the aggregation by :id and returns the alias.
   Uses [:raw ...] for aliases containing dots to prevent HoneySQL
   from splitting on dots as namespace separators."
  [aggregations ref-id]
  (when-let [agg (first (filter #(= ref-id (:id %)) aggregations))]
    (let [alias (-format-agg-alias (:function agg) (:field agg))]
      (if (.contains alias ".")
        [:raw (str "\"" alias "\"")]
        (keyword alias)))))

(defn- -resolve-sort-col
  "Resolve sort config to a HoneySQL column reference.
   Handles both raw field sort {:field f} and ref-based sort {:ref r}."
  [sort-config aggregations]
  (if (:ref sort-config)
    (-resolve-ref aggregations (:ref sort-config))
    (-field->col (:field sort-config))))

(defn- -build-columns-metadata
  "Build columns metadata mapping aggregation refs to row keys.
   Returns vector of {:ref id :key alias-string}."
  [aggregations]
  (mapv (fn [{:keys [id field function]}]
          {:ref id
           :key (-format-agg-alias function field)})
        aggregations))

(defn- -build-having-clause
  "Recursively build a HoneySQL HAVING clause from a having expression.
   Handles simple comparisons and compound AND/OR expressions."
  [aggregations having]
  (cond
    (:and having)
    (into [:and] (map #(-build-having-clause aggregations %) (:and having)))

    (:or having)
    (into [:or] (map #(-build-having-clause aggregations %) (:or having)))

    :else
    (let [agg-col (-resolve-ref aggregations (:ref having))
          sql-op (keyword (:op having))]
      [sql-op agg-col (:value having)])))

(defn- -add-having
  "Add HAVING clause to query if present.
   Resolves refs to aggregation SQL aliases. Supports AND/OR composition."
  [hsql-query {:keys [having aggregations]}]
  (if having
    (assoc hsql-query :having (-build-having-clause aggregations having))
    hsql-query))

(defn- -add-order-and-limit
  "Add ORDER BY and LIMIT to query.
   Uses explicit sort if provided, otherwise defaults based on query type."
  [hsql-query {:keys [limit aggregations visualization]}]
  (let [limit (or limit default-limit)
        sort-config (:sort visualization)
        has-aggregations? (seq aggregations)
        order-by (cond
                   ;; Explicit sort provided
                   sort-config
                   [[(-resolve-sort-col sort-config aggregations)
                     (keyword (:order sort-config))]]
                   ;; No aggregations: stable pagination order
                   (not has-aggregations?)
                   [[:timestamp :desc] [:id :desc]]
                   ;; Aggregations without sort: no default order
                   :else nil)]
    (cond-> hsql-query
      order-by (assoc :order-by order-by)
      true (assoc :limit limit))))

(defn- -add-cursor-filter
  "Add cursor-based pagination filter to query.
   Cursor format: {f: field, v: value, id: snowflake_id}
   Sort order (from query) determines comparison direction:
   - desc: get rows with smaller values
   - asc: get rows with larger values"
  [hsql-query cursor-str sort-order]
  (if-let [{:keys [f v id]} (when cursor-str (query-cursor/decode cursor-str))]
    (let [col (-field->col f)
          ;; For timestamp field, convert epoch-ms to timestamp
          ;; Coerce to long since EPOCH_MS expects integer and timestamps are stored as doubles
          v (if (= "timestamp" f) (-epoch-ms->timestamp (long v)) v)
          ;; desc = get smaller values, asc = get larger values
          op (if (= "asc" sort-order) :> :<)]
      (update hsql-query :where conj
              [:or
               [op col v]
               [:and
                [:= col v]
                [op :id id]]]))
    hsql-query))

;; ---------------------------------------------------------
;; Query Execution

(defn- -resolve-sort-field-name
  "Resolve the sort field name string for cursor encoding.
   For ref-based sort, returns the aggregation alias string.
   For field-based sort, returns the field name directly."
  [sort-config aggregations]
  (if-let [ref (:ref sort-config)]
    (when-let [agg (first (filter #(= ref (:id %)) aggregations))]
      (-format-agg-alias (:function agg) (:field agg)))
    (:field sort-config)))

(defn- -execute-table
  "Execute a table visualization query.
   Supports cursor-based pagination for non-aggregated queries."
  [duckdb {:keys [cursor limit visualization aggregations] :as query}]
  (let [limit (or limit default-limit)
        sort-config (:sort visualization)
        sort-field (or (-resolve-sort-field-name sort-config aggregations) "timestamp")
        sort-order (or (:order sort-config) "desc")
        ;; Fetch one extra row to detect if there are more results
        fetch-limit (inc limit)
        hsql-query (-> (-build-base-query query)
                       (-add-filter (:filter query))
                       (-add-cursor-filter cursor sort-order)
                       (-add-aggregations query)
                       (-add-having query)
                       (-add-order-and-limit (assoc query :limit fetch-limit)))
        _ (def x hsql-query)
        [sql-str & params] (sql/format hsql-query {:dialect :ansi})
        all-rows (jdbc/execute! duckdb (into [sql-str] params))
        has-more? (> (count all-rows) limit)
        rows (if has-more? (take limit all-rows) all-rows)
        columns (when (seq aggregations)
                  (-build-columns-metadata aggregations))
        ;; Build next cursor from last row if there are more results
        next-cursor (when (and has-more? (seq rows))
                      (let [last-row (last rows)
                            id (:id last-row)
                            sort-col (keyword sort-field)
                            sort-value (get last-row sort-col)
                            ;; Coerce timestamp to long (stored as double with sub-ms precision)
                            sort-value (if (and (= "timestamp" sort-field) sort-value)
                                         (long sort-value)
                                         sort-value)]
                        (when (and sort-value id)
                          (query-cursor/encode {:f sort-field :v sort-value :id id}))))]
    (cond-> {:rows (vec rows)
             :total_count (count rows)
             :has_more has-more?
             :next_cursor next-cursor}
      columns (assoc :columns columns))))

(def ^:private -bucket-ms->interval query-util/bucket-ms->interval)

(defn- -build-time-series-query
  "Build HoneySQL query for time series visualization.
   Auto-injects time bucketing; user's group_by fields become series labels.
   Supports HAVING for post-aggregation filtering (used by alert rules)."
  [{:keys [time_range filter aggregations group_by having bucket-ms]}]
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
        (-add-filter filter)
        (-add-having {:having having :aggregations aggregations}))))

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
             ;; DuckLake has some weird bug, if I reorder these few lines it crashes or spitting out wrong result!
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
  (def ds (:db/duckdb-reader system))

  ;; Table query (timestamps in Unix epoch milliseconds)
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :limit 100
            :visualization {:type "table"}})
  ;; => {:data {:rows [...] :total_count 100 :has_more true
  ;;            :next_cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="}
  ;;     :metadata {:query_time_ms N :has_more true}}

  ;; Paginated query using cursor (default timestamp desc sort)
  ;; Cursor format: {f: "timestamp", v: 1702000000000, id: 12345}
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :limit 100
            :cursor "eyJmIjoidGltZXN0YW1wIiwidiI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
            :visualization {:type "table"}})
  ;; => {:data {:rows [...] :total_count 100 :has_more false :next_cursor nil}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Paginated query with custom sort
  ;; Cursor format: {f: "service", v: "api-gateway", id: 12345}
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :limit 100
            :cursor "eyJmIjoic2VydmljZSIsInYiOiJhcGktZ2F0ZXdheSIsImlkIjoxMjM0NX0="
            :visualization {:type "table" :sort {:field "service" :order "asc"}}})
  ;; => {:data {:rows [...] :total_count 100 :has_more true
  ;;            :next_cursor "eyJmIjoic2VydmljZSIsInYiOiJ3ZWIiLCJpZCI6Njc4OTB9"}
  ;;     :metadata {:query_time_ms N :has_more true}}

  ;; Table query with aggregation sorted by ref
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:id "A" :field "*" :function "count"}]
            :group_by ["service"]
            :visualization {:type "table" :sort {:ref "A" :order "desc"}}})
  ;; => {:data {:columns [{:ref "A" :key "count(*)"}]
  ;;            :rows [{:service "api" :count(*) 500}
  ;;                   {:service "web" :count(*) 300} ...]
  ;;            :total_count N :has_more false :next_cursor nil}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Table query with having (for alerting)
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:id "A" :field "*" :function "count"}]
            :group_by ["service"]
            :having {:ref "A" :op ">" :value 100}
            :visualization {:type "table"}})
  ;; => {:data {:columns [{:ref "A" :key "count(*)"}]
  ;;            :rows [{:service "api" :count(*) 500}]  ;; only groups with count > 100
  ;;            :total_count 1 :has_more false :next_cursor nil}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Having with AND composition (count > 10 AND avg(duration) < 500)
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:id "A" :field "*" :function "count"}
                           {:id "B" :field "duration_ms" :function "avg"}]
            :group_by ["service"]
            :having {:and [{:ref "A" :op ">" :value 10}
                           {:ref "B" :op "<" :value 500}]}
            :visualization {:type "table"}})

  ;; Time series query - count events per minute
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:id "A" :field "*" :function "count"}]
            :visualization {:type "time_series" :bucket_ms 60000}})
  ;; => {:data {:bucket_ms 60000
  ;;            :series [{:labels {} :name "count(*)"
  ;;                      :data [{:timestamp N :value N} ...]}]}
  ;;     :metadata {:query_time_ms N :has_more false}}

  ;; Time series with group_by - count per service per minute
  (execute ds
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:id "A" :field "*" :function "count"}]
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

  (ig/halt-key! :db/duckdb-reader ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
