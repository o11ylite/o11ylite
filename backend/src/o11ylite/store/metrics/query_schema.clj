;; ---------------------------------------------------------
;; o11ylite.store.metrics.query-schema
;;
;; Malli schemas for metrics query requests.
;; Defines the shape of query API requests and validation.
;;
;; V1 Scope:
;;   - Single or multiple metrics with aggregations
;;   - Top-level filter + per-metric filter overrides
;;   - Shared group-by across all metrics
;;   - Auto or manual time bucketing
;;   - Formula support (A / B * 100) with cross-field validation
;;
;; Deferred:
;;   - Aggregation-type validation (requires metadata lookup)
;;   - Histogram percentiles (p50, p99)
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query-schema
  (:require
    [malli.core :as m]
    [malli.error :as me]
    [o11ylite.store.metrics.formula :as formula]))

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
  [:enum "=" "!=" "contains" "exists" "starts-with"])

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
;; Metric-Specific Schemas

(def metric-ref
  "Single uppercase letter A-Z for referencing metrics in formulas."
  [:re {:error/message "metric id must be a single uppercase letter A-Z"}
   #"^[A-Z]$"])

(def metric-name
  "Valid metric name.
   Must start with a letter, can contain letters, numbers, dots, underscores, dashes.
   Examples: cpu.utilization, http.server.requests, my_custom_metric"
  [:and
   :string
   [:re {:error/message "metric name must start with a letter and contain only letters, numbers, dots, underscores, dashes"}
    #"^[a-zA-Z][a-zA-Z0-9._-]*$"]])

(def aggregation
  "Aggregation function for metrics.
   Valid aggregations depend on metric type (gauge/sum/histogram),
   but type validation is deferred to execution layer."
  [:enum "sum" "avg" "min" "max" "last" "rate" "count"])

(def metric-definition
  "Definition of a single metric to query."
  [:map
   [:id metric-ref]
   [:name metric-name]
   [:agg aggregation]
   [:filter {:optional true} filter-expr]])

(def formula-ref
  "Formula identifier. Distinct from metric-ref namespace (A-Z) — uses
   F1-F9 to make formula vs metric IDs visually unambiguous."
  [:re {:error/message "formula id must be F1-F9"}
   #"^F[1-9]$"])

(def formula-definition
  "Definition of a single formula computed over metric query results."
  [:map
   [:id formula-ref]
   [:expr [:string {:min 1 :max 256}]]
   [:name {:optional true} [:string {:min 1 :max 128}]]
   [:unit {:optional true} [:string {:max 32}]]])

;; ---------------------------------------------------------
;; Having Schema

(def having-op
  "Numeric comparison operators for post-aggregation filtering."
  [:enum ">" "<" ">=" "<=" "=" "!="])

(def having-ref
  "Reference target for a having clause. Either a metric id (A-Z) or a
   formula id (F1-F9). Filtering semantics are symmetric: per-bucket
   drop where the predicate fails."
  [:or metric-ref formula-ref])

(def having-expr
  "Post-aggregation filter on a metric or formula ref.
   Only supports numeric comparisons."
  [:map
   [:ref having-ref]
   [:op having-op]
   [:value number?]])

;; ---------------------------------------------------------
;; Visualization Schemas

(def time-series-visualization
  [:map {:closed true}
   [:type [:= "time_series"]]
   [:bucket_ms {:optional true} [:int {:min 1}]]
   [:overlay {:optional true} :boolean]
   [:render_as {:optional true} [:enum "line" "stacked_area" "bar"]]
   ;; UI-only render hint: source-metric ids whose series should be
   ;; hidden from the chart. Backend ignores this for query execution
   ;; but persists it (e.g. notebook cells) so the rendering state
   ;; survives reloads.
   [:hidden_metrics {:optional true} [:vector metric-ref]]])

(def visualization
  "Visualization config for metrics queries. Currently only time_series is supported."
  time-series-visualization)

;; ---------------------------------------------------------
;; Main Query Schema

(def ^:private base-query
  "Base schema for metrics query requests (without cross-field constraints)."
  [:map
   [:time_range time-range]
   [:bucket_ms {:optional true} [:int {:min 1}]]
   [:filter {:optional true} filter-expr]
   [:group_by {:optional true} [:vector field-name]]
   [:having {:optional true} having-expr]
   [:metrics [:vector {:min 1} metric-definition]]
   [:formulas {:optional true} [:vector {:max 10} formula-definition]]
   [:visualization {:optional true} visualization]])

(defn- -unique-metric-ids?
  "All metric IDs must be unique (no duplicate A, B, etc.)."
  [{:keys [metrics]}]
  (let [ids (map :id metrics)]
    (= (count ids) (count (set ids)))))

(defn- -valid-having-ref?
  "Having ref must reference a declared metric id or formula id."
  [{:keys [having metrics formulas]}]
  (if having
    (let [valid-ids (into (set (map :id metrics))
                          (map :id formulas))]
      (contains? valid-ids (:ref having)))
    true))

(defn- -unique-formula-ids?
  "All formula IDs must be unique within :formulas."
  [{:keys [formulas]}]
  (let [ids (map :id formulas)]
    (= (count ids) (count (set ids)))))

(defn- -formulas-parse?
  "Every formula :expr must parse successfully."
  [{:keys [formulas]}]
  (every? (fn [{:keys [expr]}]
            (try
              (formula/parse expr)
              true
              (catch Exception _ false)))
          formulas))

(defn- -formula-refs-resolve?
  "Every metric ref inside a formula :expr must exist in :metrics."
  [{:keys [formulas metrics]}]
  (let [metric-ids (set (map :id metrics))]
    (every?
      (fn [{:keys [expr]}]
        (try
          (every? metric-ids (formula/refs (formula/parse expr)))
          (catch Exception _ true)))   ; parse failure reported by -formulas-parse?
      formulas)))

(defn- -formula-has-refs?
  "Every formula must reference at least one metric (no constant-only exprs)."
  [{:keys [formulas]}]
  (every? (fn [{:keys [expr]}]
            (try
              (seq (formula/refs (formula/parse expr)))
              (catch Exception _ true)))   ; parse failure reported by -formulas-parse?
          formulas))

(def metrics-query
  "Schema for metrics query requests."
  [:and
   base-query
   [:fn {:error/message "metric IDs must be unique"}
    -unique-metric-ids?]
   [:fn {:error/message "having ref must reference a declared metric or formula id"
         :error/path [:having]}
    -valid-having-ref?]
   [:fn {:error/message "formula IDs must be unique"}
    -unique-formula-ids?]
   [:fn {:error/message "formula expressions must be valid"}
    -formulas-parse?]
   [:fn {:error/message "formula refs must reference declared metric IDs"}
    -formula-refs-resolve?]
   [:fn {:error/message "formula must reference at least one metric"}
    -formula-has-refs?]])

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

  ;; Valid single metric query
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A"
                        :name "cpu.utilization"
                        :agg "avg"}]})
  ;; => nil

  ;; Valid query with group_by
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A"
                        :name "cpu.utilization"
                        :agg "avg"}]
             :group_by ["attr.host.name"]})
  ;; => nil

  ;; Valid multi-metric query with filters
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :bucket_ms 60000
             :filter {:field "attr.env" :op "=" :value "prod"}
              :metrics [{:id "A"
                         :name "http.server.errors"
                         :agg "sum"
                         :filter {:field "attr.status_code" :op "=" :value "500"}}
                        {:id "B"
                         :name "http.server.requests"
                         :agg "sum"}]
              :group_by ["attr.service"]})
  ;; => nil

  ;; Valid query with formula (free memory %)
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A" :name "mem.free" :agg "avg"}
                       {:id "B" :name "mem.total" :agg "avg"}]
             :formulas [{:id "F1"
                         :expr "A / B * 100"
                         :name "free mem %"
                         :unit "%"}]})
  ;; => nil

  ;; Missing time_range
  (validate metrics-query
            {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})
  ;; => {:error {:time_range ["missing required key"]}}

  ;; Missing metrics
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}})
  ;; => {:error {:metrics ["missing required key"]}}

  ;; Empty metrics array
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics []})
  ;; => {:error {:metrics ["should have at least 1 elements"]}}

  ;; Invalid metric ID (must be single uppercase letter)
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "a1"
                        :name "cpu.utilization"
                        :agg "avg"}]})
  ;; => {:error {:metrics {0 {:id ["metric id must be a single uppercase letter A-Z"]}}}}

  ;; Duplicate metric IDs
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}
                       {:id "A" :name "memory.usage" :agg "avg"}]})
  ;; => {:error ["metric IDs must be unique"]}

  ;; Invalid aggregation
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A"
                        :name "cpu.utilization"
                        :agg "percentile"}]})
  ;; => {:error {:metrics {0 {:agg ["should be one of: sum, avg, min, max, last, rate, count"]}}}}

  ;; Invalid metric name (SQL injection attempt)
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A"
                        :name "cpu; DROP TABLE metrics;"
                        :agg "avg"}]})
  ;; => {:error {:metrics {0 {:name ["metric name must start with a letter..."]}}}}

  ;; Valid histogram query
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A"
                        :name "http.server.duration"
                        :agg "avg"}]
             :group_by ["attr.service" "attr.method"]})
  ;; => nil

  ;; Complex filter with AND/OR
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :filter {:and [{:field "attr.env" :op "=" :value "prod"}
                            {:or [{:field "attr.region" :op "=" :value "us-east"}
                                  {:field "attr.region" :op "=" :value "us-west"}]}]}
             :metrics [{:id "A"
                        :name "http.server.requests"
                        :agg "sum"}]})
  ;; => nil

  ;; Having on metric query (for alerting: empty result = no alert)
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
             :having {:ref "A" :op ">" :value 80}})
  ;; => nil

  ;; Having with invalid ref
  (validate metrics-query
            {:time_range {:start 1702000000000 :end 1702003600000}
             :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
             :having {:ref "B" :op ">" :value 80}})
  ;; => {:error ["having ref must reference an existing metric ID"]}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
