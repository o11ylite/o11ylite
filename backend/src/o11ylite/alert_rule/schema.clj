;; ---------------------------------------------------------
;; o11ylite.alert-rule.schema
;;
;; Malli schemas for alert rule validation.
;; Query schemas are derived from the full events/metrics
;; query schemas by removing runtime-only keys (time_range,
;; cursor, limit) and selecting applicable cross-field
;; validators.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.schema
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
  "Derive an alert-rule query schema from a parent [:and :map :fn ...] schema.
   Removes specified keys, closes the map, and keeps only the listed :fn validators."
  [parent-schema dissoc-keys keep-messages]
  (let [base (reduce mu/dissoc (-base-map parent-schema) dissoc-keys)
        closed (mu/closed-schema base)
        fns (-fn-validators parent-schema keep-messages)]
    (into [:and closed] fns)))

;; ---------------------------------------------------------
;; Query Schemas (derived from parent API schemas)

(def events-query
  "Alert rule query schema for events mode.
   Derived from events-schema/events-query minus runtime keys."
  (-derive-query-schema
    events-schema/events-query
    [:time_range :cursor :limit]
    #{"having requires aggregations"
      "having ref must reference an existing aggregation ID"
      "aggregation IDs must be unique"}))

(def metrics-query
  "Alert rule query schema for metrics mode.
   Derived from metrics-schema/metrics-query minus runtime keys."
  (-derive-query-schema
    metrics-schema/metrics-query
    [:time_range]
    #{"metric IDs must be unique"
      "having ref must reference a declared metric or formula id"}))

;; ---------------------------------------------------------
;; Alert Rule Schema

(def eval-window-ms
  "Valid evaluation window presets (milliseconds)."
  [:enum 60000 300000 900000 1800000 3600000])

(def eval-interval-ms
  "Valid evaluation interval presets (milliseconds)."
  [:enum 60000 300000 900000 1800000 3600000])

(def alert-target
  "Identifier of the metric or formula whose series the alert watches.
   Required at save time when a metrics query declares more than one
   series source (i.e. metrics + formulas count > 1). When provided,
   must match a declared metric id (A-Z) or formula id (F1-F9)."
  [:re {:error/message "alert_target must be a metric id (A-Z) or formula id (F1-F9)"}
   #"^([A-Z]|F[1-9])$"])

(def ^:private -alert-rule-base
  [:map {:closed true}
   [:name [:string {:min 1, :max 255}]]
   [:description {:optional true} [:maybe :string]]
   [:enabled :boolean]
   [:query_mode [:enum "events" "metrics"]]
   [:query :any]
   [:eval_window_ms eval-window-ms]
   [:eval_interval_ms eval-interval-ms]
   [:alert_on [:enum "result" "no_result"]]
   [:alert_target {:optional true} [:maybe alert-target]]])

(defn- -metrics-source-ids
  "Set of declared metric and formula ids from a metrics query."
  [query]
  (into (set (map :id (:metrics query)))
        (map :id (:formulas query))))

(defn- -alert-target-required?
  [query]
  (> (+ (count (:metrics query)) (count (:formulas query))) 1))

(defn- -valid-alert-target?
  "When query_mode is 'metrics':
   - if metrics+formulas count > 1, alert_target is required
   - when alert_target is provided, it must reference a declared id"
  [{:keys [query_mode query alert_target]}]
  (if-not (= query_mode "metrics")
    true
    (let [ids (-metrics-source-ids query)]
      (cond
        ;; Required but missing
        (and (-alert-target-required? query) (nil? alert_target)) false
        ;; Provided but doesn't match a declared id
        (and (some? alert_target) (not (contains? ids alert_target))) false
        :else true))))

(def alert-rule
  "Full schema for alert rule create/update requests.
   The cross-field :fn validator attaches its error to :alert_target via
   :error/path so the result lands as {:alert_target [...]} — the same
   map shape every other field error has."
  [:and
   -alert-rule-base
   [:fn {:error/message
         "alert_target must reference a declared metric or formula id; required when the query has more than one metric/formula"
         :error/path [:alert_target]}
    -valid-alert-target?]])

;; ---------------------------------------------------------
;; Validation

(defn validate
  "Validate an alert rule (including its query based on query_mode).
   Returns nil if valid, or {:error ...} if invalid."
  [params]
  (let [rule-error (when-not (m/validate alert-rule params)
                     {:error (me/humanize (m/explain alert-rule params))})]
    (if rule-error
      rule-error
      (let [query-schema (case (:query_mode params)
                           "events" events-query
                           "metrics" metrics-query)]
        (when-not (m/validate query-schema (:query params))
          {:error {:query (me/humanize (m/explain query-schema (:query params)))}})))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Valid events alert rule
  (validate {:name "High error rate"
             :description "Alert when errors spike"
             :enabled true
             :query_mode "events"
             :query {:filter {:field "service" :op "=" :value "frontend"}
                     :visualization {:type "table"}}
             :eval_window_ms 300000
             :eval_interval_ms 60000})
  ;; => nil

  ;; Invalid: :filters (plural) rejected by closed query map
  (validate {:name "Bad query"
             :enabled true
             :query_mode "events"
             :query {:filters [{:field "service" :op "=" :value "frontend"}]
                     :visualization {:type "table"}}
             :eval_window_ms 300000
             :eval_interval_ms 60000})
  ;; => {:error {:query {:filters ["disallowed key"]}}}

  ;; Invalid: missing name
  (validate {:enabled true
             :query_mode "events"
             :query {:visualization {:type "table"}}
             :eval_window_ms 300000
             :eval_interval_ms 60000})
  ;; => {:error {:name ["missing required key"]}}

  ;; Invalid: bad eval_window_ms
  (validate {:name "Test"
             :enabled true
             :query_mode "events"
             :query {:visualization {:type "table"}}
             :eval_window_ms 999
             :eval_interval_ms 60000})
  ;; => {:error {:eval_window_ms [...]}}

  ;; Valid metrics alert rule
  (validate {:name "CPU alert"
             :enabled true
             :query_mode "metrics"
             :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                     :having {:ref "A" :op ">" :value 80}}
             :eval_window_ms 300000
             :eval_interval_ms 60000})
  ;; => nil

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
