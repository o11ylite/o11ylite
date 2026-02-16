;; ---------------------------------------------------------
;; o11ylite.notebook.schema
;;
;; Malli schemas for notebook and cell validation.
;; Cell query schemas are derived from the parent API schemas
;; (events/metrics) with notebook-specific rules — e.g. limit
;; is allowed (unlike alert rules which strip it).
;; ---------------------------------------------------------

(ns o11ylite.notebook.schema
  (:require
    [malli.core :as m]
    [malli.error :as me]
    [malli.util :as mu]
    [o11ylite.store.events.query-schema :as events-schema]
    [o11ylite.store.metrics.query-schema :as metrics-schema]))

;; ---------------------------------------------------------
;; Schema derivation helpers

(defn- -base-map
  "Extract the :map child from an [:and :map :fn ...] composite schema."
  [and-schema]
  (first (m/children and-schema)))

(defn- -fn-validators
  "Select :fn validators from an [:and ...] schema by error message."
  [and-schema messages]
  (let [msg-set (set messages)]
    (filterv #(and (= :fn (m/type %))
                   (contains? msg-set (:error/message (m/properties %))))
             (m/children and-schema))))

(defn- -derive-query-schema
  "Derive a notebook cell query schema from a parent [:and :map :fn ...] schema.
   Removes specified keys, closes the map, and keeps only the listed :fn validators."
  [parent-schema dissoc-keys keep-messages]
  (let [base (reduce mu/dissoc (-base-map parent-schema) dissoc-keys)
        closed (mu/closed-schema base)
        fns (-fn-validators parent-schema keep-messages)]
    (into [:and closed] fns)))

;; ---------------------------------------------------------
;; Cell Query Schemas (derived from parent API schemas)

(def events-query
  "Notebook cell query schema for events mode.
   Derived from events-schema/events-query minus runtime keys.
   Unlike alert rules, notebooks keep :limit (user-configurable per cell)."
  (-derive-query-schema
    events-schema/events-query
    [:time_range :cursor]
    #{"having requires aggregations"
      "having ref must reference an existing aggregation ID"
      "aggregation IDs must be unique"
      "sort ref must reference an existing aggregation ID"
      "time_series requires at least one aggregation"}))

(def metrics-query
  "Notebook cell query schema for metrics mode.
   Derived from metrics-schema/metrics-query minus runtime keys."
  (-derive-query-schema
    metrics-schema/metrics-query
    [:time_range]
    #{"metric IDs must be unique"
      "having ref must reference an existing metric ID"}))

;; ---------------------------------------------------------
;; Notebook Schema

(def notebook
  "Schema for notebook create/update requests."
  [:map {:closed true}
   [:name [:string {:min 1, :max 255}]]
   [:description {:optional true} [:maybe :string]]
   [:global_from [:string {:min 1}]]
   [:global_to [:string {:min 1}]]])

;; ---------------------------------------------------------
;; Cell Schema

(def cell
  "Schema for cell create/update requests."
  [:map {:closed true}
   [:title {:optional true} [:maybe :string]]
   [:query_mode [:enum "events" "metrics"]]
   [:query :any]
   [:pinned_from {:optional true} [:maybe :string]]
   [:pinned_to {:optional true} [:maybe :string]]])

;; ---------------------------------------------------------
;; Validation

(defn validate-notebook
  "Validate notebook params.
   Returns nil if valid, or {:error ...} if invalid."
  [params]
  (when-not (m/validate notebook params)
    {:error (me/humanize (m/explain notebook params))}))

(defn validate-cell
  "Validate cell params (including query based on query_mode).
   Returns nil if valid, or {:error ...} if invalid."
  [params]
  (let [cell-error (when-not (m/validate cell params)
                     {:error (me/humanize (m/explain cell params))})]
    (if cell-error
      cell-error
      (let [query-schema (case (:query_mode params)
                           "events" events-query
                           "metrics" metrics-query)]
        (when-not (m/validate query-schema (:query params))
          {:error {:query (me/humanize (m/explain query-schema (:query params)))}})))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Valid notebook
  (validate-notebook {:name "Debug session"
                      :description "Investigating latency"
                      :global_from "now-1h"
                      :global_to "now"})
  ;; => nil

  ;; Invalid: missing name
  (validate-notebook {:global_from "now-1h" :global_to "now"})
  ;; => {:error {:name ["missing required key"]}}

  ;; Valid cell (events with limit — notebooks allow limit)
  (validate-cell {:query_mode "events"
                  :query {:visualization {:type "table"}
                          :limit 50}})
  ;; => nil

  ;; Valid cell (events without limit)
  (validate-cell {:query_mode "events"
                  :query {:visualization {:type "table"}}})
  ;; => nil

  ;; Invalid: bad query_mode
  (validate-cell {:query_mode "invalid"
                  :query {:visualization {:type "table"}}})
  ;; => {:error {:query_mode [...]}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
