;; ---------------------------------------------------------
;; o11ylite.mcp.tools.telemetry
;;
;; Read-only MCP tools over telemetry: discovery (services, event
;; fields, metrics) and queries (events, traces, metrics).
;;
;; Tools call the same validation and execution functions as the REST
;; API (o11ylite.api.*), then trim results for model context: event
;; rows drop null columns and can be projected to selected fields.
;; ---------------------------------------------------------

(ns o11ylite.mcp.tools.telemetry
  (:require
    [o11ylite.components.blocked-fields :as blocked-fields]
    [o11ylite.mcp.json-schema :as js]
    [o11ylite.mcp.protocol :as protocol]
    [o11ylite.mcp.tools.support :as support]
    [o11ylite.store.events.query :as events.query]
    [o11ylite.store.events.query-schema :as events-schema]
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.store.metrics.query :as metrics.query]
    [o11ylite.store.metrics.query-schema :as metrics-schema]
    [o11ylite.store.query-util :as query-util]
    [o11ylite.store.schema :as schema]
    [o11ylite.store.services :as services]))

;; ---------------------------------------------------------
;; Shared schema fragments

(def ^:private read-only
  {:readOnlyHint true :openWorldHint false})

(def ^:private search-properties
  {:search {:type "string"
            :description "Case-insensitive substring to match against names."}
   :limit {:type "integer" :minimum 1 :maximum 1000
           :description "Maximum items to return (default 200)."}})

(def ^:private search-args
  [:map {:closed true}
   [:search {:optional true} :string]
   [:limit {:optional true} [:int {:min 1 :max 1000}]]])

(def ^:private no-args
  [:map {:closed true}])

(defn- -now
  []
  (System/currentTimeMillis))

;; ---------------------------------------------------------
;; list_services

(def list-services
  "Tool: list known service names."
  {:name "list_services"
   :title "List services"
   :description "List every service that has sent telemetry, with first/last seen times (epoch ms)."
   :input-schema (js/object-schema {})
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps]} args]
              (support/validate! no-args args)
              {:services (services/get-services (:sqlite deps))})})

;; ---------------------------------------------------------
;; list_event_fields

(def list-event-fields
  "Tool: list queryable event fields."
  {:name "list_event_fields"
   :title "List event fields"
   :description (str "List queryable fields of the events table (spans and logs) with their types "
                     "(string, integer, float, boolean, instant). OpenTelemetry attributes are "
                     "prefixed with attr. (e.g. attr.http.route). Call this before filtering or "
                     "grouping on a field you have not seen.")
   :input-schema (js/object-schema search-properties)
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps]} {:keys [search limit] :as args}]
              (support/validate! search-args args)
              (let [blocked (blocked-fields/get-blocked-event-fields (:blocked-fields deps))
                    fields (->> (schema/fetch-event-fields (:duckdb deps))
                                (remove (fn [[k _]] (contains? blocked (name k))))
                                (map (fn [[k v]] {:name (name k) :type (:type v)}))
                                (sort-by :name))
                    {:keys [items total truncated]} (support/filter-by-search fields search (or limit 200))]
                {:fields items :total total :truncated truncated}))})

;; ---------------------------------------------------------
;; query_events

(def ^:private query-events-args
  [:map {:closed true}
   [:time_range {:optional true} :any]
   [:filter {:optional true} :any]
   [:aggregations {:optional true} :any]
   [:group_by {:optional true} :any]
   [:having {:optional true} :any]
   [:visualization {:optional true}
    [:map [:type [:enum "table" "time_series"]]]]
   [:limit {:optional true} [:int {:min 1 :max 1000}]]
   [:cursor {:optional true} [:maybe :string]]
   [:fields {:optional true} [:vector :string]]])

(def ^:private default-event-limit 50)

(defn- -validated-events-query
  "Validate against the events query engine and normalize filter values.
   Returns [query field-metadata]."
  [duckdb query]
  (let [fields (schema/fetch-event-fields duckdb)]
    (some-> (events.query/validate fields query) support/query-error!)
    [(query-util/normalize-filter fields query) fields]))

