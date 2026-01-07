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
  "Unix epoch timestamp in milliseconds."
  [:int {:min 0}])

(def field-name
  "Valid field name for queries.
   Allows alphanumeric, underscores, dashes, and dots (for nested attributes like attr.http.method).
   Must start with a letter or underscore.
   Prevents SQL injection by rejecting special characters."
  [:and
   :string
   [:re {:error/message "field name must contain only letters, numbers, underscores, dashes, and dots"}
    #"^[a-zA-Z_][a-zA-Z0-9_.\-]*$"]])

(def time-range
  "Time range with start/end as Unix epoch milliseconds."
  [:map
   [:start timestamp]
   [:end timestamp]])

;; ---------------------------------------------------------
;; Filter Schemas

(def filter-op
  [:enum "=" "!=" ">" "<" ">=" "<=" "contains" "exists"])

(def simple-filter
  [:map
   [:field field-name]
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

(def aggregation-field
  "Field name for aggregations. Same as field-name but also allows '*' for count(*)."
  [:or [:= "*"] field-name])

(def aggregation
  [:map
   [:field aggregation-field]
   [:function aggregation-function]])

;; ---------------------------------------------------------
;; Visualization Schemas

(def sort-order
  [:enum "asc" "desc"])

(def sort-config
  [:map
   [:field field-name]
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

;; DEFERRED: Heatmap visualization is deferred to post-v1.
;; Decision: Heatmap should be a "smart visualization" - when user selects heatmap,
;; the UI shows a simplified "Distribution of: [field]" selector instead of the
;; full aggregation builder. Backend will handle histogram bucketing internally.
;; The current schema expects group_by to specify the field, but this may change
;; to visualization.field when implemented.
;; See: https://github.com/o11ylite/o11ylite/discussions/xxx (architecture decision)
(def heatmap-visualization
  [:map
   [:type [:= "heatmap"]]
   [:y_buckets {:optional true} [:int {:min 1 :max 200}]]])

;; Trace visualization: Part of v1, accessed via dedicated /trace/:id page.
;; Uses /api/query/events with visualization: {type: "trace"} and
;; filter: {field: "trace_id", op: "=", value: "<id>"}.
;; Users click trace_id links in table results to navigate to the trace page.
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
   [:group_by {:optional true} [:vector field-name]]
   [:having {:optional true} filter-expr]
   [:visualization visualization]])

(defn- -valid-heatmap-group-by?
  "Heatmap requires exactly one group_by field for Y-axis bucketing."
  [{:keys [visualization group_by]}]
  (if (= "heatmap" (:type visualization))
    (= 1 (count group_by))
    true))

(defn- -valid-time-series-aggregation?
  "Time series requires at least one aggregation for Y-axis values."
  [{:keys [visualization aggregations]}]
  (if (= "time_series" (:type visualization))
    (seq aggregations)
    true))

(defn- -valid-trace-filter?
  "Trace visualization requires a simple trace_id = X filter at the top level."
  [{:keys [visualization filter]}]
  (if (= "trace" (:type visualization))
    (and (map? filter)
         (= "trace_id" (:field filter))
         (= "=" (:op filter))
         (some? (:value filter)))
    true))

(def events-query
  "Schema for events query requests."
  [:and
   base-query
   [:fn {:error/message "heatmap requires exactly one group_by field"}
    -valid-heatmap-group-by?]
   [:fn {:error/message "time_series requires at least one aggregation"}
    -valid-time-series-aggregation?]
   [:fn {:error/message "trace requires a trace_id = <value> filter"}
    -valid-trace-filter?]])

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

  ;; Valid table query (timestamps in Unix epoch milliseconds)
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :visualization {:type "table" :limit 100}})
  ;; => nil

  ;; Missing time_range
  (validate events-query
            {:visualization {:type "table"}})
  ;; => {:error {:time_range ["missing required key"]}}

  ;; Heatmap without group_by
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :visualization {:type "heatmap"}})
  ;; => {:error ["heatmap requires exactly one group_by field"]}

  ;; Valid heatmap
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :group_by ["duration_ms"]
             :visualization {:type "heatmap"}})
  ;; => nil

  ;; Valid field with dots (attribute fields)
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :filter {:field "attr.http.method" :op "=" :value "GET"}
             :visualization {:type "table"}})
  ;; => nil

  ;; Invalid field name (SQL injection attempt)
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :filter {:field "service; DROP TABLE events;" :op "=" :value "x"}
             :visualization {:type "table"}})
  ;; => {:error {:filter {:field ["field name must contain only letters, numbers, underscores, and dots"]}}}

  ;; Trace without trace_id filter
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :visualization {:type "trace"}})
  ;; => {:error ["trace requires a trace_id = <value> filter"]}

  ;; Valid trace query
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :filter {:field "trace_id" :op "=" :value "abc123"}
             :visualization {:type "trace"}})
  ;; => nil

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
