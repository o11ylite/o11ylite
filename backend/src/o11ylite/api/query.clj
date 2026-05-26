;; ---------------------------------------------------------
;; o11ylite.api.query
;;
;; Query API endpoints for events and metrics.
;; Handles HTTP concerns: validation, request/response formatting.
;; Delegates query execution to store layer.
;; ---------------------------------------------------------

(ns o11ylite.api.query
  (:require
    [o11ylite.store.events.query :as events.query]
    [o11ylite.store.metrics.query :as metrics.query]
    [o11ylite.store.query-util :as query-util]
    [o11ylite.store.schema :as schema]
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-events-handler
  "Create the events query handler with duckdb dependency."
  [duckdb]
  (fn [request]
    (let [query (:body request)
          fields (schema/fetch-event-fields duckdb)]
      (if-let [error (events.query/validate fields query)]
        (response/json 400 error)
        (let [query (query-util/normalize-filter fields query)]
          (response/json 200 (events.query/execute duckdb query)))))))

(defn- -make-metrics-handler
  "Create the metrics query handler with duckdb and sqlite dependencies."
  [duckdb sqlite]
  (fn [request]
    (let [query (:body request)]
      (if-let [error (metrics.query/validate sqlite query)]
        (response/json 400 error)
        (response/json 200 (metrics.query/execute duckdb sqlite query))))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Query API routes."
  [{:keys [duckdb sqlite]}]
  [["/query"
    ["/events" {:post {:handler (-make-events-handler duckdb)}}]
    ["/metrics" {:post {:handler (-make-metrics-handler duckdb sqlite)}}]]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example events query - table view
  {:time_range {:start 1702000000000
                :end 1702003600000}
   :filter {:field "service"
            :op "="
            :value "api-gateway"}
   :limit 100
   :visualization {:type "table"
                   :sort {:field "timestamp" :order "desc"}}}

  ;; Example metrics query - CPU utilization by host
  {:time_range {:start 1702000000000
                :end 1702003600000}
   :metrics [{:id "A"
              :name "cpu.utilization"
              :agg "avg"}]
   :group_by ["attr.host.name"]}

  ;; Example metrics query - error rate setup
  {:time_range {:start 1702000000000
                :end 1702003600000}
   :bucket_ms 60000
   :filter {:field "attr.env" :op "=" :value "prod"}
   :metrics [{:id "A"
              :name "http.server.errors"
              :agg "sum"
              :filter {:field "attr.status_code" :op ">=" :value "500"}}
             {:id "B"
              :name "http.server.requests"
              :agg "sum"}]
   :group_by ["attr.service"]}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
