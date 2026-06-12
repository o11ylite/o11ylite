;; ---------------------------------------------------------
;; o11ylite.alert-rule.notify
;;
;; Alertmanager-compatible webhook notification dispatch.
;;
;; One POST per rule per tick, carrying every instance transition from
;; that tick batched into the Alertmanager `alerts` array. Each entry has
;; its own status/labels/annotations/startsAt/endsAt/generatorURL, so a
;; grouped rule reports per-group fire/resolve in a single request and an
;; ungrouped rule degenerates to an array-of-one.
;;
;; Notifications are transition-driven: an instance that fires notifies
;; once on fire and once on resolve. A still-firing instance does not
;; re-notify each tick (repeat routing is out of scope).
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.notify
  (:require
    [babashka.http-client :as http]
    [clojure.string :as str]
    [jsonista.core :as json]
    [o11ylite.util.telemetry :as telemetry]
    [steffan-westcott.clj-otel.api.trace.span :as span])
  (:import
    [java.time Instant]
    [java.time.format DateTimeFormatter]))

;; ---------------------------------------------------------
;; Private Helpers

(def ^:private -zero-time
  "Alertmanager's zero timestamp, used for endsAt while an alert is firing."
  "0001-01-01T00:00:00Z")

(defn- -epoch-ms->rfc3339
  "Convert epoch milliseconds to RFC3339 string."
  [epoch-ms]
  (when epoch-ms
    (.format DateTimeFormatter/ISO_INSTANT (Instant/ofEpochMilli epoch-ms))))

(defn- -stringify-labels
  "Coerce a label map's keys and values to strings for the labels object."
  [labels]
  (reduce-kv (fn [acc k v] (assoc acc (name k) (str v))) {} (or labels {})))

(defn- -value->string
  "Render an instance's last_value for the value annotation. A single
   scalar renders bare; a map renders as comma-joined k=v pairs."
  [value]
  (cond
    (nil? value) nil
    (map? value) (->> value
                      (map (fn [[k v]] (str (name k) "=" v)))
                      sort
                      (str/join ", "))
    :else (str value)))

(defn- -notification->alert
  "Build one Alertmanager alert entry from an instance notification."
  [{:keys [name description rule-labels generator-url]}
   {:keys [status labels value fingerprint started_at resolved_at reason]}]
  (let [merged-labels (merge {"alertname" name "source" "o11ylite"}
                             (-stringify-labels rule-labels)
                             (-stringify-labels labels))
        value-str (-value->string value)
        annotations (cond-> {}
                      description (assoc "description" description)
                      value-str (assoc "value" value-str)
                      reason (assoc "reason" reason))]
    {:status status
     :labels merged-labels
     :annotations annotations
     :startsAt (or (-epoch-ms->rfc3339 started_at) -zero-time)
     :endsAt (if (= status "resolved")
               (or (-epoch-ms->rfc3339 resolved_at) (-epoch-ms->rfc3339 (System/currentTimeMillis)))
               -zero-time)
     :generatorURL (or generator-url "")
     :fingerprint (str fingerprint)}))

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

(defn build-payload
  "Build the Alertmanager array body for a rule's batch of instance
   notifications. `rule-ctx` carries rule-level fields (name, description,
   rule-labels, generator-url); `notifications` is the per-instance batch."
  [rule-ctx notifications]
  (mapv #(-notification->alert rule-ctx %) notifications))

(defn send-batch!
  "POST a rule's batch of instance notifications as a single Alertmanager
   array. No-op when there is no webhook URL or no notifications.
   Logs errors but does not throw."
  [webhook-url rule-ctx notifications]
  (when (and webhook-url (seq notifications))
    (span/with-span! [::send-webhook {:o11ylite.alert_rule.id (:id rule-ctx)
                                      :o11ylite.alert_rule.alert_count (count notifications)
                                      :url.full webhook-url}]
      (try
        (let [payload (build-payload rule-ctx notifications)
              body (json/write-value-as-string payload)]
          (-send-http-post! webhook-url body))
        (catch Exception e
          (telemetry/report-error! ::webhook-send-error e))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (build-payload
    {:id "12345" :name "High error rate" :description "errors spiked"
     :generator-url "https://o11y.example/alert-rules/12345/edit"}
    [{:status "firing" :labels {:service "api"} :value {:error_rate 0.12}
      :fingerprint "abc" :started_at 1702000000000}
     {:status "resolved" :labels {:service "web"} :fingerprint "def"
      :started_at 1702000000000 :resolved_at 1702000600000 :reason "dismissed"}])

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
