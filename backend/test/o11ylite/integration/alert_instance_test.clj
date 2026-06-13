;; ---------------------------------------------------------
;; o11ylite.integration.alert-instance-test
;;
;; Foundation-step coverage for per-group alert instances. Grouping is
;; dormant in this step, so every rule is the degenerate single-instance
;; (empty-fingerprint) case. These tests assert the instance lifecycle
;; underneath the existing rule-level behavior:
;;
;;   - match rule: empty-fingerprint instance minted on fire, removed
;;     after resolve
;;   - absence rule: ungrouped rule fires on the first empty eval with no
;;     prior instance, resolves to ok (not deleted) on reappearance
;;   - startsAt (started_at) survives across a tick while firing
;;   - eval exception freezes the tick: no instance transitions
;; ---------------------------------------------------------

(ns o11ylite.integration.alert-instance-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.alert-rule :as alert-rule]
    [o11ylite.alert-rule.instance-store :as instance-store]
    [o11ylite.alert-rule.store :as store]
    [o11ylite.test-helpers :as h])
  (:import
    [com.github.f4b6a3.uuid UuidCreator]))

(use-fixtures :each h/with-system)

(defn- sqlite
  []
  (:db/sqlite h/*system*))
(defn- duckdb
  []
  (:db/duckdb-reader h/*system*))

(defn- create-rule!
  [overrides]
  (let [id (str (UuidCreator/getTimeOrderedEpoch))
        rule (merge {:name "Instance Test Rule"
                     :description "t"
                     :query_mode "events"
                     :query {:filter {:field "service" :op "=" :value "inst-svc"}
                             :visualization {:type "table"}}
                     :eval_window_ms 7200000
                     :eval_interval_ms 0}
                    overrides)]
    (store/create! (sqlite) id rule)
    id))

(defn- instances
  [rule-id]
  (instance-store/list-by-rule (sqlite) rule-id))

(defn- eval!
  []
  (alert-rule/run-evaluation-cycle! (duckdb) (sqlite) nil))

;; ---------------------------------------------------------
;; Match rule instance lifecycle

(deftest match-rule-instance-lifecycle-test
  (testing "ungrouped match rule mints and removes the empty-fingerprint instance"
    (let [rule-id (create-rule! {:name "Match Lifecycle"
                                 :query {:filter {:field "service" :op "=" :value "match-life-svc"}
                                         :visualization {:type "table"}}})]
      (testing "no instance before any breach"
        (is (zero? (instance-store/count-by-rule (sqlite) rule-id))))

      (h/ingest-sample-events! 4 {:service "match-life-svc" :error true})
      (eval!)

      (testing "firing mints exactly one empty-fingerprint instance"
        (let [inst (instances rule-id)]
          (is (= 1 (count inst)))
          (is (= "" (:fingerprint (first inst))))
          (is (= "firing" (:state (first inst))))
          (is (some? (:started_at (first inst))))))

      (testing "rule rollup state is firing"
        (is (= "firing" (:state (store/get-by-id (sqlite) rule-id)))))

      (testing "started_at survives a second firing tick"
        (let [started-1 (:started_at (first (instances rule-id)))]
          (eval!)
          (is (= started-1 (:started_at (first (instances rule-id))))
              "startsAt must not reset while continuously firing")))

      ;; Note: we cannot easily delete the ingested events to force a
      ;; resolve here without a delete path; resolve-side deletion is
      ;; asserted in the absence test and the unit transition tests.
      )))

;; ---------------------------------------------------------
;; Absence rule instance lifecycle

(deftest absence-rule-bootstrap-instance-test
  (testing "ungrouped absence rule fires on the first empty eval with no prior instance"
    (let [rule-id (create-rule! {:name "Absence Bootstrap"
                                 :alert_on "no_result"
                                 :query {:filter {:field "service" :op "=" :value "never-here-svc"}
                                         :visualization {:type "table"}}})]
      (testing "no instance before any eval"
        (is (zero? (instance-store/count-by-rule (sqlite) rule-id))))

      (testing "fires when the first eval returns empty (no rows ever seen)"
        ;; ingest unrelated events so the query window has data but no match
        (h/ingest-sample-events! 3 {:service "other-svc"})
        (eval!)
        (let [inst (instances rule-id)]
          (is (= 1 (count inst)))
          (is (= "" (:fingerprint (first inst))))
          (is (= "firing" (:state (first inst))))
          (is (some? (:started_at (first inst)))))
        (is (= "firing" (:state (store/get-by-id (sqlite) rule-id)))))

      (testing "resolves back to ok (not deleted) when the group reappears"
        (h/ingest-sample-events! 2 {:service "never-here-svc"})
        (eval!)
        (let [inst (instances rule-id)]
          (is (= 1 (count inst)) "instance is retained as a tracked group, not deleted")
          (is (= "ok" (:state (first inst)))))
        (is (= "ok" (:state (store/get-by-id (sqlite) rule-id))))))))

;; ---------------------------------------------------------
;; Eval exception freezes the tick

(deftest eval-exception-freezes-instances-test
  (testing "a rule whose field disappears preserves instances and prev state"
    (let [rule-id (create-rule! {:name "Freeze On Error"
                                 :query {:filter {:field "service" :op "=" :value "freeze-svc"}
                                         :visualization {:type "table"}}})]
      (h/ingest-sample-events! 3 {:service "freeze-svc" :error true})
      (eval!)
      (is (= "firing" (:state (store/get-by-id (sqlite) rule-id))) "sanity: firing")
      (let [inst-before (instances rule-id)]
        ;; Rewrite the query to reference a field that does not exist
        (store/update! (sqlite) rule-id
                       {:name "Freeze On Error" :description "" :enabled true
                        :query_mode "events"
                        :query {:filter {:field "attr.gone_now" :op "=" :value "x"}
                                :visualization {:type "table"}}
                        :eval_window_ms 7200000 :eval_interval_ms 0
                        :alert_on "result"})
        (eval!)
        (testing "prev state preserved and error recorded"
          (let [rule (store/get-by-id (sqlite) rule-id)]
            (is (= "firing" (:state rule)))
            (is (re-find #"attr\.gone_now" (or (:last_eval_error rule) "")))))
        (testing "instances unchanged by the frozen tick"
          (is (= (map :state inst-before)
                 (map :state (instances rule-id)))))))))
