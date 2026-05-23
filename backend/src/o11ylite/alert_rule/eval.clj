;; ---------------------------------------------------------
;; o11ylite.alert-rule.eval
;;
;; Alert rule evaluation engine.
;; Executes a rule's stored query against the existing query
;; infrastructure and determines the resulting alert state.
;;
;; State determination (controlled by alert_on mode):
;;   alert_on = "result" (default):
;;     - Query returns non-empty results -> :firing
;;     - Query returns empty results     -> :ok
;;   alert_on = "no_result" (absence detection):
;;     - Query returns non-empty results -> :ok
;;     - Query returns empty results     -> :firing
;;
;; On evaluation failure (validation error, exception), the rule
;; keeps its previous state. The error is recorded in last_eval_error.
;; A broken evaluation is
;; an operational problem, not an alert condition.

;; Scaling note (see eval namespace for implementation details):
;; Current implementation fetches all due rules and evaluates them
;; sequentially within a single scheduler tick. This is appropriate for
;; small-to-moderate rule counts (< ~50 rules).
;;
;; For higher scale:
;; 1. Partition rules into batches and evaluate each batch in a
;;    separate virtual thread (pmap or executor-based fan-out).
;; 2. Add a configurable concurrency limit to bound DuckDB connection
;;    usage (e.g., 4 concurrent evaluations).
;; 3. Consider staggering rule evaluation times to avoid thundering
;;    herd on shared eval_interval_ms values.
;; 4. For very large rule sets (hundreds+), introduce a priority queue
;;    sorted by next_eval_at and process top-N per tick.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.eval
  (:require
    [o11ylite.alert-rule.notify :as notify]
    [o11ylite.alert-rule.store :as store]
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

(defn- -events-result-firing?
  "Check if an events query result indicates a firing state.
   Non-empty rows means the condition is met."
  [result]
  (pos? (count (get-in result [:data :rows]))))

(defn- -filter-by-target
  "When alert-target is set, keep only series whose :id matches.
   Otherwise return series unchanged."
  [series alert-target]
  (if alert-target
    (filterv #(= alert-target (:id %)) series)
    series))

(defn- -metrics-result-firing?
  "Check if a metrics query result indicates a firing state.
   When alert-target is set, only series matching that id are considered.
   Any non-empty series with data points means the condition is met."
  [result alert-target]
  (let [series (get-in result [:data :series])]
    (some #(pos? (count (:data %)))
          (-filter-by-target series alert-target))))

(defn- -resolve-state
  "Determine alert state from query result emptiness and alert_on mode.
   In 'result' mode (default), non-empty results mean firing.
   In 'no_result' mode, empty results mean firing (absence detection)."
  [has-data? alert-on]
  (case alert-on
    "no_result" (if has-data? :ok :firing)
    ;; default: "result"
    (if has-data? :firing :ok)))

;; ---------------------------------------------------------
;; Public API

(defn evaluate-rule
  "Evaluate a single alert rule.
   Executes the stored query and determines the resulting state.
   The alert_on field controls interpretation: 'result' (default) fires on
   non-empty results, 'no_result' fires on empty results (absence detection).

   Returns {:state :ok|:firing, :error nil} on success,
   or {:state nil, :error string} on failure (caller preserves prev state)."
  [duckdb sqlite {qmode :query_mode query :query
                  eval-win :eval_window_ms
                  alert-on :alert_on
                  alert-target :alert_target}]
  (try
    (let [full-query (-> query
                         (-inject-time-range eval-win)
                         -ensure-table-viz)]
      (case qmode
        "events"
        (let [fields (schema/fetch-event-fields duckdb)]
          (if-let [validation-error (events.query/validate fields full-query)]
            {:state nil :error (str "Validation error: " (:error validation-error))}
            (let [result (events.query/execute duckdb full-query)]
              {:state (-resolve-state (-events-result-firing? result) alert-on)
               :error nil})))

        "metrics"
        (let [metrics-query (-inject-time-range query eval-win)]
          (if-let [validation-error (metrics.query/validate sqlite duckdb metrics-query)]
            {:state nil :error (str "Validation error: " (:error validation-error))}
            ;; :single-bucket? collapses every metric to one row per
            ;; group_by combination over the full eval window so HAVING
            ;; applies to the full window, not per sub-bucket.
            (let [result (metrics.query/execute duckdb sqlite metrics-query
                                                {:single-bucket? true})
                  firing? (-metrics-result-firing? result alert-target)]
              {:state (-resolve-state firing? alert-on)
               :error nil})))

        ;; Unknown mode
        {:state nil :error (str "Unknown query_mode: " qmode)}))
    (catch Exception e
      (telemetry/report-error! ::evaluation-error e)
      {:state nil :error (.getMessage e)})))

;; ---------------------------------------------------------
;; Evaluation Cycle Orchestration

(defn- -evaluate-and-notify!
  "Evaluate a single rule and send notification if appropriate.
   On evaluation failure (state is nil), preserves previous state."
  [duckdb sqlite webhook-url rule]
  (let [{:keys [id state]} rule
        prev-state (keyword state)]
    (span/with-span! [::evaluate-rule {:o11ylite.alert_rule.id id
                                       :o11ylite.alert_rule.prev_state prev-state}]
      (let [{:keys [state error]} (evaluate-rule duckdb sqlite rule)
            new-state (or state prev-state)]
        (span/add-span-data! {:attributes {:o11ylite.alert_rule.new_state new-state
                                           :o11ylite.alert_rule.error error}})
        ;; Update DB state (records last_eval_at and error even on failure)
        (store/update-eval-result! sqlite id new-state error prev-state)
        ;; Send webhook notification
        (notify/maybe-send-webhook! webhook-url rule new-state prev-state)))))

(defn run-evaluation-cycle!
  "Evaluate all due alert rules and send notifications.
   Called by the scheduler on each tick.

   See facade namespace for scaling considerations."
  [duckdb sqlite webhook-url]
  (let [due-rules (store/get-enabled-due sqlite)]
    (when (seq due-rules)
      (span/with-span! [::evaluation-cycle {:o11ylite.alert_rule.rule_count (count due-rules)}]
        (doseq [rule due-rules]
          (-evaluate-and-notify! duckdb sqlite webhook-url rule))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example rule structure for evaluation
  (def sample-rule
    {:query_mode "events"
     :query {:filter {:field "error" :op "=" :value true}
             :aggregations [{:id "A" :field "*" :function "count"}]
             :having {:ref "A" :op ">" :value 100}
             :visualization {:type "table"}}
     :eval_window_ms 300000})

  ;; Evaluate would be called as:
  ;; (evaluate-rule duckdb sqlite sample-rule)

  ;; To run full evaluation cycle:
  ;; (run-evaluation-cycle! duckdb sqlite nil)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
