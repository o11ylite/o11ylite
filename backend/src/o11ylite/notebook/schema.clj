;; ---------------------------------------------------------
;; o11ylite.notebook.schema
;;
;; Malli schemas for notebook and cell validation.
;; Cell query schemas are derived the same way as alert rules:
;; from the full events/metrics query schemas minus runtime keys.
;; ---------------------------------------------------------

(ns o11ylite.notebook.schema
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [o11ylite.alert-rule.schema :as alert-rule-schema]))

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
      ;; Reuse alert-rule query schemas (same shape: query payload minus time_range)
      (let [query-schema (case (:query_mode params)
                           "events" alert-rule-schema/events-query
                           "metrics" alert-rule-schema/metrics-query)]
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

  ;; Valid cell
  (validate-cell {:query_mode "events"
                  :query {:visualization {:type "table"}}})
  ;; => nil

  ;; Invalid: bad query_mode
  (validate-cell {:query_mode "invalid"
                  :query {:visualization {:type "table"}}})
  ;; => {:error {:query_mode [...]}}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
