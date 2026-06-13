;; ---------------------------------------------------------
;; o11ylite.alert-rule.transitions-test
;;
;; Table-driven tests over the state machine. Each case is
;; (mode stored-state present?) -> expected outcome. Every row of
;; `transitions` is covered. Transitions are immediate (no hysteresis).
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.transitions-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.alert-rule.transitions :as tr]))

(deftest mode-mapping-test
  (is (= :on-absence (tr/alert-on->mode "no_result")))
  (is (= :on-match (tr/alert-on->mode "result")))
  (testing "unknown/nil defaults to match"
    (is (= :on-match (tr/alert-on->mode nil)))
    (is (= :on-match (tr/alert-on->mode "bogus")))))

(deftest match-transitions-test
  (testing "match: group first present -> fire"
    (let [o (tr/step :on-match :none true)]
      (is (= :commit (:action o)))
      (is (= :firing (:next o)))
      (is (= :firing (:notify o)))))
  (testing "match: still present -> update value, no notify"
    (let [o (tr/step :on-match :firing true)]
      (is (= :commit (:action o)))
      (is (= :firing (:next o)))
      (is (nil? (:notify o)))
      (is (true? (:update-value? o)))))
  (testing "match: cleared -> resolve + delete + notify"
    (let [o (tr/step :on-match :firing false)]
      (is (= :commit (:action o)))
      (is (= :resolved (:next o)))
      (is (= :resolved (:notify o)))
      (is (true? (:delete? o)))))
  (testing "match: never present -> no-op"
    (is (nil? (tr/step :on-match :none false)))))

(deftest absence-transitions-test
  (testing "absence: group first present -> ok (tracked)"
    (let [o (tr/step :on-absence :none true)]
      (is (= :commit (:action o)))
      (is (= :ok (:next o)))
      (is (nil? (:notify o)))))
  (testing "absence: still present -> ok, no notify"
    (let [o (tr/step :on-absence :ok true)]
      (is (= :commit (:action o)))
      (is (= :ok (:next o)))
      (is (nil? (:notify o)))))
  (testing "absence: tracked group disappears -> fire"
    (let [o (tr/step :on-absence :ok false)]
      (is (= :commit (:action o)))
      (is (= :firing (:next o)))
      (is (= :firing (:notify o)))))
  (testing "absence: firing group reappears -> resolve to ok, no delete"
    (let [o (tr/step :on-absence :firing true)]
      (is (= :commit (:action o)))
      (is (= :ok (:next o)))
      (is (= :resolved (:notify o)))
      (is (false? (:delete? o)))))
  (testing "absence: empty from the first eval -> fire (no prior instance)"
    (let [o (tr/step :on-absence :none false)]
      (is (= :commit (:action o)))
      (is (= :firing (:next o)))
      (is (= :firing (:notify o))))
    (testing "firing group stays absent -> no-op (persists until reappearance/dismissal)"
      (is (nil? (tr/step :on-absence :firing false))))))

;; ---------------------------------------------------------
;; Exhaustive sweep: every (mode, stored, presence) key resolves to
;; either nil or a commit, never throws.

(deftest exhaustive-coverage-test
  (doseq [mode [:on-match :on-absence]
          stored [:none :ok :firing]
          present? [true false]]
    (testing (str mode " " stored " present?=" present?)
      (let [o (tr/step mode stored present?)]
        (is (or (nil? o) (= :commit (:action o)))
            "every key yields nil or an immediate commit")))))
