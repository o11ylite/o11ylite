;; ---------------------------------------------------------
;; o11ylite.integration.alert-rule-eval-test
;;
;; Integration test for alert rule evaluation happy path.
;; Tests: create rule -> ingest matching events -> evaluate -> rule fires
;; ---------------------------------------------------------

(ns o11ylite.integration.alert-rule-eval-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.alert-rule :as alert-rule]
    [o11ylite.alert-rule.store :as store]
    [o11ylite.test-helpers :as h])
  (:import
    [com.github.f4b6a3.uuid UuidCreator]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))

(defn- duckdb
  []
  (:db/duckdb h/*system*))

(defn- events-schema
  []
  (:cache/events-schema h/*system*))

(defn- create-alert-rule!
  "Create an alert rule directly via the store. Returns the rule ID."
  [overrides]
  (let [id (str (UuidCreator/getTimeOrderedEpoch))
        ;; eval_interval_ms defaults to 0 so the rule is always considered due,
        ;; even if the background scheduler already evaluated it moments ago.
        rule-data (merge {:name "Test Alert Rule"
                          :description "Test rule for evaluation"
                          :query_mode "events"
                          :query {:filter {:field "service" :op "=" :value "test-service"}
                                  :visualization {:type "table"}}
                          :eval_window_ms 300000
                          :eval_interval_ms 0}
                         overrides)]
    (store/create! (sqlite) id rule-data)
    id))

(defn- get-rule-state
  "Get the current state of an alert rule."
  [rule-id]
  (:state (store/get-by-id (sqlite) rule-id)))

;; ---------------------------------------------------------
;; Tests

(deftest alert-rule-fires-on-matching-events-test
  (testing "Happy path: alert rule evaluates to firing when matching events exist"
    ;; 1. Create an alert rule that matches events with service='error-service'
    (let [rule-id (create-alert-rule! {:name "Error Rate Alert"
                                       :query {:filter {:field "service" :op "=" :value "error-service"}
                                               :visualization {:type "table"}}
                                       ;; Use 2-hour window since test helpers generate events within last hour
                                       :eval_window_ms 7200000})]

      ;; Verify initial state is "ok"
      (is (= "ok" (get-rule-state rule-id))
          "Rule should start in 'ok' state")

      ;; 2. Ingest events that match the rule's query
      ;; Events are generated with timestamps within last hour, so they'll be caught
      (h/ingest-sample-events! 5 {:service "error-service"
                                  :error true})

      ;; 3. Trigger evaluation cycle manually
      (alert-rule/run-evaluation-cycle!
        (duckdb)
        (sqlite)
        (events-schema)
        nil)

      ;; 4. Verify rule state changed to "firing"
      (is (= "firing" (get-rule-state rule-id))
          "Rule should transition to 'firing' state after evaluation"))))

(deftest alert-rule-remains-ok-without-matching-events-test
  (testing "Alert rule stays 'ok' when no matching events exist"
    ;; 1. Create an alert rule that matches a specific service
    (let [rule-id (create-alert-rule! {:name "Specific Service Alert"
                                       :query {:filter {:field "service" :op "=" :value "nonexistent-service"}
                                               :visualization {:type "table"}}
                                       ;; Use 2-hour window since test helpers generate events within last hour
                                       :eval_window_ms 7200000})]

      ;; 2. Ingest events that DON'T match the rule's query
      (h/ingest-sample-events! 3 {:service "other-service"})

      ;; 3. Trigger evaluation
      (alert-rule/run-evaluation-cycle!
        (duckdb)
        (sqlite)
        (events-schema)
        nil)

      ;; 4. Verify rule stays in "ok" state
      (is (= "ok" (get-rule-state rule-id))
          "Rule should remain in 'ok' state when no matching events"))))

(deftest alert-rule-eval-records-timestamp-test
  (testing "Alert rule evaluation records last_eval_at timestamp"
    ;; Create and evaluate a rule
    (let [rule-id (create-alert-rule! {:name "Timestamp Test Alert"
                                       :query {:filter {:field "service" :op "=" :value "ts-test-service"}
                                               :visualization {:type "table"}}
                                       ;; Use 2-hour window since test helpers generate events within last hour
                                       :eval_window_ms 7200000})]

      ;; Ingest matching event
      (h/ingest-sample-events! 1 {:service "ts-test-service"})

      ;; Evaluate
      (alert-rule/run-evaluation-cycle!
        (duckdb)
        (sqlite)
        (events-schema)
        nil)

      ;; Verify timestamp was recorded
      (let [rule (store/get-by-id (sqlite) rule-id)]
        (is (some? (:last_eval_at rule))
            "Rule should have last_eval_at timestamp after evaluation")
        (is (number? (:last_eval_at rule))
            "last_eval_at should be a numeric timestamp")))))

;; ---------------------------------------------------------
;; Absence Detection (no_result mode)

(deftest no-result-mode-fires-on-empty-events-test
  (testing "no_result mode: alert fires when no matching events exist"
    (let [rule-id (create-alert-rule! {:name "Silence Detection"
                                       :query {:filter {:field "service" :op "=" :value "silent-service"}
                                               :visualization {:type "table"}}
                                       :eval_window_ms 7200000
                                       :alert_on "no_result"})]
      ;; Ingest events for a different service (no match)
      (h/ingest-sample-events! 3 {:service "other-service"})

      ;; Evaluate
      (alert-rule/run-evaluation-cycle!
        (duckdb)
        (sqlite)
        (events-schema)
        nil)

      ;; no_result + empty results = firing
      (is (= "firing" (get-rule-state rule-id))
          "Rule with alert_on=no_result should fire when query returns empty"))))

(deftest no-result-mode-ok-on-non-empty-events-test
  (testing "no_result mode: alert stays ok when matching events exist"
    (let [rule-id (create-alert-rule! {:name "Silence Detection (OK)"
                                       :query {:filter {:field "service" :op "=" :value "alive-service"}
                                               :visualization {:type "table"}}
                                       :eval_window_ms 7200000
                                       :alert_on "no_result"})]
      ;; Ingest matching events
      (h/ingest-sample-events! 5 {:service "alive-service"})

      ;; Evaluate
      (alert-rule/run-evaluation-cycle!
        (duckdb)
        (sqlite)
        (events-schema)
        nil)

      ;; no_result + non-empty results = ok
      (is (= "ok" (get-rule-state rule-id))
          "Rule with alert_on=no_result should stay ok when query returns results"))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.alert-rule-eval-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
