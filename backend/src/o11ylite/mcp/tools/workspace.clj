;; ---------------------------------------------------------
;; o11ylite.mcp.tools.workspace
;;
;; MCP tools over saved objects: alert rules (read + save) and
;; notebooks (create). Writes go through the same malli validation
;; as the UI routes (o11ylite.routes.alert-rules / notebooks).
;; ---------------------------------------------------------

(ns o11ylite.mcp.tools.workspace
  (:require
    [next.jdbc :as jdbc]
    [o11ylite.alert-rule :as alert-rule]
    [o11ylite.alert-rule.schema :as alert-rule-schema]
    [o11ylite.mcp.json-schema :as js]
    [o11ylite.mcp.protocol :as protocol]
    [o11ylite.mcp.tools.support :as support]
    [o11ylite.notebook :as notebook]
    [o11ylite.notebook.schema :as notebook-schema])
  (:import
    [com.github.f4b6a3.uuid UuidCreator]))

;; ---------------------------------------------------------
;; Shared

(def ^:private read-only
  {:readOnlyHint true :openWorldHint false})

(defn- -new-id
  []
  (str (UuidCreator/getTimeOrderedEpoch)))

(defn- -alert-rule-summary
  "List view: drop the query body to keep the list compact."
  [rule]
  (select-keys rule [:id :name :description :enabled :state :query_mode :alert_on
                     :eval_window_ms :eval_interval_ms :last_eval_at :last_eval_error
                     :state_changed_at :updated_at]))

(defn- -url
  [context path]
  (str (:base-url context) path))

;; ---------------------------------------------------------
;; list_alert_rules

(def list-alert-rules
  "Tool: list alert rules with their current state."
  {:name "list_alert_rules"
   :title "List alert rules"
   :description (str "List alert rules with state (\"firing\" if any instance fires, else "
                     "\"ok\"), enabled flag, and last evaluation result. Use state to answer "
                     "\"what is alerting right now\"; use get_alert_rule for the query and "
                     "per-group instances.")
   :input-schema (js/object-schema
                   {:state {:enum ["ok" "firing"]
                            :description "Only return rules in this state."}})
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps]} args]
              (support/validate! [:map {:closed true}
                                  [:state {:optional true} [:enum "ok" "firing"]]]
                                 args)
              (let [rules (cond->> (alert-rule/list-all (:sqlite deps))
                            (:state args) (filter #(= (:state args) (:state %))))]
                {:alert_rules (mapv -alert-rule-summary rules)}))})

;; ---------------------------------------------------------
;; get_alert_rule

(def get-alert-rule
  "Tool: one alert rule with query and instances."
  {:name "get_alert_rule"
   :title "Get alert rule"
   :description (str "Get one alert rule including its query and its alert instances (one per "
                     "group-by combination, with state, labels, and the last breaching value).")
   :input-schema (js/object-schema {:id {:type "string" :description "Alert rule id."}} [:id])
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps] :as context} args]
              (support/validate! [:map {:closed true} [:id [:string {:min 1}]]] args)
              (if-let [rule (alert-rule/get-by-id (:sqlite deps) (:id args))]
                {:alert_rule rule
                 :instances (alert-rule/list-instances (:sqlite deps) (:id args))
                 :url (-url context (str "/alert-rules/" (:id args) "/edit"))}
                (throw (protocol/tool-error (str "Alert rule not found: " (:id args))))))})

;; ---------------------------------------------------------
;; save_alert_rule

(def ^:private window-enum [60000 300000 900000 1800000 3600000])

(def ^:private save-alert-rule-args
  [:map {:closed true}
   [:id {:optional true} [:string {:min 1}]]
   [:name :any]
   [:description {:optional true} :any]
   [:enabled {:optional true} :boolean]
   [:query_mode :any]
   [:query :any]
   [:eval_window_ms :any]
   [:eval_interval_ms :any]
   [:alert_on {:optional true} :any]
   [:alert_target {:optional true} :any]])

