;; ---------------------------------------------------------
;; o11ylite.store.events.query-schema
;;
;; Malli schemas for events query requests.
;; Defines the shape of query API requests and validation.
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-schema
  (:require
    [clojure.string :as str]
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
  [:map {:closed true}
   [:start timestamp]
   [:end timestamp]])

;; ---------------------------------------------------------
;; Ref Schema

(def ref-id
  "Single uppercase letter A-Z for referencing aggregations.
   Same format as metrics query IDs for consistency."
  [:re {:error/message "ref must be a single uppercase letter A-Z"}
   #"^[A-Z]$"])

;; ---------------------------------------------------------
;; Filter Schemas

(def filter-op
  [:enum "=" "!=" ">" "<" ">=" "<=" "contains" "exists" "starts-with"])

(def simple-filter
  [:map {:closed true}
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
;; Having Schema

(def having-op
  "Numeric comparison operators for post-aggregation filtering."
  [:enum ">" "<" ">=" "<=" "=" "!="])

(def simple-having
  "Single post-aggregation comparison on an aggregation ref."
  [:map {:closed true}
   [:ref ref-id]
   [:op having-op]
   [:value number?]])

(def having-expr
  "Recursive having expression: simple | {and: [...]} | {or: [...]}.
   Mirrors filter-expr composition for consistent DSL."
  [:schema
   {:registry
    {::having [:or
               simple-having
               [:map [:and [:vector [:ref ::having]]]]
               [:map [:or [:vector [:ref ::having]]]]]}}
   [:ref ::having]])

;; ---------------------------------------------------------
;; Aggregation Schema

(def aggregation-function
  [:enum "count" "sum" "avg" "min" "max" "p50" "p90" "p99"])

(def aggregation-field
  "Field name for aggregations. Same as field-name but also allows '*' for count(*)."
  [:or [:= "*"] field-name])

(def aggregation
  [:map {:closed true}
   [:id ref-id]
   [:field aggregation-field]
   [:function aggregation-function]])

;; ---------------------------------------------------------
;; Visualization Schemas

(def sort-order
  [:enum "asc" "desc"])

(def sort-config
  "Sort configuration: either by raw field or by aggregation ref.
   Use :field for raw columns, :ref for aggregation results."
  [:or
   [:map
    [:field field-name]
    [:order sort-order]]
   [:map
    [:ref ref-id]
    [:order sort-order]]])

(def table-visualization
  [:map {:closed true}
   [:type [:= "table"]]
   [:sort {:optional true} sort-config]
   [:displayed_fields {:optional true} [:vector field-name]]])

(def time-series-visualization
  [:map {:closed true}
   [:type [:= "time_series"]]
   [:bucket_ms {:optional true} [:int {:min 1}]]
   [:overlay {:optional true} :boolean]
   [:render_as {:optional true} [:enum "line" "stacked_area" "bar"]]])

;; DEFERRED: Heatmap visualization is deferred to post-v1.
;; Decision: Heatmap should be a "smart visualization" - when user selects heatmap,
;; the UI shows a simplified "Distribution of: [field]" selector instead of the
;; full aggregation builder. Backend will handle histogram bucketing internally.
;; The current schema expects group_by to specify the field, but this may change
;; to visualization.field when implemented.
;; See: https://github.com/o11ylite/o11ylite/discussions/xxx (architecture decision)
(def heatmap-visualization
  [:map {:closed true}
   [:type [:= "heatmap"]]
   [:y_buckets {:optional true} [:int {:min 1 :max 200}]]])

;; Trace visualization: Part of v1, accessed via dedicated /trace/:id page.
;; Uses /api/query/events with visualization: {type: "trace"} and
;; filter: {field: "trace_id", op: "=", value: "<id>"}.
;; Users click trace_id links in table results to navigate to the trace page.
(def trace-visualization
  [:map {:closed true}
   [:type [:= "trace"]]])

(def visualization
  [:or
   table-visualization
   time-series-visualization
   heatmap-visualization
   trace-visualization])

;; ---------------------------------------------------------
;; Cursor Schema

(def cursor
  "Pagination cursor for table queries.
   Base64-encoded JSON containing sort field, value, and id for keyset pagination.
   Format: base64({\"f\": <field>, \"v\": <value>, \"id\": <snowflake_id>})
   For default timestamp sorting, f is \"timestamp\".
   Accepts nil (or null in JSON) for first page."
  [:maybe [:string {:min 1}]])

;; ---------------------------------------------------------
;; Type-Aware Filter Validation

(def valid-ops-by-type
  "Valid filter operators for each field type."
  {:string    #{"=" "!=" "contains" "exists" "starts-with"}
   :integer   #{"=" "!=" ">" "<" ">=" "<=" "exists"}
   :float     #{"=" "!=" ">" "<" ">=" "<=" "exists"}
   :boolean   #{"=" "!=" "exists"}
   :instant   #{"=" "!=" ">" "<" ">=" "<=" "exists"}})

(defn- -validate-filter-op-for-type
  "Validate that an operator is valid for the given field type.
   Returns nil if valid, error map if invalid."
  [field-op field-type field-name]
  (let [valid-ops (get valid-ops-by-type field-type)]
    (if-not (contains? valid-ops field-op)
      {:error (format "operator '%s' is not valid for %s field '%s'. Valid operators: %s"
                      field-op field-type field-name (str/join ", " (sort valid-ops)))}
      nil)))

(defn- -validate-filter-expr-with-metadata
  "Recursively validate filter expression operators against field types.
   Returns nil if valid, error map if invalid.
   Unknown fields are skipped (allows querying before data exists)."
  [events-schema filter-expr]
  (cond
    ;; Compound AND
    (:and filter-expr)
    (some #(-validate-filter-expr-with-metadata events-schema %)
          (:and filter-expr))

    ;; Compound OR
    (:or filter-expr)
    (some #(-validate-filter-expr-with-metadata events-schema %)
          (:or filter-expr))

    ;; Simple filter
    :else
    (when-let [field-meta (get events-schema (keyword (:field filter-expr)))]
      (-validate-filter-op-for-type (:op filter-expr)
                                    (:type field-meta)
                                    (:field filter-expr)))))

(defn validate-filter-ops-with-metadata
  "Validate all filter operators are valid for their field types.
   Returns nil if valid, {:error ...} if invalid.
   Unknown fields are skipped (allows querying before data exists).
   Having uses ref-based numeric comparisons (no field-type check needed)."
  [events-schema {:keys [filter]}]
  (when filter
    (-validate-filter-expr-with-metadata events-schema filter)))

;; ---------------------------------------------------------
;; Events Query Schema

(def ^:private base-query
  "Base schema for events query requests (without cross-field constraints)."
  [:map {:closed true}
   [:time_range time-range]
   [:filter {:optional true} filter-expr]
   [:aggregations {:optional true} [:vector aggregation]]
   [:group_by {:optional true} [:vector field-name]]
   [:having {:optional true} having-expr]
   [:limit {:optional true} [:int {:min 1 :max 10000}]]
   [:cursor {:optional true} cursor]
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

;; Cursor pagination is not supported for aggregated queries because:
;; 1. Aggregated results lack a natural unique identifier for tiebreaking
;; 2. Group-by fields may not be unique, making keyset pagination unreliable
;; 3. Aggregated result sets are typically small enough for offset pagination
;; For non-aggregated queries, cursor works with any sort order using {sort_value, id}.
(defn- -valid-cursor-usage?
  "Cursor pagination is only valid for table queries without aggregations."
  [{:keys [visualization cursor aggregations]}]
  (if cursor
    (and (= "table" (:type visualization))
         (empty? aggregations))
    true))

(defn- -valid-having-usage?
  "Having is only valid when aggregations are present."
  [{:keys [having aggregations]}]
  (if having
    (seq aggregations)
    true))

(defn- -all-having-refs
  "Extract all :ref values from a (possibly composed) having expression."
  [having]
  (cond
    (:and having) (mapcat -all-having-refs (:and having))
    (:or having) (mapcat -all-having-refs (:or having))
    :else [(:ref having)]))

(defn- -valid-having-ref?
  "All having refs must reference existing aggregation IDs."
  [{:keys [having aggregations]}]
  (if having
    (let [agg-ids (set (map :id aggregations))]
      (every? #(contains? agg-ids %) (-all-having-refs having)))
    true))

(defn- -valid-sort-ref?
  "When sort uses :ref, the ref must reference an existing aggregation ID
   and aggregations must be present."
  [{:keys [visualization aggregations]}]
  (let [sort-config (:sort visualization)]
    (if (:ref sort-config)
      (let [agg-ids (set (map :id aggregations))]
        (and (seq aggregations)
             (contains? agg-ids (:ref sort-config))))
      true)))

(defn- -unique-aggregation-ids?
  "All aggregation IDs must be unique."
  [{:keys [aggregations]}]
  (if (seq aggregations)
    (let [ids (map :id aggregations)]
      (= (count ids) (count (set ids))))
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
    -valid-trace-filter?]
   [:fn {:error/message "cursor is only valid for table queries without aggregations"}
    -valid-cursor-usage?]
   [:fn {:error/message "having requires aggregations"}
    -valid-having-usage?]
   [:fn {:error/message "having ref must reference an existing aggregation ID"}
    -valid-having-ref?]
   [:fn {:error/message "sort ref must reference an existing aggregation ID"}
    -valid-sort-ref?]
   [:fn {:error/message "aggregation IDs must be unique"}
    -unique-aggregation-ids?]])

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
             :limit 100
             :visualization {:type "table"}})
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

  ;; Table query with sort by regular field
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :visualization {:type "table" :sort {:field "service" :order "asc"}}})
  ;; => nil

  ;; Table query with sort by aggregation ref
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :aggregations [{:id "A" :field "*" :function "count"}]
             :group_by ["service"]
             :visualization {:type "table" :sort {:ref "A" :order "desc"}}})
  ;; => nil

  ;; Having on aggregated query
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :aggregations [{:id "A" :field "*" :function "count"}]
             :group_by ["service"]
             :having {:ref "A" :op ">" :value 100}
             :visualization {:type "table"}})
  ;; => nil

  ;; Having with AND composition
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :aggregations [{:id "A" :field "*" :function "count"}
                            {:id "B" :field "duration_ms" :function "avg"}]
             :group_by ["service"]
             :having {:and [{:ref "A" :op ">" :value 100}
                            {:ref "B" :op "<" :value 500}]}
             :visualization {:type "table"}})
  ;; => nil

  ;; Having without aggregations is invalid
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :having {:ref "A" :op ">" :value 100}
             :visualization {:type "table"}})
  ;; => {:error ["having requires aggregations"]}

  ;; Cursor with custom sort is valid (for non-aggregated queries)
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :cursor "abc123"
             :visualization {:type "table" :sort {:field "service" :order "asc"}}})
  ;; => nil

  ;; Cursor with aggregations is invalid
  (validate events-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :cursor "abc123"
             :aggregations [{:id "A" :field "*" :function "count"}]
             :group_by ["service"]
             :visualization {:type "table"}})
  ;; => {:error ["cursor is only valid for table queries without aggregations"]}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
