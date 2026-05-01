;; ---------------------------------------------------------
;; o11ylite.alert-rule.notify
;;
;; Alertmanager-compatible webhook notification dispatch.
;; Sends HTTP POST with v4 webhook payload format.
;;
;; Notification policy:
;;   - Sends on every eval while state is :firing
;;     (Alertmanager expects repeated alerts to know they're still active)
;;   - Sends once when transitioning from :firing to :ok (resolved)
;;   - Silent when state is :ok and was already :ok
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.notify
  (:require
    [babashka.http-client :as http]
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mulog]
    [jsonista.core :as json])
  (:import
    [java.time Instant]
    [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------
;; Private Helpers — formatting

(defn- -epoch-ms->rfc3339
  "Convert epoch milliseconds to RFC3339 string."
  [epoch-ms]
  (when epoch-ms
    (.format DateTimeFormatter/ISO_INSTANT
             (Instant/ofEpochMilli epoch-ms))))

(defn- -fingerprint
  "Generate a stable fingerprint from a rule ID."
  [rule-id]
  (format "%016x" (hash rule-id)))

(defn- -format-number
  "Trim trailing .0 from doubles that are integer-valued for nicer display."
  [v]
  (cond
    (nil? v) "null"
    (and (number? v) (== (double v) (long v))) (str (long v))
    (number? v) (format "%.4g" (double v))
    :else (str v)))

(def ^:private ^:const max-breach-rows 5)

;; ---------------------------------------------------------
;; Private Helpers — events result summarization

(defn- -row-label
  "Render a single events row as a compact `k=v, k=v` label.
   Drops keys that look like timestamps or have nil values, keeping
   group-by + aggregation columns which are what the user cares about."
  [row]
  (->> row
       (remove (fn [[k v]]
                 (or (nil? v)
                     (#{:timestamp :start_ms :end_ms :bucket_ms} k))))
       (map (fn [[k v]] (str (name k) "=" (-format-number v))))
       (str/join ", ")))

(defn- -summarize-events-result
  "Build {:summary, :details} strings from an events query result.
   `result` shape: {:data {:rows [{...}] :total_count N :columns [...]}}.
   Returns nil-valued keys when the result is empty (no_result firing)."
  [result]
  (let [rows (get-in result [:data :rows])
        total (or (get-in result [:data :total_count]) (count rows))]
    (if (seq rows)
      (let [top (take max-breach-rows rows)
            labels (mapv -row-label top)
            more (- total (count top))
            details-lines (cond-> labels
                            (pos? more) (conj (str "... and " more " more")))]
        {:summary (format "%d group(s) breached: %s"
                          total
                          (str/join " | " (take 3 labels)))
         :details (str/join "\n" details-lines)})
      {:summary nil :details nil})))

;; ---------------------------------------------------------
;; Private Helpers — metrics result summarization

(defn- -last-data-point
  "Return the most recent {:timestamp :value} of a series, or nil if empty."
  [series]
  (last (:data series)))

(defn- -series-label
  "Render a metrics series as `name{labels}=value` for breach summaries."
  [series]
  (let [labels (some->> (:labels series)
                        seq
                        (map (fn [[k v]] (str (name k) "=" v)))
                        (str/join ","))
        last-pt (-last-data-point series)
        v (some-> last-pt :value -format-number)
        base (or (:name series) (:id series))]
    (str base
         (when (seq labels) (str "{" labels "}"))
         (when v (str "=" v)))))

(defn- -summarize-metrics-result
  "Build {:summary, :details} strings from a metrics query result.
   `result` shape: {:data {:series [{:id :name :labels :data}] :bucket_ms ...}}.
   When `alert-target` is set, restrict to that ref so the summary matches
   what actually fired."
  [result alert-target]
  (let [all-series (get-in result [:data :series])
        series (cond->> all-series
                 alert-target (filterv #(= alert-target (:id %))))
        breaching (filterv #(seq (:data %)) series)]
    (if (seq breaching)
      (let [top (take max-breach-rows breaching)
            labels (mapv -series-label top)
            more (- (count breaching) (count top))
            details-lines (cond-> labels
                            (pos? more) (conj (str "... and " more " more")))]
        {:summary (format "%d series breached: %s"
                          (count breaching)
                          (str/join " | " (take 3 labels)))
         :details (str/join "\n" details-lines)})
      {:summary nil :details nil})))

;; ---------------------------------------------------------
;; Private Helpers — payload assembly

(defn- -summary-for
  "Pick the right summarizer based on rule shape, defensive against missing data.
   Returns {:summary, :details}; values may be nil (e.g. resolved alert,
   no_result firing with empty rows, or evaluation error path)."
  [{:keys [query_mode alert_target alert_on]} status result]
  (cond
    (nil? result) {:summary nil :details nil}

    (and (= status "firing") (= alert_on "no_result"))
    {:summary "Query returned no results (no_result mode firing)."
     :details nil}

    (= query_mode "events") (-summarize-events-result result)
    (= query_mode "metrics") (-summarize-metrics-result result alert_target)
    :else {:summary nil :details nil}))

(defn- -build-payload
  "Build Alertmanager POST body. Result-derived breach context is included
   when available (firing path with non-empty result data)."
  [{:keys [id name description state_changed_at eval_window_ms] :as rule}
   status
   result]
  (let [now-ms (System/currentTimeMillis)
        starts-at (-epoch-ms->rfc3339 (or state_changed_at now-ms))
        ends-at (when (= status "resolved") now-ms)
        {:keys [summary details]} (-summary-for rule status result)
        ;; Labels are kept minimal: only the structural keys Alertmanager
        ;; expects (alertname) plus a constant source. Rule-shape metadata
        ;; (query_mode, alert_on, alert_target, eval_window) is *not* useful
        ;; for routing/grouping and would just pollute the label namespace.
        ;; User-defined labels are a planned follow-up.
        labels {"alertname" name
                "source" "o11ylite"}
        annotations (cond-> {}
                      description (assoc "description" description)
                      summary (assoc "summary" summary)
                      details (assoc "breach_details" details)
                      eval_window_ms (assoc "eval_window_ms"
                                            (str eval_window_ms)))]
    [{:labels labels
      :annotations annotations
      :startsAt starts-at
      :endsAt (if ends-at (-epoch-ms->rfc3339 ends-at) "0001-01-01T00:00:00Z")
      :generatorURL ""
      :fingerprint (-fingerprint id)}]))

(defn- -send-http-post!
  "Send an HTTP POST with JSON body. Fire-and-forget with timeout."
  [url body-str]
  (let [response (http/post url {:headers {"Content-Type" "application/json"}
                                 :body body-str
                                 :throw false
                                 :timeout 5000})]
    (when (>= (:status response) 400)
      (mulog/log ::webhook-error
                 :url url
                 :status (:status response)
                 :body (:body response)))))

;; ---------------------------------------------------------
;; Public API

(defn maybe-send-webhook!
  "Send Alertmanager-compatible webhook if appropriate.
   `result` is the query result that drove the evaluation, used to enrich
   the payload with breach context. Pass nil when a result isn't available
   (e.g. evaluation error path).

   Returns nil. Logs errors but does not throw."
  [webhook-url rule new-state prev-state result]
  (when webhook-url
    (let [should-send? (or (= new-state :firing)
                           (and (= prev-state :firing) (= new-state :ok)))
          status (case new-state
                   :firing "firing"
                   :ok "resolved"
                   nil)]
      (when (and should-send? status)
        (try
          (let [payload (-build-payload rule status result)
                body (json/write-value-as-string payload)]
            (mulog/log ::sending-webhook
                       :rule-id (:id rule)
                       :status status
                       :url webhook-url)
            (-send-http-post! webhook-url body))
          (catch Exception e
            (mulog/log ::webhook-send-error
                       :rule-id (:id rule)
                       :error (.getMessage e))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Build a sample events payload with breach context
  (-build-payload
    {:id "12345"
     :name "High error rate"
     :description "Error count exceeded threshold"
     :state_changed_at 1702000000000
     :query_mode "events"
     :eval_window_ms 300000
     :alert_on "result"}
    "firing"
    {:data {:rows [{:service "api" :count 147}
                   {:service "worker" :count 132}]
            :total_count 2}})

  ;; Build a sample metrics payload
  (-build-payload
    {:id "abc" :name "CPU critical" :query_mode "metrics"
     :eval_window_ms 60000 :alert_on "result"}
    "firing"
    {:data {:series [{:id "A" :name "cpu.utilization"
                      :labels {"host" "node-1"}
                      :data [{:timestamp 1 :value 95.5}]}]}})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
