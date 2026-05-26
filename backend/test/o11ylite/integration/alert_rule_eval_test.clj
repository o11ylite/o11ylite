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
  (:db/duckdb-reader h/*system*))

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
        nil)

      ;; no_result + non-empty results = ok
      (is (= "ok" (get-rule-state rule-id))
          "Rule with alert_on=no_result should stay ok when query returns results"))))

;; ---------------------------------------------------------
;; Metrics mode + alert_target

(deftest metrics-alert-target-filters-series-test
  (testing "alert_target restricts which series the evaluator considers"
    ;; Ingest two metrics so the query returns two series. metric A always
    ;; has data; metric B never has data. With alert_target=B, the rule
    ;; should NOT fire (B's series is empty), proving alert_target
    ;; correctly narrows evaluation.
    (let [now-ns (* (System/currentTimeMillis) 1000000)
          ;; Use distinct names so other tests don't pollute
          metric-a "alert.target.test.a"
          metric-b "alert.target.test.b"]

      (h/export-metrics!
        {:service-name "alert-target-svc"
         :meter-name "test-meter"
         :metrics [(h/build-gauge-metric
                     {:name metric-a
                      :unit "1"
                      :data-points [{:value 42.0 :time-ns now-ns}]})]})
      ;; metric-b is declared but never ingested (no series produced)

      (testing "without alert_target, A's data alone fires the rule"
        (let [rule-id (create-alert-rule!
                        {:name "No alert_target"
                         :query_mode "metrics"
                         :query {:metrics [{:id "A" :name metric-a :agg "last"}]}
                         :eval_window_ms 7200000})]
          (alert-rule/run-evaluation-cycle!
            (duckdb) (sqlite) nil)
          (is (= "firing" (get-rule-state rule-id)))))

      (testing "alert_target=A fires when A has data"
        (let [rule-id (create-alert-rule!
                        {:name "alert_target A"
                         :query_mode "metrics"
                         :query {:metrics [{:id "A" :name metric-a :agg "last"}
                                           {:id "B" :name metric-b :agg "last"}]}
                         :eval_window_ms 7200000
                         :alert_target "A"})]
          (alert-rule/run-evaluation-cycle!
            (duckdb) (sqlite) nil)
          (is (= "firing" (get-rule-state rule-id)))))

      (testing "alert_target=B stays ok when B has no data (even though A has data)"
        (let [rule-id (create-alert-rule!
                        {:name "alert_target B"
                         :query_mode "metrics"
                         :query {:metrics [{:id "A" :name metric-a :agg "last"}
                                           {:id "B" :name metric-b :agg "last"}]}
                         :eval_window_ms 7200000
                         :alert_target "B"})]
          (alert-rule/run-evaluation-cycle!
            (duckdb) (sqlite) nil)
          (is (= "ok" (get-rule-state rule-id))))))))

;; ---------------------------------------------------------
;; Metrics eval_window_ms is used as the single time bucket

(deftest metrics-eval-aggregates-entire-window-test
  ;; Both tests below would FAIL if alert eval used per-sub-bucket aggregation
  ;; instead of a single bucket spanning the entire eval window. They construct
  ;; data where the full-window aggregate disagrees with per-sub-bucket
  ;; aggregates, so removing single-bucket mode flips the outcome.
  (let [now-ms (System/currentTimeMillis)
        now-ns (* now-ms 1000000)
        eval-window-ms 300000
        ms->ns (fn [ms] (* ms 1000000))]

    (testing "avg: full-window avg below threshold stays ok despite outlier sub-bucket"
      ;; Many low values (10) + one outlier (200) in its own sub-bucket.
      ;; Full-window avg = (10*5 + 200)/6 ≈ 41.7 → ok.
      ;; Per-sub-bucket: outlier bucket avg = 200 → would fire without single-bucket mode.
      (let [metric-name "alert.avg.outlier.test"
            data-points [{:value 10.0 :time-ns (- now-ns (ms->ns 250000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns 200000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns 150000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns 100000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns  50000))}
                         {:value 200.0 :time-ns (- now-ns (ms->ns 30000))}]]
        (h/export-metrics!
          {:service-name "alert-avg-outlier-svc"
           :meter-name "test-meter"
           :metrics [(h/build-gauge-metric
                       {:name metric-name
                        :unit "1"
                        :data-points data-points})]})
        (let [rule-id (create-alert-rule!
                        {:name "Avg Outlier Test"
                         :query_mode "metrics"
                         :query {:metrics [{:id "A" :name metric-name :agg "avg"}]
                                 :having {:ref "A" :op ">" :value 50}}
                         :eval_window_ms eval-window-ms})]
          (alert-rule/run-evaluation-cycle!
            (duckdb) (sqlite) nil)
          (is (= "ok" (get-rule-state rule-id))
              "Full-window avg ~41.7 must not fire; only single-bucket mode prevents the outlier sub-bucket from triggering."))))

    (testing "sum: full-window sum above threshold fires despite small per-sub-bucket sums"
      ;; Five values of 10 each in different sub-buckets.
      ;; Full-window sum = 50 → > 49 → fires.
      ;; Per-sub-bucket sum = 10 each → would stay ok without single-bucket mode.
      (let [metric-name "alert.sum.spread.test"
            data-points [{:value 10.0 :time-ns (- now-ns (ms->ns 240000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns 180000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns 120000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns  60000))}
                         {:value 10.0 :time-ns (- now-ns (ms->ns  20000))}]]
        (h/export-metrics!
          {:service-name "alert-sum-spread-svc"
           :meter-name "test-meter"
           :metrics [(h/build-gauge-metric
                       {:name metric-name
                        :unit "1"
                        :data-points data-points})]})
        (let [rule-id (create-alert-rule!
                        {:name "Sum Spread Test"
                         :query_mode "metrics"
                         :query {:metrics [{:id "A" :name metric-name :agg "sum"}]
                                 :having {:ref "A" :op ">" :value 49}}
                         :eval_window_ms eval-window-ms})]
          (alert-rule/run-evaluation-cycle!
            (duckdb) (sqlite) nil)
          (is (= "firing" (get-rule-state rule-id))
              "Full-window sum 50 must fire; without single-bucket mode each sub-bucket sums to 10 and would not exceed threshold."))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.alert-rule-eval-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
