;; ---------------------------------------------------------
;; o11ylite.store.metrics.query
;;
;; Metrics querying: retrieval and aggregation for time-series data.
;;
;; Key differences from events query:
;;   - Metric name is required (queries specific metrics, not all data)
;;   - Aggregation depends on metric type (gauge/sum/histogram)
;;   - Results are always time-series format (bucketed)
;;   - Supports multiple metrics in one query (for future formula support)
;;
;; V1 Scope:
;;   - Single or multiple metrics with aggregations
;;   - Global filters + per-metric filters
;;   - Shared group-by across metrics
;;   - Auto or manual time bucketing
;;   - Optional formulas computed over the resulting series (A / B * 100)
;;
;; Deferred:
;;   - Histogram percentiles (p50, p99)
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query
  (:require
    [honey.sql :as sql]
    [next.jdbc :as jdbc]
    [o11ylite.store.metrics.formula :as formula]
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.store.metrics.query-schema :as query-schema]
    [o11ylite.store.metrics.query-validation :as query-validation]
    [o11ylite.store.query-util :as query-util]))

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate a metrics query request.
   Performs schema validation, then metadata-aware validation.
   Returns nil if valid, or {:error ...} if invalid."
  [sqlite query]
  (or (query-schema/validate query-schema/metrics-query query)
      (query-validation/validate-with-metadata sqlite query)))


;; ---------------------------------------------------------
;; Query Building

(defn- -build-histogram-agg-expr
  "Build aggregation expression for histogram metrics.
   Histograms store distribution data in hist.* columns:
     - hist.count: total observation count
     - hist.sum: sum of all observed values
     - hist.min: minimum observed value
     - hist.max: maximum observed value
   
   TODO: Histogram percentiles (p50, p90, p99)
   Requires fetching bucket boundaries from metadata (hist_boundaries in SQLite)
   and implementing linear interpolation within buckets, or using DuckDB's
   approx_quantile on an expanded representation."
  [agg]
  (case agg
    "count" [:sum [:raw "\"hist.count\""]]
    "sum"   [:sum [:raw "\"hist.sum\""]]
    "avg"   [:/ [:sum [:raw "\"hist.sum\""]] [:sum [:raw "\"hist.count\""]]]
    "min"   [:min [:raw "\"hist.min\""]]
    "max"   [:max [:raw "\"hist.max\""]]))

(defn- -build-gauge-sum-agg-expr
  "Build aggregation expression for gauge/sum metrics.
   Aggregates on the `value` column.
   Rate is sum divided by bucket seconds."
  [agg bucket-ms]
  (let [bucket-seconds (quot bucket-ms 1000)]
    (case agg
      "sum" [:sum :value]
      "avg" [:avg :value]
      "min" [:min :value]
      "max" [:max :value]
      "last" [:last :value]
      "rate" [:/ [:sum :value] bucket-seconds]
      "count" [:count :*])))

(defn- -build-agg-expr
  "Build aggregation expression for a metric based on its type.
   Dispatches to histogram-specific or gauge/sum logic."
  [metric-type agg bucket-ms]
  (if (= metric-type :histogram)
    (-build-histogram-agg-expr agg)
    (-build-gauge-sum-agg-expr agg bucket-ms)))

(defn- -merge-filters
  "Merge global filter with per-metric filter using AND.
   Returns nil if both are nil."
  [global-filter metric-filter]
  (cond
    (and global-filter metric-filter)
    {:and [global-filter metric-filter]}

    global-filter global-filter
    metric-filter metric-filter
    :else nil))

