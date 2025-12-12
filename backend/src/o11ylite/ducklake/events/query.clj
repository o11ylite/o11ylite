;; ---------------------------------------------------------
;; o11ylite.ducklake.events.query
;;
;; Event querying: search and retrieval for observability events
;; (spans, span-events, logs).
;;
;; Exports:
;; - validate function for validating query requests
;; - execute function for running queries against DuckDB
;; ---------------------------------------------------------

(ns o11ylite.ducklake.events.query
  (:require
   [o11ylite.ducklake.events.query-schema :as schema]))

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate an events query request.
   Returns nil if valid, or error map with :error key if invalid."
  [query]
  (schema/validate schema/events-query query))

;; ---------------------------------------------------------
;; Query Execution (Stubs)

(defn- -execute-table
  "Execute a table visualization query."
  [_duckdb {:keys [visualization]}]
  (let [limit (or (:limit visualization) 200)]
    {:rows []
     :total_count 0
     :truncated (> 0 limit)}))

(defn- -execute-time-series
  "Execute a time series visualization query."
  [_duckdb {:keys [time_range visualization]}]
  (let [bucket_ms (:bucket_ms visualization)
        range-ms (- (:end time_range) (:start time_range))
        actual-bucket-ms (or bucket_ms (max 1000 (quot range-ms 100)))]
    {:bucket_ms actual-bucket-ms
     :series []}))

(defn- -execute-heatmap
  "Execute a heatmap visualization query."
  [_duckdb _query]
  {:x_buckets []
   :y_buckets []
   :values []})

(defn- -execute-trace
  "Execute a trace visualization query."
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

  ;; Example: table query
  (execute nil
           {:time_range {:start 1702000000000 :end 1702003600000}
            :visualization {:type "table" :limit 100}})

  ;; Example: time series query
  (execute nil
           {:time_range {:start 1702000000000 :end 1702003600000}
            :aggregations [{:field "*" :function "count"}]
            :visualization {:type "time_series"}})

  ;; Validate before execute
  (let [query {:time_range {:start 1702000000000 :end 1702003600000}
               :visualization {:type "table"}}]
    (if-let [error (validate query)]
      error
      (execute nil query)))

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
