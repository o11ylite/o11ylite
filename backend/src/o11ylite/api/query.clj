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
   [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-events-handler
  "Create the events query handler with duckdb dependency."
  [duckdb]
  (fn [request]
    (let [query (:body request)]
      (if-let [error (events.query/validate query)]
        (response/json 400 error)
        (response/json 200 (events.query/execute duckdb query))))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Query API routes."
  [{:keys [duckdb]}]
  [["/query"
    ["/events" {:post {:handler (-make-events-handler duckdb)}}]]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example events query - table view
  {:time_range {:start 1702000000000
                :end 1702003600000}
   :filter {:field "service"
            :op "="
            :value "api-gateway"}
   :visualization {:type "table"
                   :limit 100
                   :sort {:field "timestamp" :order "desc"}}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
