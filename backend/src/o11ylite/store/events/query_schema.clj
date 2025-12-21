;; ---------------------------------------------------------
;; o11ylite.store.events.query-schema
;;
;; Malli schemas for events query requests.
;; Defines the shape of query API requests and validation.
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-schema
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;; ---------------------------------------------------------
;; Primitive Schemas

(def timestamp
  "Unix epoch timestamp in seconds."
  [:int {:min 0}])

(def time-range
  "Time range with start/end as Unix epoch seconds."
  [:map
   [:start timestamp]
   [:end timestamp]])

;; ---------------------------------------------------------
;; Filter Schemas

(def filter-op
  [:enum "=" "!=" ">" "<" ">=" "<=" "contains" "exists"])

(def simple-filter
  [:map
   [:field :string]
   [:op filter-op]
   [:value :any]])

(def filter-expr
  "Recursive filter expression: simple | {and: [...]} | {or: [...]}."
  [:schema
   {:registry
    {::filter [:or
               simple-filter
               [:map [:and [:vector [:ref ::filter]]]]
               [:map [:or [:vector [:ref ::filter]]]]]}}
   [:ref ::filter]])

;; ---------------------------------------------------------
;; Aggregation Schema

(def aggregation-function
  [:enum "count" "sum" "avg" "min" "max" "p50" "p90" "p99"])

(def aggregation
  [:map
   [:field :string]
   [:function aggregation-function]
   [:alias {:optional true} :string]])

;; ---------------------------------------------------------
;; Visualization Schemas

(def sort-order
  [:enum "asc" "desc"])

(def sort-config
  [:map
   [:field :string]
   [:order sort-order]])

(def table-visualization
  [:map
   [:type [:= "table"]]
   [:limit {:optional true} [:int {:min 1 :max 500}]]
   [:sort {:optional true} sort-config]])

(def time-series-visualization
  [:map
   [:type [:= "time_series"]]
   [:bucket_ms {:optional true} [:int {:min 1}]]
   [:limit_series {:optional true} [:int {:min 1}]]])

(def heatmap-visualization
  [:map
   [:type [:= "heatmap"]]
   [:y_buckets {:optional true} [:int {:min 1 :max 200}]]])

(def trace-visualization
  [:map
   [:type [:= "trace"]]])

(def visualization
  [:or
   table-visualization
   time-series-visualization
   heatmap-visualization
   trace-visualization])

;; ---------------------------------------------------------
;; Events Query Schema

(def ^:private base-query
  "Base schema for events query requests (without cross-field constraints)."
  [:map
   [:time_range time-range]
   [:filter {:optional true} filter-expr]
   [:aggregations {:optional true} [:vector aggregation]]
   [:group_by {:optional true} [:vector :string]]
   [:having {:optional true} filter-expr]
   [:visualization visualization]])

(defn- -valid-heatmap-group-by?
  "Heatmap requires exactly one group_by field for Y-axis bucketing."
  [{:keys [visualization group_by]}]
  (if (= "heatmap" (:type visualization))
    (= 1 (count group_by))
    true))

(def events-query
  "Schema for events query requests."
  [:and
   base-query
   [:fn {:error/message "heatmap requires exactly one group_by field"}
    -valid-heatmap-group-by?]])

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate data against a schema.
   Returns nil if valid, or error map with :error key if invalid."
  [schema data]
  (when-not (m/validate schema data)
    {:error (me/humanize (m/explain schema data))}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Valid table query (timestamps in Unix epoch seconds)
  (validate events-query
            {:time_range {:start 1702000000 :end 1702003600}
             :visualization {:type "table" :limit 100}})
  ;; => nil

  ;; Missing time_range
  (validate events-query
            {:visualization {:type "table"}})
  ;; => {:error {:time_range ["missing required key"]}}

  ;; Heatmap without group_by
  (validate events-query
            {:time_range {:start 1702000000 :end 1702003600}
             :visualization {:type "heatmap"}})
  ;; => {:error ["heatmap requires exactly one group_by field"]}

  ;; Valid heatmap
  (validate events-query
            {:time_range {:start 1702000000 :end 1702003600}
             :group_by ["duration_ms"]
             :visualization {:type "heatmap"}})
  ;; => nil

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
