;; ---------------------------------------------------------
;; o11ylite.mcp.tools.workspace
;;
;; MCP tools over saved objects: alert rules (read + save) and
;; notebooks (list, read, create, update). Writes go through the same
;; malli validation as the UI routes (o11ylite.routes.alert-rules /
;; notebooks).
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

(defn- -notebook-url
  [context id]
  (-url context (str "/notebooks/" id)))

(defn- -notebook-summary
  [context notebook]
  (-> (select-keys notebook [:id :name :description :cell_count :created_at :updated_at])
      (assoc :url (-notebook-url context (:id notebook)))))

(def ^:private read-only-cell-keys
  [:id :notebook_id :position :created_at :updated_at])

(defn- -cell-params
  "Drop read-only fields returned by get_notebook, so a read-modify-write
   round-trip works. Unknown fields are left in place and rejected by
   notebook.schema/cell."
  [cell]
  (apply dissoc cell read-only-cell-keys))

(def ^:private notebook-cell-json-schema
  {:type "object"
   :properties {:title {:type ["string" "null"]}
                :description {:type ["string" "null"]}
                :query_mode {:enum ["events" "metrics"]}
                :query {:type "object"
                        :description (str "Query in the query_events/query_metrics shape without "
                                          "time_range or cursor. For time_series cells, "
                                          "query.visualization carries overlay, render_as, and "
                                          "hidden_metrics (see query_metrics).")}
                :pinned_from {:type ["string" "null"]}
                :pinned_to {:type ["string" "null"]}
                :id {:type "string" :description "Read-only; ignored on write."}
                :notebook_id {:type "string" :description "Read-only; ignored on write."}
                :position {:type "integer" :description "Read-only; ignored on write."}
                :created_at {:type "integer" :description "Read-only; ignored on write."}
                :updated_at {:type "integer" :description "Read-only; ignored on write."}}
   :required ["query_mode" "query"]
   :additionalProperties false})

(def ^:private notebook-cells-args
  [:vector {:max 50} [:map-of :keyword :any]])

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
;; list_notebooks

(def list-notebooks
  "Tool: list notebooks."
  {:name "list_notebooks"
   :title "List notebooks"
   :description (str "List saved notebooks with cell count and last-updated time. Use "
                     "get_notebook for the cell queries and chart settings.")
   :input-schema (js/object-schema {})
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps] :as context} args]
              (support/validate! [:map {:closed true}] args)
              {:notebooks (mapv (partial -notebook-summary context)
                                (notebook/list-notebooks (:sqlite deps)))})})

;; ---------------------------------------------------------
;; get_notebook

(def get-notebook
  "Tool: get one notebook with its cells."
  {:name "get_notebook"
   :title "Get notebook"
   :description (str "Get one notebook with its ordered cells. Each cell includes its full "
                     "query and chart settings (query.visualization.overlay, render_as, "
                     "hidden_metrics). Read this before update_notebook.")
   :input-schema (js/object-schema {:id {:type "string" :description "Notebook id."}} [:id])
   :annotations read-only
   :scope "read"
   :handler (fn [{:keys [deps] :as context} args]
              (support/validate! [:map {:closed true} [:id [:string {:min 1}]]] args)
              (if-let [saved (notebook/get-notebook-by-id (:sqlite deps) (:id args))]
                {:notebook saved
                 :url (-notebook-url context (:id args))}
                (throw (protocol/tool-error (str "Notebook not found: " (:id args)
                                                 ". Use list_notebooks to find ids.")))))})

;; ---------------------------------------------------------
;; create_notebook

(def ^:private create-notebook-args
  [:map {:closed true}
   [:name :any]
   [:description {:optional true} :any]
   [:cells {:optional true} notebook-cells-args]])