(defn- -build-metric-query
  "Build HoneySQL query for a single metric.
   Returns a query that produces time-bucketed, aggregated results.

   Supports HAVING for post-aggregation filtering (applied only when the
   having clause references this metric's ID)."
  [{:keys [time_range filter group_by having]} metric metric-type bucket-ms]
  (let [{metric-name :name metric-id :id agg :agg metric-filter :filter} metric
        merged-filter (-merge-filters filter metric-filter)
        ;; Apply HAVING only if it references this metric's ID
        metric-having (when (and having (= metric-id (:ref having)))
                        having)
        bucket-interval (query-util/bucket-ms->interval bucket-ms)
        time-bucket-expr [:time_bucket bucket-interval :timestamp]
        bucket-epoch-expr [:epoch_ms time-bucket-expr]
        bucket-expr [bucket-epoch-expr :bucket]
        agg-expr [(-build-agg-expr metric-type agg bucket-ms) :value]
        group-by-fields (or group_by [])
        group-cols (map query-util/field->col group-by-fields)
        group-select (map (fn [field col] [col (keyword field)]) group-by-fields group-cols)
        ;; Select: bucket, group_by fields, aggregation
        select-clause (into [bucket-expr] (concat group-select [agg-expr]))
        group-by-clause (into [:bucket] group-cols)
        ;; Base query
        base-query {:select (vec select-clause)
                    :from [:o11ylite.metrics]
                    :where [:and
                            [:= :name metric-name]
                            [:>= :timestamp (query-util/epoch-ms->timestamp (:start time_range))]
                            [:< :timestamp (query-util/epoch-ms->timestamp (:end time_range))]]
                    :group-by group-by-clause
                    :order-by [[:bucket :asc]]}]
    (cond-> base-query
      merged-filter (update :where conj (query-util/build-filter-clause merged-filter))
      metric-having (assoc :having [(keyword (:op metric-having))
                                    :value
                                    (:value metric-having)]))))

(defn- -format-series-name
  "Format series name as agg(metric), e.g., avg(cpu.utilization).
   Matches the events query format for UI consistency."
  [agg metric-name]
  (str agg "(" metric-name ")"))

(defn- -rows->series
  "Transform query result rows into series format for a single metric.
   Creates one series per unique label combination.

   Rows use the original field names as column aliases (e.g., :attr.k8s.pod.name),
   matching the events query approach."
  [rows metric-id metric-name metric-agg group-by-fields]
  (let [label-keys (map keyword group-by-fields)
        grouped (group-by #(select-keys % label-keys) rows)
        series-name (-format-series-name metric-agg metric-name)]
    (for [[labels rows-for-series] grouped]
      {:id metric-id
       :metric metric-name
       :name series-name
       :labels labels
       :data (vec (for [row rows-for-series]
                    {:timestamp (:bucket row)
                     :value (:value row)}))})))

(defn- -column-not-found?
  "Check if exception is a DuckDB column-not-found error."
  [e]
  (and (instance? java.sql.SQLException e)
       (some-> (.getMessage e) (.contains "not found in FROM clause"))))

(defn- -execute-metric
  "Execute query for a single metric and return its series and unit.
   Looks up metric type from metadata to select appropriate aggregation columns.
   Returns empty series if referenced columns don't exist (graceful degradation).

   Returns {:series [...] :unit \"By\"|nil}"
  [duckdb sqlite query metric bucket-ms]
  (let [{:keys [id name agg]} metric
        ;; Lookup metric metadata; default to :gauge for unknown metrics (graceful degradation)
        metric-meta (metadata/get-metric sqlite name)
        metric-type (or (:metric_type metric-meta) :gauge)
        hsql-query (-build-metric-query query metric metric-type bucket-ms)
        [sql-str & params] (sql/format hsql-query {:dialect :ansi})
        group-by-fields (or (:group_by query) [])]
    (try
      (let [rows (jdbc/execute! duckdb (into [sql-str] params))]
        {:series (-rows->series rows id name agg group-by-fields)
         :unit (:unit metric-meta)})
      (catch java.sql.SQLException e
        (if (-column-not-found? e)
          ;; Return empty series for non-existent columns
          {:series [] :unit (:unit metric-meta)}
          (throw e))))))

;; ---------------------------------------------------------
;; Query Execution

(defn execute
  "Execute a metrics query.
   Assumes query has already been validated against metrics-query schema.

   Arguments:
     duckdb - DuckDB datasource
     sqlite - SQLite datasource (for metric metadata lookups to determine type)
     query  - Validated query map

    Returns:
     {:data {:bucket_ms N
             :start_ms N
             :end_ms N
             :series [{:id \"A\"
                       :metric \"cpu.utilization\"
                       :name \"avg(cpu.utilization)\"
                       :labels {:attr.host.name \"server-1\"}
                       :data [{:timestamp N :value N} ...]}
                      ;; If :formulas were supplied, synthetic series are
                      ;; appended after metric series. They carry :id (e.g.
                      ;; \"F1\"), :metric nil, :formula <expr>, :name (\"F1\"
                      ;; or \"F1: <user-name>\"), and a (possibly :unit) field.
                      ...]
             :units {\"cpu.utilization\" \"%\"
                     ;; Formula units appear keyed as \"F1\" or \"F1: name\"
                     \"network.io\" \"By\"}}
      :metadata {:query_time_ms N}}"
  [duckdb sqlite query]
  (let [start-time (System/currentTimeMillis)
        {:keys [time_range bucket_ms metrics]} query
        range-ms (- (:end time_range) (:start time_range))
        resolved-bucket-ms (or bucket_ms (query-util/select-bucket-ms range-ms))
        start-ms (query-util/align-to-bucket (:start time_range) resolved-bucket-ms)
        end-ms (query-util/align-to-bucket (:end time_range) resolved-bucket-ms)
        ;; Execute query for each metric, collecting series and units
        results (map #(-execute-metric duckdb sqlite query % resolved-bucket-ms) metrics)
        raw-series (vec (mapcat :series results))
        formulas (or (:formulas query) [])
        ;; Append synthetic formula series (one per formula × matching label combo)
        all-series (formula/apply-formulas raw-series formulas)
        metric-units (into {} (map (fn [metric result]
                                     [(:name metric) (:unit result)])
                                   metrics results))
        formula-units (into {} (for [f formulas
                                     :when (:unit f)]
                                 [(if (:name f) (str (:id f) ": " (:name f)) (:id f))
                                  (:unit f)]))
        units (merge metric-units formula-units)
        query-time-ms (- (System/currentTimeMillis) start-time)]
    {:data {:bucket_ms resolved-bucket-ms
            :start_ms start-ms
            :end_ms end-ms
            :series all-series
            :units units}
     :metadata {:query_time_ms query-time-ms}}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def ds (:db/duckdb system))

  ;; Example: Single metric query
  (def sample-query
    {:time_range {:start 1702000000000 :end 1702003600000}
     :metrics [{:id "A"
                :name "cpu.utilization"
                :agg "avg"}]
     :group_by ["attr.host.name"]})

  ;; Validate (pass nil for sqlite to skip metadata validation)
  (validate nil sample-query)
  ;; => nil

  ;; Execute query (returns empty series when no data)
  (execute ds nil sample-query)
  ;; => {:data {:bucket_ms 60000
  ;;            :start_ms 1702000000000
  ;;            :end_ms 1702003600000
  ;;            :series []}
  ;;     :metadata {:query_time_ms N}}

  ;; Example: Multi-metric query for error rate
  (def error-rate-query
    {:time_range {:start 1702000000000 :end 1702003600000}
     :bucket_ms 60000
     :filter {:field "attr.env" :op "=" :value "prod"}
     :metrics [{:id "A"
                :name "http.server.errors"
                :agg "sum"
                :filter {:field "attr.status_code" :op ">=" :value "500"}}
               {:id "B"
                :name "http.server.requests"
                :agg "sum"}]
     :group_by ["attr.service"]})

  (validate nil error-rate-query)
  ;; => nil

  ;; Build query for inspection (gauge metric)
  (-build-metric-query sample-query (first (:metrics sample-query)) :gauge 60000)
  ;; => {:select [[[:epoch_ms [:time_bucket ...]] :bucket]
  ;;              [[:raw "\"attr.host.name\""] :g0]
  ;;              [[:avg :value] :value]]
  ;;     :from [:o11ylite.metrics]
  ;;     :where [:and
  ;;             [:= :name "cpu.utilization"]
  ;;             [:>= :timestamp [:epoch_ms 1702000000000]]
  ;;             [:< :timestamp [:epoch_ms 1702003600000]]]
  ;;     :group-by [:bucket :g0]
  ;;     :order-by [[:bucket :asc]]}

  ;; Build query for histogram metric
  (-build-metric-query {:time_range {:start 1702000000000 :end 1702003600000}
                        :metrics [{:id "A" :name "http.duration" :agg "avg"}]}
                       {:id "A" :name "http.duration" :agg "avg"}
                       :histogram
                       60000)
  ;; => Uses hist.sum / hist.count for avg aggregation

  ;; Bucket size selection (using shared query-util functions)
  (query-util/select-bucket-ms 3600000)   ;; 1 hour => 60000 (1 min buckets, ~60 buckets)
  (query-util/select-bucket-ms 86400000)  ;; 1 day => 1200000 (20 min buckets, ~72 buckets)
  (query-util/select-bucket-ms 604800000) ;; 1 week => 7200000 (2 hour buckets, ~84 buckets)

  (ig/halt-key! :db/duckdb ds)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
