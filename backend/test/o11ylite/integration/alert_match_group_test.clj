;; ---------------------------------------------------------
;; o11ylite.integration.alert-match-group-test
;;
;; Step 2: per-group match semantics. A grouped match rule must track
;; independent fire/resolve state per group, surface group labels and
;; breaching values, and batch all of a tick's transitions. We assert on
;; the notification batch returned by evaluate-rule! (the exact payload
;; the webhook sends) plus the persisted instances.
;;
;; Each test gets a fresh system, so the only events in the store are the
;; ones the test ingests. The rule groups by service with no filter; one
;; instance is therefore minted per ingested service.
;; ---------------------------------------------------------

(ns o11ylite.integration.alert-match-group-test
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
        rule (merge {:name "Grouped Match"
                     :description "t"
                     :query_mode "events"
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

(defn- wait-for-events!
  "Wait until `n` groups are visible to the eval loop, using a persisted
   probe rule so instance upserts satisfy the alert_rules FK."
  [n]
  (store/create! (sqlite) "probe"
                 {:name "probe" :description "t"
                  :query_mode "events" :alert_on "result"
                  :eval_window_ms 7200000 :eval_interval_ms 0
                  :query {:group_by ["service"] :visualization {:type "table"}}})
  (try
    (h/wait-until
      #(let [r (eval-rule "probe")]
         (when (>= (count (:notifications r)) n) r))
      {:label "events visible"})
    (finally
      (store/delete! (sqlite) "probe"))))

(deftest grouped-match-fires-per-group-test
  (testing "a grouped match rule mints one firing instance per present group"
    (h/ingest-sample-events! 3 {:service "svc-a"})
    (h/ingest-sample-events! 2 {:service "svc-b"})
    (wait-for-events! 2)
    (let [rule-id (create-rule! {})
          result (eval-rule rule-id)
          inst (instances rule-id)
          firing-services (set (map #(get-in % [:labels :service]) inst))]
      (testing "one instance per present group"
        (is (= 2 (count inst)))
        (is (= #{"svc-a" "svc-b"} firing-services))
        (is (every? #(= "firing" (:state %)) inst)))

      (testing "every instance has a distinct fingerprint"
        (is (= 2 (count (set (map :fingerprint inst))))))

      (testing "the batch has one firing entry per group with service label"
        (let [notifs (:notifications result)]
          (is (= 2 (count notifs)))
          (is (every? #(= "firing" (:status %)) notifs))
          (is (= #{"svc-a" "svc-b"}
                 (set (map #(get-in % [:labels :service]) notifs))))))

      (testing "rule rollup is firing"
        (is (= :firing (:state result)))))))

(deftest grouped-match-no-renotify-while-firing-test
  (testing "a continuously-firing group does not re-notify each tick"
    (h/ingest-sample-events! 2 {:service "svc-x"})
    (wait-for-events! 1)
    (let [rule-id (create-rule! {})]
      (let [r1 (eval-rule rule-id)]
        (is (= 1 (count (instances rule-id))))
        (is (= 1 (count (:notifications r1)))))
      (let [r2 (eval-rule rule-id)]
        (is (empty? (:notifications r2))
            "still-firing must be silent")
        (is (= 1 (count (instances rule-id))))))))