(def create-notebook
  "Tool: create a notebook with cells."
  {:name "create_notebook"
   :title "Create notebook"
   :description
   (str "Save an investigation as a notebook the user can open in the UI. Each cell is a "
        "query in the query_events (query_mode \"events\") or query_metrics "
        "(query_mode \"metrics\") shape without time_range or cursor; events cells need a "
        "visualization. For time_series cells, set query.visualization to preserve chart "
        "layout: overlay (default false: one chart per metric/formula), render_as "
        "(\"line\" default, \"stacked_area\", \"bar\"), and hidden_metrics (default []: series "
        "ids A-Z or F1-F9 to hide while still evaluating them). Cells follow the notebook's "
        "time picker unless pinned_from/pinned_to (ISO-8601) freeze them to a window, e.g. "
        "the incident. Returns the notebook URL.")
   :input-schema
   (js/object-schema
     {:name {:type "string" :minLength 1 :maxLength 255}
      :description {:type ["string" "null"]}
      :cells {:type "array" :maxItems 50 :items notebook-cell-json-schema}}
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
         (when-let [error (notebook-schema/validate-cell (-cell-params cell))]
           (throw (protocol/tool-error (str "Invalid cell at index " i) (:error error)))))
       (let [id (-new-id)]
         (jdbc/with-transaction [tx (:sqlite deps)]
           (notebook/create-notebook! tx id notebook-params)
           (doseq [cell cells]
             (notebook/create-cell! tx (-new-id) (-> cell
                                                     -cell-params
                                                     (assoc :notebook_id id)))))
         {:id id
          :cell_count (count cells)
          :url (-notebook-url context id)})))})

;; ---------------------------------------------------------
;; update_notebook

(def ^:private update-notebook-args
  [:map {:closed true}
   [:id [:string {:min 1}]]
   [:name {:optional true} :any]
   [:description {:optional true} :any]
   [:cells {:optional true} notebook-cells-args]])

(def update-notebook
  "Tool: update a notebook and optionally replace its cells."
  {:name "update_notebook"
   :title "Update notebook"
   :description
   (str "Update a notebook by id. Omit name/description to keep them; omit cells to keep "
        "the current cells, or send cells to replace the entire list in order. Read the "
        "notebook with get_notebook first, change the cell query (including "
        "query.visualization.overlay, render_as, and hidden_metrics), then send all cells "
        "back; read-only cell fields (id, notebook_id, position, timestamps) are ignored. "
        "Chart settings default to one chart per metric/formula, \"line\" rendering, and no "
        "hidden series. Returns the updated notebook and its URL.")
   :input-schema
   (js/object-schema
     {:id {:type "string" :description "Notebook id to update."}
      :name {:type "string" :minLength 1 :maxLength 255}
      :description {:type ["string" "null"]}
      :cells {:type "array" :maxItems 50 :items notebook-cell-json-schema}}
     [:id])
   :annotations {:readOnlyHint false :destructiveHint true :idempotentHint false
                 :openWorldHint false}
   :scope "write"
   :handler
   (fn [{:keys [deps] :as context} args]
     (support/validate! update-notebook-args args)
     (let [sqlite (:sqlite deps)
           id (:id args)
           existing (notebook/get-notebook-by-id sqlite id)]
       (when-not existing
         (throw (protocol/tool-error (str "Notebook not found: " id
                                          ". Use list_notebooks to find ids."))))
       (let [notebook-params (cond-> {:name (:name existing)
                                      :description (:description existing)}
                               (contains? args :name) (assoc :name (:name args))
                               (contains? args :description) (assoc :description (:description args)))
             cells (vec (:cells args))]
         (when-let [error (notebook-schema/validate-notebook notebook-params)]
           (throw (protocol/tool-error "Invalid notebook" (:error error))))
         (when (contains? args :cells)
           (doseq [[i cell] (map-indexed vector cells)]
             (when-let [error (notebook-schema/validate-cell (-cell-params cell))]
               (throw (protocol/tool-error (str "Invalid cell at index " i) (:error error))))))
         (if (contains? args :cells)
           (notebook/update-notebook-with-cells!
             sqlite id notebook-params
             (mapv #(-> % -cell-params (assoc :id (-new-id))) cells))
           (notebook/update-notebook! sqlite id notebook-params))
         {:notebook (notebook/get-notebook-by-id sqlite id)
          :url (-notebook-url context id)})))})

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  ((:handler list-alert-rules) {:deps {:sqlite (:db/sqlite system)}} {})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