(defn- -instant-keys
  "Row keys whose column type is instant (timestamps)."
  [field-metadata]
  (into #{} (keep (fn [[k v]] (when (= "instant" (some-> (:type v) name)) k))) field-metadata))

(defn- -trim-row
  "Drop null columns, optionally project to `fields`, and render
   timestamps as ISO-8601."
  [instant-keys fields row]
  (let [row (cond-> (support/drop-nil-values row)
              (seq fields) (select-keys (map keyword fields)))]
    (reduce (fn [r k] (if (contains? r k) (update r k support/epoch-ms->iso) r))
            row
            instant-keys)))

(def query-events
  "Tool: query events (spans and logs)."
  {:name "query_events"
   :title "Query events"
   :description
   (str "Query events: spans and logs share one table (meta.signal_type is \"span\", "
        "\"span_event\", or \"log\"). Built-in fields: service, name, trace_id, span_id, "
        "timestamp, span.duration_ms, span.status_code; attributes are attr.*.\n"
        "- Raw rows: omit aggregations; visualization {\"type\":\"table\"} (default). "
        "Use fields to return only the columns you need; null columns are dropped. "
        "Page with cursor = next_cursor.\n"
        "- Aggregates: aggregations [{\"id\":\"A\",\"function\":\"count\",\"field\":\"*\"}] "
        "with group_by, optional having on aggregation ids, and "
        "visualization {\"type\":\"table\",\"sort\":{\"ref\":\"A\",\"order\":\"desc\"}}.\n"
        "- Over time: visualization {\"type\":\"time_series\"} (requires aggregations; "
        "optional bucket_ms).\n"
        "Filter example: {\"and\":[{\"field\":\"service\",\"op\":\"=\",\"value\":\"api\"},"
        "{\"field\":\"span.duration_ms\",\"op\":\">\",\"value\":500}]}.")
   :input-schema
   (js/object-schema
     {:time_range support/time-range-json-schema
      :filter (js/from-malli events-schema/filter-expr
                             "Row filter. Ops: = != > < >= <= contains exists starts-with. Combine with {and:[...]} / {or:[...]}.")
      :aggregations (js/from-malli [:vector events-schema/aggregation]
                                   "Aggregations; id is a single letter A-Z referenced by having and sort.")
      :group_by (js/from-malli [:vector events-schema/field-name] "Fields to group by.")
      :having (js/from-malli events-schema/having-expr
                             "Filter on aggregation results, e.g. {\"ref\":\"A\",\"op\":\">\",\"value\":10}.")
      :visualization {:type "object"
                      :description (str "{\"type\":\"table\"} (optional sort {field|ref, order}) or "
                                        "{\"type\":\"time_series\"} (optional bucket_ms).")
                      :properties {:type {:enum ["table" "time_series"]}
                                   :sort (js/from-malli events-schema/sort-config)
                                   :bucket_ms {:type "integer" :minimum 1}}
                      :required ["type"]}
      :limit {:type "integer" :minimum 1 :maximum 1000
              :description "Maximum rows (default 50)."}
      :cursor {:type "string" :description "next_cursor from a previous raw-row result."}
      :fields {:type "array" :items {:type "string"}
               :description "Raw rows only: columns to return."}})
   :annotations read-only
   :scope "read"
   :handler
   (fn [{:keys [deps]} args]
     (support/validate! query-events-args args)
     (let [time-range (support/resolve-time-range (:time_range args) "1h" (-now))
           visualization (or (:visualization args) {:type "table"})
           [query field-metadata]
           (-validated-events-query
             (:duckdb deps)
             (cond-> (-> (select-keys args [:filter :aggregations :group_by :having :cursor])
                         (assoc :time_range time-range
                                :visualization visualization))
               (= "table" (:type visualization))
               (assoc :limit (or (:limit args) default-event-limit))))
           {:keys [data metadata]} (events.query/execute (:duckdb deps) query)
           trim (partial -trim-row (-instant-keys field-metadata) (:fields args))]
       (merge {:time_range (support/time-range->iso time-range)
               :query_time_ms (:query_time_ms metadata)}
              (if (= "table" (:type visualization))
                (-> data
                    (update :rows #(mapv trim %))
                    (dissoc :total_count)
                    (assoc :row_count (count (:rows data))))
                data))))})

;; ---------------------------------------------------------
;; get_trace

(def ^:private get-trace-args
  [:map {:closed true}
   [:trace_id [:string {:min 1}]]
   [:time_range {:optional true} :any]])

(def get-trace
  "Tool: fetch all spans of one trace."
  {:name "get_trace"
   :title "Get trace"
   :description (str "Fetch the spans of one trace (up to 1000), ordered by timestamp, with "
                     "span_id/parent_span_id for reconstructing the tree. Searches the last 24h "
                     "unless time_range is given.")
   :input-schema (js/object-schema
                   {:trace_id {:type "string" :description "Trace ID (hex)."}
                    :time_range support/time-range-json-schema}
                   [:trace_id])
   :annotations read-only
   :scope "read"
   :handler
   (fn [{:keys [deps]} args]
     (support/validate! get-trace-args args)
     (let [time-range (support/resolve-time-range (:time_range args) "24h" (-now))
           [query field-metadata]
           (-validated-events-query
             (:duckdb deps)
             {:time_range time-range
              :visualization {:type "trace"}
              :filter {:field "trace_id" :op "=" :value (:trace_id args)}})
           spans (get-in (events.query/execute (:duckdb deps) query) [:data :spans])]
       (cond-> {:trace_id (:trace_id args)
                :time_range (support/time-range->iso time-range)
                :span_count (count spans)
                :spans (mapv (partial -trim-row (-instant-keys field-metadata) nil) spans)}
         (empty? spans)
         (assoc :hint "No spans found. Widen time_range if the trace is older."))))})

;; ---------------------------------------------------------
;; list_metrics / describe_metric

(def list-metrics
  "Tool: list metric names."
  {:name "list_metrics"
   :title "List metrics"
   :description "List metrics with their type (gauge, sum, histogram) and unit."
   :input-schema (js/object-schema search-properties)
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps]} {:keys [search limit] :as args}]
              (support/validate! search-args args)
              (let [{:keys [items total truncated]}
                    (support/filter-by-search (metadata/list-metrics-summary (:sqlite deps))
                                              search (or limit 200))]
                {:metrics items :total total :truncated truncated}))})

