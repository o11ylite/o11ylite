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
;;
;; Deferred:
;;   - Formula support (A / B * 100)
;;   - Aggregation-type validation (requires metadata lookup)
;;   - Histogram percentiles (p50, p99)
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query-schema
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
;; Metric-Specific Schemas

(def metric-ref
  "Single uppercase letter A-Z for referencing metrics in formulas (future)."
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

;; ---------------------------------------------------------
;; Main Query Schema

(def ^:private base-query
  "Base schema for metrics query requests (without cross-field constraints)."
  [:map
   [:time_range time-range]
   [:bucket_ms {:optional true} [:int {:min 1}]]
   [:filter {:optional true} filter-expr]
   [:group_by {:optional true} [:vector field-name]]
   [:metrics [:vector {:min 1} metric-definition]]])

(defn- -unique-metric-ids?
  "All metric IDs must be unique (no duplicate A, B, etc.)."
  [{:keys [metrics]}]
  (let [ids (map :id metrics)]
    (= (count ids) (count (set ids)))))

(def metrics-query
  "Schema for metrics query requests."
  [:and
   base-query
   [:fn {:error/message "metric IDs must be unique"}
    -unique-metric-ids?]])

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
                        :filter {:field "attr.status_code" :op ">=" :value "500"}}
                       {:id "B"
                        :name "http.server.requests"
                        :agg "sum"}]
             :group_by ["attr.service"]})
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

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
