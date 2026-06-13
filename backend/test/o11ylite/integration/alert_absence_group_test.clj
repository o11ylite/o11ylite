;; ---------------------------------------------------------
;; o11ylite.integration.alert-absence-group-test
;;
;; Step 3: per-group absence semantics. A grouped absence rule tracks
;; each group it has seen present (as :ok); when a tracked group later
;; disappears from results, that group fires independently. Unlike match
;; rules, a resolved absence group is retained (not deleted) — it stays a
;; tracked group so it can fire again. This rides on the same generic
;; eval loop as match: the only difference is the transition table.
;;
;; Each test gets a fresh system, so the only events in the store are the
;; ones the test ingests.
;; ---------------------------------------------------------

(ns o11ylite.integration.alert-absence-group-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.alert-rule.eval :as alert-eval]
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
        rule (merge {:name "Grouped Absence"
                     :description "t"
                     :query_mode "events"
                     :alert_on "no_result"
                     :query {:group_by ["service"]
                             :visualization {:type "table"}}
                     :eval_window_ms 7200000
                     :eval_interval_ms 0}
                    overrides)]
    (store/create! (sqlite) id rule)
    id))

(defn- eval-rule
  [rule-id]
  (alert-eval/evaluate-rule! (duckdb) (sqlite) (store/get-by-id (sqlite) rule-id)))

(defn- instances
  [rule-id]
  (instance-store/list-by-rule (sqlite) rule-id))

(defn- instance-by-service
  [rule-id svc]
  (->> (instances rule-id)
       (filter #(= svc (get-in % [:labels :service])))
       first))

(defn- wait-for-events!
  [n]
  (h/wait-until
    #(let [r (alert-eval/evaluate-rule! (duckdb) (sqlite)
                                        {:query_mode "events" :alert_on "result"
                                         :eval_window_ms 7200000
                                         :query {:group_by ["service"] :visualization {:type "table"}}
                                         :id "probe"})]
       (when (>= (count (:notifications r)) n) r))
    {:label "events visible"}))

(deftest grouped-absence-tracks-then-fires-per-group-test
  (testing "a grouped absence rule tracks present groups, then fires per group on disappearance"
    (h/ingest-sample-events! 2 {:service "svc-a"})
    (h/ingest-sample-events! 2 {:service "svc-b"})
    (wait-for-events! 2)
    ;; Window the rule to the two seeded services so both are present on
    ;; the first eval and become tracked :ok groups.
    (let [rule-id (create-rule! {:query {:group_by ["service"]
                                         :filter {:or [{:field "service" :op "=" :value "svc-a"}
                                                       {:field "service" :op "=" :value "svc-b"}]}
                                         :visualization {:type "table"}}})]
      (testing "first eval: both present groups tracked as ok, no notifications"
        (let [result (eval-rule rule-id)
              inst (instances rule-id)]
          (is (= 2 (count inst)))
          (is (= #{"svc-a" "svc-b"} (set (map #(get-in % [:labels :service]) inst))))
          (is (every? #(= "ok" (:state %)) inst))
          (is (empty? (:notifications result)))
          (is (= :ok (:state result)))))

      ;; Narrow the rule so svc-b is no longer in results, svc-a still is.
      ;; alert_on unchanged, so tracked instances are retained.
      (store/update! (sqlite) rule-id
                     {:name "Grouped Absence" :description "t" :enabled true
                      :query_mode "events" :alert_on "no_result"
                      :query {:group_by ["service"]
                              :filter {:field "service" :op "=" :value "svc-a"}
                              :visualization {:type "table"}}
                      :eval_window_ms 7200000 :eval_interval_ms 0})

      (testing "svc-b disappears -> only svc-b fires, svc-a stays ok"
        (let [result (eval-rule rule-id)]
          (is (= "firing" (:state (instance-by-service rule-id "svc-b"))))
          (is (= "ok" (:state (instance-by-service rule-id "svc-a"))))
          (testing "exactly one firing notification, for svc-b"
            (let [notifs (:notifications result)]
              (is (= 1 (count notifs)))
              (is (= "firing" (:status (first notifs))))
              (is (= "svc-b" (get-in (first notifs) [:labels :service])))))
          (is (= :firing (:state result)) "rollup is firing while any group fires")))

      (testing "resolved/absence groups are retained, not deleted"
        (is (= 2 (count (instances rule-id)))
            "both groups still tracked after one fires")))))

(deftest grouped-absence-resolves-without-delete-test
  (testing "a firing absence group resolves back to ok (retained) when it reappears"
    (h/ingest-sample-events! 2 {:service "svc-x"})
    (wait-for-events! 1)
    (let [rule-id (create-rule! {:query {:group_by ["service"]
                                         :filter {:field "service" :op "=" :value "svc-x"}
                                         :visualization {:type "table"}}})]
      ;; Track svc-x as ok.
      (eval-rule rule-id)
      (is (= "ok" (:state (instance-by-service rule-id "svc-x"))))
      ;; Make it disappear -> fire.
      (store/update! (sqlite) rule-id
                     {:name "Grouped Absence" :description "t" :enabled true
                      :query_mode "events" :alert_on "no_result"
                      :query {:group_by ["service"]
                              :filter {:field "service" :op "=" :value "NONE"}
                              :visualization {:type "table"}}
                      :eval_window_ms 7200000 :eval_interval_ms 0})
      (let [r (eval-rule rule-id)]
        (is (= "firing" (:state (instance-by-service rule-id "svc-x"))))
        (is (= 1 (count (:notifications r)))))
      ;; Bring it back -> resolve to ok, retained, resolve notification.
      (store/update! (sqlite) rule-id
                     {:name "Grouped Absence" :description "t" :enabled true
                      :query_mode "events" :alert_on "no_result"
                      :query {:group_by ["service"]
                              :filter {:field "service" :op "=" :value "svc-x"}
                              :visualization {:type "table"}}
                      :eval_window_ms 7200000 :eval_interval_ms 0})
      (let [r (eval-rule rule-id)]
        (is (= "ok" (:state (instance-by-service rule-id "svc-x"))))
        (is (= 1 (count (instances rule-id))) "retained, not deleted")
        (is (= 1 (count (:notifications r))))
        (is (= "resolved" (:status (first (:notifications r)))))))))
