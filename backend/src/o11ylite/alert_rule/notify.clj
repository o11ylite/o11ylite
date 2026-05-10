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
    [jsonista.core :as json]
    [o11ylite.util.telemetry :as telemetry]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.time Instant]
    [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------
;; Private Helpers

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

(defn- -build-payload
  "Build Alertmanager POST body."
  [{:keys [id name description state_changed_at]} status]
  (let [now-ms (System/currentTimeMillis)
        starts-at (-epoch-ms->rfc3339 (or state_changed_at now-ms))
        ends-at (when (= status "resolved") now-ms)
        labels {"alertname" name
                "source" "o11ylite"}
        annotations (cond-> {}
                      description (assoc "description" description))]
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
                                 :timeout 5000})
        status (:status response)]
    (span/add-span-data! {:attributes {:http.response.status_code status}})
    (when (>= status 400)
      (span/add-span-data! {:attributes {:o11ylite.alert_rule.webhook_response_body (:body response)}}))))

;; ---------------------------------------------------------
;; Public API

(defn maybe-send-webhook!
  "Send Alertmanager-compatible webhook if appropriate.
   Returns nil. Logs errors but does not throw."
  [webhook-url rule new-state prev-state]
  (when webhook-url
    (let [should-send? (or (= new-state :firing)
                           (and (= prev-state :firing) (= new-state :ok)))
          status (case new-state
                   :firing "firing"
                   :ok "resolved"
                   nil)]
      (when (and should-send? status)
        (span/with-span! [::send-webhook {:o11ylite.alert_rule.id (:id rule)
                                          :o11ylite.alert_rule.webhook_status status
                                          :url.full webhook-url}]
          (try
            (let [payload (-build-payload rule status)
                  body (json/write-value-as-string payload)]
              (-send-http-post! webhook-url body))
            (catch Exception e
              (telemetry/report-error! ::webhook-send-error e))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Build a sample payload
  (-build-payload
    {:id "12345"
     :name "High error rate"
     :description "Error count exceeded threshold"
     :state_changed_at 1702000000000}
    "firing")

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
