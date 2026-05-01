;; ---------------------------------------------------------
;; o11ylite.alert-rule.notify-test
;;
;; Unit tests for the Alertmanager-compatible payload builder.
;; Tests are pure function checks against the private builder via
;; #' var access — no system fixture required.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.notify-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [o11ylite.alert-rule.notify :as notify]))

;; ---------------------------------------------------------
;; Helpers

(def ^:private build-payload @#'notify/-build-payload)

(defn- single-alert
  "Builders return a vector with one alert map; pull it out."
  [payload]
  (first payload))

(def base-rule
  {:id "rule-123"
   :name "High error rate"
   :description "Errors exceed threshold"
   :state_changed_at 1702000000000
   :query_mode "events"
   :eval_window_ms 300000
   :alert_on "result"})

;; ---------------------------------------------------------
;; Labels

(deftest labels-are-minimal-test
  (testing "labels carry only alertname + source — rule-shape metadata stays out"
    (let [rule (assoc base-rule
                      :query_mode "metrics"
                      :alert_on "no_result"
                      :alert_target "F1")
          {:keys [labels]} (single-alert (build-payload rule "firing" nil))]
      (is (= #{"alertname" "source"} (set (keys labels)))
          "labels should be exactly alertname + source")
      (is (= "High error rate" (get labels "alertname")))
      (is (= "o11ylite" (get labels "source")))
      (is (nil? (get labels "query_mode"))
          "query_mode is not a routing key — keep it out of labels")
      (is (nil? (get labels "alert_on"))
          "alert_on is not a routing key — keep it out of labels")
      (is (nil? (get labels "alert_target"))
          "alert_target is not a routing key — keep it out of labels")
      (is (nil? (get labels "eval_window"))
          "eval_window is not a routing key — eval_window_ms in annotations is enough"))))

;; ---------------------------------------------------------
;; Annotations — events firing path

(deftest events-firing-includes-breach-summary-test
  (testing "events firing payload includes summary + details from rows"
    (let [result {:data {:rows [{:service "api" :count 147}
                                {:service "worker" :count 132}]
                         :total_count 2}}
          {:keys [annotations]} (single-alert (build-payload base-rule "firing" result))]
      (is (= "Errors exceed threshold" (get annotations "description")))
      (is (str/includes? (get annotations "summary") "2 group(s) breached"))
      (is (str/includes? (get annotations "summary") "service=api"))
      (is (str/includes? (get annotations "summary") "count=147"))
      (let [details (get annotations "breach_details")]
        (is (str/includes? details "service=api, count=147"))
        (is (str/includes? details "service=worker, count=132"))))))

(deftest events-firing-truncates-large-result-sets-test
  (testing "details list truncates after max rows and notes the remainder"
    (let [rows (mapv (fn [i] {:service (str "svc-" i) :count (+ 100 i)}) (range 10))
          result {:data {:rows rows :total_count (count rows)}}
          {:keys [annotations]} (single-alert (build-payload base-rule "firing" result))
          details (get annotations "breach_details")]
      (is (str/includes? details "svc-0"))
      (is (str/includes? details "svc-4"))
      (is (str/includes? details "and 5 more"))
      (is (not (str/includes? details "svc-5"))
          "rows beyond the cap should not appear individually"))))

;; ---------------------------------------------------------
;; Annotations — metrics firing path

(deftest metrics-firing-includes-series-summary-test
  (testing "metrics firing payload renders series with name, labels, and last value"
    (let [rule (assoc base-rule :query_mode "metrics")
          result {:data {:series [{:id "A" :name "cpu.utilization"
                                   :labels {"host.name" "node-1"}
                                   :data [{:timestamp 1 :value 95.5}]}
                                  {:id "A" :name "cpu.utilization"
                                   :labels {"host.name" "node-2"}
                                   :data [{:timestamp 1 :value 91.2}]}]}}
          {:keys [annotations]} (single-alert (build-payload rule "firing" result))]
      (is (str/includes? (get annotations "summary") "2 series breached"))
      (is (str/includes? (get annotations "breach_details") "cpu.utilization"))
      (is (str/includes? (get annotations "breach_details") "host.name=node-1"))
      (is (str/includes? (get annotations "breach_details") "95.50")))))

(deftest metrics-alert-target-narrows-summary-test
  (testing "alert_target restricts the summary to the targeted ref"
    (let [rule (assoc base-rule
                      :query_mode "metrics"
                      :alert_target "B")
          result {:data {:series [{:id "A" :name "http.requests"
                                   :labels {} :data [{:timestamp 1 :value 1000}]}
                                  {:id "B" :name "http.errors"
                                   :labels {} :data [{:timestamp 1 :value 50}]}]}}
          {:keys [annotations]} (single-alert (build-payload rule "firing" result))]
      (is (str/includes? (get annotations "summary") "1 series breached"))
      (is (str/includes? (get annotations "breach_details") "http.errors"))
      (is (not (str/includes? (get annotations "breach_details") "http.requests"))))))

(deftest metrics-firing-with-empty-data-degrades-gracefully-test
  (testing "metrics result with all-empty series produces no summary annotations"
    (let [rule (assoc base-rule :query_mode "metrics")
          result {:data {:series [{:id "A" :name "m" :labels {} :data []}]}}
          {:keys [annotations]} (single-alert (build-payload rule "firing" result))]
      (is (nil? (get annotations "summary")))
      (is (nil? (get annotations "breach_details")))
      (is (= "Errors exceed threshold" (get annotations "description"))
          "description survives even when result has no usable breach data"))))

;; ---------------------------------------------------------
;; Annotations — no_result + resolved + nil-result paths

(deftest no-result-firing-uses-fixed-summary-test
  (testing "no_result mode firing has a deterministic summary, not row-derived"
    (let [rule (assoc base-rule :alert_on "no_result")
          ;; Empty result is the firing condition for no_result mode
          result {:data {:rows [] :total_count 0}}
          {:keys [annotations]} (single-alert (build-payload rule "firing" result))]
      (is (str/includes? (get annotations "summary") "no results")
          "summary should explain the absence-detection trigger")
      (is (nil? (get annotations "breach_details"))
          "no rows means no per-row breakdown to render"))))

(deftest resolved-status-omits-breach-context-test
  (testing "resolved status sets endsAt and skips breach details"
    (let [{:keys [annotations endsAt]} (single-alert (build-payload base-rule "resolved" nil))]
      (is (= "Errors exceed threshold" (get annotations "description")))
      (is (nil? (get annotations "summary")))
      (is (nil? (get annotations "breach_details")))
      (is (not= "0001-01-01T00:00:00Z" endsAt)
          "resolved alerts should carry a real RFC3339 endsAt"))))

(deftest nil-result-on-firing-keeps-payload-valid-test
  (testing "evaluation error path: nil result still yields a deliverable payload"
    (let [{:keys [labels annotations startsAt fingerprint]}
          (single-alert (build-payload base-rule "firing" nil))]
      (is (= "High error rate" (get labels "alertname")))
      (is (some? startsAt))
      (is (some? fingerprint))
      (is (nil? (get annotations "summary"))
          "no result -> no summary, but description still rides along")
      (is (= "Errors exceed threshold" (get annotations "description"))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.alert-rule.notify-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