(def save-alert-rule
  "Tool: create or update an alert rule."
  {:name "save_alert_rule"
   :title "Save alert rule"
   :description
   (str "Create an alert rule, or replace an existing one when id is given (send every field; "
        "fetch it first with get_alert_rule). The query uses the query_events / query_metrics "
        "shape without time_range, cursor, or limit; the evaluation window supplies the time "
        "range.\n"
        "- events: {\"filter\":...,\"aggregations\":[...],\"group_by\":[...],\"having\":...,"
        "\"visualization\":{\"type\":\"table\"}}\n"
        "- metrics: {\"metrics\":[...],\"formulas\":[...],\"filter\":...,\"group_by\":[...],"
        "\"having\":...}; set alert_target when more than one metric/formula is declared.\n"
        "alert_on \"result\" fires for each group the query returns; \"no_result\" fires "
        "when a previously seen group returns nothing (absence detection).")
   :input-schema
   (js/object-schema
     {:id {:type "string" :description "Existing rule id to replace. Omit to create."}
      :name {:type "string" :minLength 1 :maxLength 255}
      :description {:type "string"}
      :enabled {:type "boolean"
                :description "Default true for new rules; unchanged when replacing."}
      :query_mode {:enum ["events" "metrics"]}
      :query {:type "object" :description "Query definition (see tool description)."}
      :eval_window_ms {:enum window-enum :description "Time range evaluated each run."}
      :eval_interval_ms {:enum window-enum :description "How often the rule runs."}
      :alert_on {:enum ["result" "no_result"] :description "Default \"result\"."}
      :alert_target {:type "string"
                     :description "Metrics mode: metric (A-Z) or formula (F1-F9) id to watch."}}
     [:name :query_mode :query :eval_window_ms :eval_interval_ms])
   :annotations {:readOnlyHint false :destructiveHint true :idempotentHint false
                 :openWorldHint false}
   :scope "write"
   :handler
   (fn [{:keys [deps] :as context} args]
     (support/validate! save-alert-rule-args args)
     (let [sqlite (:sqlite deps)
           id (:id args)
           existing (when id (alert-rule/get-by-id sqlite id))
           _ (when (and id (nil? existing))
               (throw (protocol/tool-error (str "Alert rule not found: " id
                                                ". Omit id to create a new rule."))))
           ;; enabled defaults to true on create and to the current value on update.
           enabled (if (contains? args :enabled) (:enabled args) (:enabled existing true))
           params (-> (select-keys args [:name :description :query_mode :query
                                         :eval_window_ms :eval_interval_ms :alert_target])
                      (assoc :enabled (boolean enabled)
                             :alert_on (or (:alert_on args) "result")))]
       (when-let [error (alert-rule-schema/validate params)]
         (throw (protocol/tool-error "Invalid alert rule" (:error error))))
       (let [saved-id (or id (-new-id))]
         (if id
           (alert-rule/update! sqlite id params)
           (do (alert-rule/create! sqlite saved-id params)
               ;; create! always inserts enabled; honor an explicit false.
               (when-not (:enabled params)
                 (alert-rule/update! sqlite saved-id params))))
         {:created (nil? id)
          :alert_rule (alert-rule/get-by-id sqlite saved-id)
          :url (-url context (str "/alert-rules/" saved-id "/edit"))})))})

;; ---------------------------------------------------------
;; create_notebook

(def ^:private create-notebook-args
  [:map {:closed true}
   [:name :any]
   [:description {:optional true} :any]
   [:cells {:optional true} [:vector {:max 50} [:map-of :keyword :any]]]])

(def create-notebook
  "Tool: create a notebook with cells."
  {:name "create_notebook"
   :title "Create notebook"
   :description
   (str "Save an investigation as a notebook the user can open in the UI. Each cell is a "
        "query in the query_events (query_mode \"events\") or query_metrics "
        "(query_mode \"metrics\") shape without time_range or cursor; events cells need a "
        "visualization. Cells follow the notebook's time picker unless pinned_from/pinned_to "
        "(ISO-8601) freeze them to a window, e.g. the incident. Returns the notebook URL.")
   :input-schema
   (js/object-schema
     {:name {:type "string" :minLength 1 :maxLength 255}
      :description {:type "string"}
      :cells {:type "array"
              :maxItems 50
              :items {:type "object"
                      :properties {:title {:type "string"}
                                   :description {:type "string"}
                                   :query_mode {:enum ["events" "metrics"]}
                                   :query {:type "object"}
                                   :pinned_from {:type "string"}
                                   :pinned_to {:type "string"}}
                      :required ["query_mode" "query"]
                      :additionalProperties false}}}
     [:name])
   :annotations {:readOnlyHint false :destructiveHint false :idempotentHint false
                 :openWorldHint false}
   :scope "write"
   :handler
   (fn [{:keys [deps] :as context} args]
     (support/validate! create-notebook-args args)
     (let [notebook-params (select-keys args [:name :description])
           cells (vec (:cells args))]
       (when-let [error (notebook-schema/validate-notebook notebook-params)]
         (throw (protocol/tool-error "Invalid notebook" (:error error))))
       (doseq [[i cell] (map-indexed vector cells)]
         (when-let [error (notebook-schema/validate-cell cell)]
           (throw (protocol/tool-error (str "Invalid cell at index " i) (:error error)))))
       (let [id (-new-id)]
         (jdbc/with-transaction [tx (:sqlite deps)]
           (notebook/create-notebook! tx id notebook-params)
           (doseq [cell cells]
             (notebook/create-cell! tx (-new-id) (assoc cell :notebook_id id))))
         {:id id
          :cell_count (count cells)
          :url (-url context (str "/notebooks/" id))})))})

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  ((:handler list-alert-rules) {:deps {:sqlite (:db/sqlite system)}} {})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
