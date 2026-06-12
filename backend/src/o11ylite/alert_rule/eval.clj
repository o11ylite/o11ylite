;; ---------------------------------------------------------
;; o11ylite.alert-rule.eval
;;
;; Alert rule evaluation engine.
;;
;; Each tick, per rule: run the stored query, derive the set of present
;; fingerprints (groups), then drive a mode-aware state machine over the
;; union of stored and present fingerprints. The state machine itself
;; lives as data in o11ylite.alert-rule.transitions; this namespace is
;; the generic engine that consumes it and persists the results.
;;
;; State determination (controlled by alert_on mode):
;;   alert_on = "result"    (match):   a group present in results breaches
;;   alert_on = "no_result" (absence): a group absent from results breaches
;;
;; A rule with no group-by is the degenerate case: a single instance with
;; the empty fingerprint. Same table, same state machine, same path.
;;
;; On evaluation failure (validation error, exception), the tick is
;; skipped entirely: no instance transitions, the rule keeps its previous
;; state, and the error is recorded in last_eval_error. A broken
;; evaluation is an operational problem, not an alert condition.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.eval
  (:require
    [o11ylite.alert-rule.fingerprint :as fingerprint]
    [o11ylite.alert-rule.instance-store :as instance-store]
    [o11ylite.alert-rule.notify :as notify]
    [o11ylite.alert-rule.store :as store]
    [o11ylite.alert-rule.transitions :as transitions]
    [o11ylite.store.events.query :as events.query]
    [o11ylite.store.metrics.query :as metrics.query]
    [o11ylite.store.schema :as schema]
    [o11ylite.util.telemetry :as telemetry]
    [steffan-westcott.clj-otel.api.trace.span :as span]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

(defn- -inject-time-range
  "Inject a computed time_range into the stored query based on eval_window_ms."
  [query eval-window-ms]
  (let [now (-now-ms)]
    (assoc query :time_range {:start (- now eval-window-ms)
                              :end now})))

(defn- -ensure-table-viz
  "Ensure query uses table visualization for evaluation.
   Alert evaluation always uses table mode to get concrete rows."
  [query]
  (assoc query :visualization {:type "table"}))

(defn- -group-fields
  "Group-by field names declared on a query, or empty."
  [query]
  (vec (:group_by query)))

(defn- -row->group
  "Split a result row into {:labels ... :value ...} given the group-by
   fields. Labels are the group-by columns; value is everything else
   (the breaching values), which match rules surface in webhooks."
  [row group-fields]
  (let [label-keys (map keyword group-fields)
        labels (select-keys row label-keys)
        value (apply dissoc row label-keys)]
    {:labels labels
     :value (not-empty value)}))

(defn- -events-presence
  "Derive present groups from an events table result.
   Returns a map fingerprint -> {:labels ... :value ...}.
   For an ungrouped rule, any rows collapse to the empty fingerprint."
  [result group-fields]
  (let [rows (get-in result [:data :rows])]
    (if (empty? group-fields)
      (when (seq rows) {fingerprint/empty-fingerprint {:labels {} :value nil}})
      (reduce (fn [acc row]
                (let [{:keys [labels value]} (-row->group row group-fields)
                      fp (fingerprint/fingerprint labels)]
                  (assoc acc fp {:labels labels :value value})))
              {}
              rows))))

(defn- -filter-by-target
  "When alert-target is set, keep only series whose :id matches."
  [series alert-target]
  (if alert-target
    (filterv #(= alert-target (:id %)) series)
    series))

(defn- -latest-value
  "The most recent datapoint value of a metrics series within the window.
   Series data is ordered ascending by timestamp, so the last point is
   newest. Returned as the value annotation source for match alerts."
  [series]
  (when-let [point (last (:data series))]
    {:value (:value point)}))

(defn- -metrics-presence
  "Derive present groups from a metrics result. A series is present iff
   it contributes at least one datapoint within the window; a series with
   zero datapoints is absent. Keyed by its labels' fingerprint.
   When alert-target is set, only that metric/formula's series count."
  [result alert-target group-fields]
  (let [series (-filter-by-target (get-in result [:data :series]) alert-target)
        with-data (filter #(pos? (count (:data %))) series)]
    (if (empty? group-fields)
      (when-let [s (first with-data)]
        {fingerprint/empty-fingerprint {:labels {} :value (-latest-value s)}})
      (reduce (fn [acc s]
                (let [labels (:labels s)
                      fp (fingerprint/fingerprint labels)]
                  (assoc acc fp {:labels labels :value (-latest-value s)})))
              {}
              with-data))))

(defn- -eval-presence
  "Run a rule's query and return either
     {:present {fp -> {:labels :value}} :error nil}
   or
     {:present nil :error \"message\"}
   on validation failure or exception. The caller skips the tick on error."
  [duckdb sqlite {qmode :query_mode query :query
                  eval-win :eval_window_ms
                  alert-target :alert_target}]
  (let [group-fields (-group-fields query)]
    (try
      (case qmode
        "events"
        (let [full-query (-> query (-inject-time-range eval-win) -ensure-table-viz)
              fields (schema/fetch-event-fields duckdb)]
          (if-let [validation-error (events.query/validate fields full-query)]
            {:present nil :error (str "Validation error: " (:error validation-error))}
            (let [result (events.query/execute duckdb full-query)]
              {:present (or (-events-presence result group-fields) {}) :error nil})))

        "metrics"
        (let [metrics-query (-inject-time-range query eval-win)]
          (if-let [validation-error (metrics.query/validate sqlite duckdb metrics-query)]
            {:present nil :error (str "Validation error: " (:error validation-error))}
            ;; :single-bucket? collapses every metric to one row per
            ;; group_by combination over the full eval window so HAVING
            ;; applies to the full window, not per sub-bucket.
            (let [result (metrics.query/execute duckdb sqlite metrics-query
                                                {:single-bucket? true})]
              {:present (or (-metrics-presence result alert-target group-fields) {}) :error nil})))

        {:present nil :error (str "Unknown query_mode: " qmode)})
      (catch Exception e
        (telemetry/report-error! ::evaluation-error e)
        {:present nil :error (.getMessage e)}))))

;; ---------------------------------------------------------
;; Instance state machine

(defn- -drive-instance!
  "Apply the state machine to one (rule, fingerprint). `stored` is the
   existing instance row or nil. `present` is {:labels :value} when the
   group appeared this tick, else nil. Persists the result and returns a
   notification map {:status ... :labels ... :value ... :fingerprint ...
   :started_at ... :resolved_at ...} when the transition notifies, else nil."
  [sqlite rule mode now fp stored present]
  (let [stored-state (if stored (keyword (:state stored)) :none)
        present? (some? present)
        outcome (transitions/step mode stored-state present?)]
    (when outcome
      (let [next-state (:next outcome)
            labels (or (:labels present) (:labels stored) {})
            first-seen (or (:first_seen stored) now)
            started-at (if (= next-state :firing)
                         (or (:started_at stored) now)
                         (:started_at stored))
            resolved-at (when (= next-state :resolved) now)
            last-value (if (:update-value? outcome)
                         (:value present)
                         (:last_value stored))
            row {:rule_id (:id rule)
                 :fingerprint fp
                 :labels labels
                 :state next-state
                 :first_seen first-seen
                 :last_seen now
                 :started_at started-at
                 :resolved_at resolved-at
                 :last_value last-value}]
        (instance-store/upsert! sqlite row)
        (when (:delete? outcome)
          (instance-store/delete! sqlite (:id rule) fp))
        (when-let [notify (:notify outcome)]
          {:status (name notify)
           :labels labels
           :value last-value
           :fingerprint fp
           :started_at started-at
           :resolved_at resolved-at})))))

(defn- -rollup-state
  "Worst-wins rule-level state from the rule's instances: :firing if any
   instance is firing, otherwise :ok."
  [sqlite rule-id]
  (if (seq (instance-store/list-by-rule-state sqlite rule-id :firing))
    :firing
    :ok))

;; ---------------------------------------------------------
;; Public API

(def ^:private -max-instances-per-rule
  "Cardinality cap. Once a rule holds this many instances, new
   fingerprints are not minted; a single meta-alert fires on the rule."
  500)

(defn- -generator-url
  "Deep link back to the rule in the UI. Absent a configured public base
   URL, a relative path is the honest best we can do."
  [rule-id]
  (str "/alert-rules/" rule-id "/edit"))

(defn- -meta-alert
  "Synthetic firing notification on the rule itself when the cardinality
   cap is hit. Labels carry the rule id and the cap so the receiver can
   see the rule is shedding groups."
  [rule]
  {:status "firing"
   :labels {:o11ylite_alert "cardinality_cap"}
   :value {:max_instances -max-instances-per-rule}
   :fingerprint "__meta_cardinality__"
   :started_at (-now-ms)})

(defn evaluate-rule!
  "Evaluate a single rule: run its query, drive the per-instance state
   machine, recompute the rule's rollup state, and collect the instance
   transitions that should notify this tick.

   Returns {:state :ok|:firing :error nil :notifications [...]} on
   success, or {:state nil :error string :notifications []} on failure
   (caller preserves prev state, no instance transitions happen)."
  [duckdb sqlite {:keys [id alert_on query] :as rule}]
  (let [{:keys [present error]} (-eval-presence duckdb sqlite rule)]
    (if error
      {:state nil :error error :notifications []}
      (let [mode (transitions/alert-on->mode alert_on)
            now (-now-ms)
            stored (instance-store/list-by-rule sqlite id)
            stored-by-fp (into {} (map (juxt :fingerprint identity)) stored)
            stored-count (count stored-by-fp)
            ;; At the cap, drive only fingerprints that already have a row
            ;; (so existing instances still resolve); drop net-new ones.
            capped? (>= stored-count -max-instances-per-rule)
            present-fps (cond->> (keys present)
                          capped? (filter #(contains? stored-by-fp %)))
            ;; A rule without group-by has exactly one group — the empty
            ;; fingerprint — and it is always tracked, even before any
            ;; eval has seen rows. This is what lets an ungrouped absence
            ;; rule fire the first time results come back empty.
            ungrouped? (empty? (:group_by query))
            fps (cond-> (into (set (keys stored-by-fp)) present-fps)
                  ungrouped? (conj fingerprint/empty-fingerprint))
            notifications (into []
                                (keep (fn [fp]
                                        (-drive-instance! sqlite rule mode now fp
                                                          (get stored-by-fp fp)
                                                          (get present fp))))
                                fps)
            notifications (cond-> notifications
                            (and capped? (seq (remove #(contains? stored-by-fp %) (keys present))))
                            (conj (-meta-alert rule)))]
        {:state (-rollup-state sqlite id)
         :error nil
         :notifications notifications}))))

;; ---------------------------------------------------------
;; Evaluation Cycle Orchestration

(defn- -rule-ctx
  "Rule-level context shared by every alert entry in a batch."
  [{:keys [id name description]}]
  {:id id
   :name name
   :description description
   :rule-labels {}
   :generator-url (-generator-url id)})

(defn- -evaluate-and-notify!
  "Evaluate a single rule, persist its rollup state, and send one batched
   webhook for all instance transitions this tick. On evaluation failure
   (state is nil), preserves previous state and sends nothing."
  [duckdb sqlite webhook-url rule]
  (let [{:keys [id state]} rule
        prev-state (keyword state)]
    (span/with-span! [::evaluate-rule {:o11ylite.alert_rule.id id
                                       :o11ylite.alert_rule.prev_state prev-state}]
      (let [{:keys [state error notifications]} (evaluate-rule! duckdb sqlite rule)
            new-state (or state prev-state)]
        (span/add-span-data! {:attributes {:o11ylite.alert_rule.new_state new-state
                                           :o11ylite.alert_rule.error error}})
        ;; Update rule rollup state (records last_eval_at and error even on failure)
        (store/update-eval-result! sqlite id new-state error prev-state)
        (notify/send-batch! webhook-url (-rule-ctx rule) notifications)))))

(defn run-evaluation-cycle!
  "Evaluate all due alert rules and send notifications.
   Called by the scheduler on each tick.

   Current implementation fetches all due rules and evaluates them
   sequentially within a single scheduler tick. Appropriate for
   small-to-moderate rule counts (< ~50 rules)."
  [duckdb sqlite webhook-url]
  (let [due-rules (store/get-enabled-due sqlite)]
    (when (seq due-rules)
      (span/with-span! [::evaluation-cycle {:o11ylite.alert_rule.rule_count (count due-rules)}]
        (doseq [rule due-rules]
          (-evaluate-and-notify! duckdb sqlite webhook-url rule))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def duckdb (:db/duckdb-reader system))
  (def sqlite (:db/sqlite system))

  ;; Evaluate one rule
  (require '[o11ylite.alert-rule.store :as store])
  (evaluate-rule! duckdb sqlite (first (store/list-all sqlite)))

  ;; Run a full cycle (no webhook)
  (run-evaluation-cycle! duckdb sqlite nil)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