(def describe-metric
  "Tool: metric metadata including attribute names."
  {:name "describe_metric"
   :title "Describe metric"
   :description (str "Get a metric's type, unit, temporality, and attribute names. The "
                     "attributes are the fields valid in query_metrics filter and group_by.")
   :input-schema (js/object-schema {:name {:type "string" :description "Metric name."}} [:name])
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps]} args]
              (support/validate! [:map {:closed true} [:name [:string {:min 1}]]] args)
              (if-let [metric (metadata/get-metric (:sqlite deps) (:name args))]
                (update metric :attributes #(some-> % sort vec))
                (throw (protocol/tool-error (str "Metric not found: " (:name args)
                                                 ". Use list_metrics to find names.")))))})

;; ---------------------------------------------------------
;; query_metrics

(def ^:private query-metrics-args
  [:map {:closed true}
   [:time_range {:optional true} :any]
   [:metrics :any]
   [:formulas {:optional true} :any]
   [:filter {:optional true} :any]
   [:group_by {:optional true} :any]
   [:having {:optional true} :any]
   [:bucket_ms {:optional true} [:int {:min 1}]]])

(def query-metrics
  "Tool: query metric time series."
  {:name "query_metrics"
   :title "Query metrics"
   :description
   (str "Query metric time series. Each metric gets an id (A-Z) and an aggregation valid for "
        "its type: gauge sum/avg/min/max/last, sum sum/rate, histogram count/sum/avg/min/max "
        "(use describe_metric). Formulas combine ids, e.g. {\"id\":\"F1\",\"expr\":\"B / A * 100\"}. "
        "filter and group_by use the metric's attribute names (attr.*).")
   :input-schema
   (js/object-schema
     {:time_range support/time-range-json-schema
      :metrics (js/from-malli [:vector {:min 1} metrics-schema/metric-definition]
                              "Metrics to query, e.g. [{\"id\":\"A\",\"name\":\"http.server.duration\",\"agg\":\"avg\"}].")
      :formulas (js/from-malli [:vector metrics-schema/formula-definition]
                               "Optional formulas over metric ids (ids F1-F9).")
      :filter (js/from-malli metrics-schema/filter-expr "Filter applied to all metrics.")
      :group_by (js/from-malli [:vector metrics-schema/field-name] "Attributes to group series by.")
      :having (js/from-malli metrics-schema/having-expr
                             "Drop points where the predicate on a metric/formula id fails.")
      :bucket_ms {:type "integer" :minimum 1
                  :description "Bucket size in ms (auto-selected when omitted)."}}
     [:metrics])
   :annotations read-only
   :scope "read"
   :handler
   (fn [{:keys [deps]} args]
     (support/validate! query-metrics-args args)
     (let [{:keys [sqlite duckdb]} deps
           query (-> (select-keys args [:metrics :formulas :filter :group_by :having :bucket_ms])
                     (assoc :time_range (support/resolve-time-range (:time_range args) "1h" (-now))))]
       (some-> (metrics.query/validate sqlite duckdb query) support/query-error!)
       (let [{:keys [data metadata]} (metrics.query/execute duckdb sqlite query)]
         (assoc data
                :time_range (support/time-range->iso (:time_range query))
                :query_time_ms (:query_time_ms metadata)))))})

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[jsonista.core :as j])
  (println (j/write-value-as-string (:input-schema query-events)))

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
