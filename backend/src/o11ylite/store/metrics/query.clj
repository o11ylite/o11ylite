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
;;
;; Deferred:
;;   - Formula support (A / B * 100)
;;   - Aggregation-type validation against metric metadata
;;   - Histogram percentiles (p50, p99)
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query
  (:require
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
;; Query Execution

(defn execute
  "Execute a metrics query.
   Assumes query has already been validated against metrics-query schema.

   Arguments:
     duckdb - DuckDB datasource
     sqlite - SQLite datasource (for metric metadata lookups)
     query  - Validated query map

   Returns:
     {:data {:bucket_ms N
             :start_ms N
             :end_ms N
             :series [{:id \"A\"
                       :metric \"cpu.utilization\"
                       :labels {:attr.host.name \"server-1\"}
                       :data [{:timestamp N :value N} ...]}
                      ...]}
      :metadata {:query_time_ms N}}"
  [_duckdb _sqlite query]
  (let [start-time (System/currentTimeMillis)
        {:keys [time_range bucket_ms]} query
        range-ms (- (:end time_range) (:start time_range))
        resolved-bucket-ms (or bucket_ms (query-util/select-bucket-ms range-ms))
        start-ms (query-util/align-to-bucket (:start time_range) resolved-bucket-ms)
        end-ms (query-util/align-to-bucket (:end time_range) resolved-bucket-ms)
        query-time-ms (- (System/currentTimeMillis) start-time)]
    ;; TODO: Implement actual query execution
    ;; For each metric in :metrics:
    ;; 1. Build SQL with time_bucket, filters (global + per-metric), group_by
    ;; 2. Apply aggregation based on metric type:
    ;;    - gauge: sum/avg/min/max/last on `value`
    ;;    - sum: sum/rate on `value`
    ;;    - histogram: count/sum/avg/min/max on hist.* columns
    ;; 3. Transform rows to series format
    {:data {:bucket_ms resolved-bucket-ms
            :start_ms start-ms
            :end_ms end-ms
            :series []}
     :metadata {:query_time_ms query-time-ms}}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

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

  ;; Execute (returns stub response)
  (execute nil nil sample-query)
  ;; => {:data {:bucket_ms 60000
  ;;            :start_ms 1702000000000
  ;;            :end_ms 1702003600000
  ;;            :series []}
  ;;     :metadata {:query_time_ms 0}}

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

  ;; Bucket size selection (using shared query-util functions)
  (query-util/select-bucket-ms 3600000)   ;; 1 hour => 60000 (1 min buckets, ~60 buckets)
  (query-util/select-bucket-ms 86400000)  ;; 1 day => 1200000 (20 min buckets, ~72 buckets)
  (query-util/select-bucket-ms 604800000) ;; 1 week => 7200000 (2 hour buckets, ~84 buckets)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
