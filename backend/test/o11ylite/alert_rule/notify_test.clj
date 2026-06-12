;; ---------------------------------------------------------
;; o11ylite.alert-rule.notify-test
;;
;; Pure tests for the Alertmanager payload builder. The builder is a
;; data -> data transform, so no system fixture is needed.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.notify-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.alert-rule.notify :as notify]))

(def ^:private rule-ctx
  {:id "rule-1"
   :name "High error rate"
   :description "errors spiked"
   :rule-labels {}
   :generator-url "/alert-rules/rule-1/edit"})

(deftest array-shape-test
  (testing "payload is an Alertmanager array, one entry per notification"
    (let [payload (notify/build-payload
                    rule-ctx
                    [{:status "firing" :labels {:service "api"} :fingerprint "a" :started_at 1000}
                     {:status "firing" :labels {:service "web"} :fingerprint "b" :started_at 1000}])]
      (is (vector? payload))
      (is (= 2 (count payload)))
      (is (= #{"api" "web"} (set (map #(get-in % [:labels "service"]) payload)))))))

(deftest ungrouped-degenerates-to-array-of-one-test
  (testing "an ungrouped rule produces a single entry with empty fingerprint"
    (let [payload (notify/build-payload
                    rule-ctx
                    [{:status "firing" :labels {} :fingerprint "" :started_at 1000}])]
      (is (= 1 (count payload)))
      (is (= "" (:fingerprint (first payload)))))))

(deftest label-merge-test
  (testing "group-by labels merge over the rule's base labels"
    (let [alert (first (notify/build-payload
                         rule-ctx
                         [{:status "firing" :labels {:service "api" :region "us"}
                           :fingerprint "a" :started_at 1000}]))]
      (is (= "High error rate" (get-in alert [:labels "alertname"])))
      (is (= "o11ylite" (get-in alert [:labels "source"])))
      (is (= "api" (get-in alert [:labels "service"])))
      (is (= "us" (get-in alert [:labels "region"]))))))

(deftest value-annotation-test
  (testing "scalar last_value renders bare in the value annotation"
    (let [alert (first (notify/build-payload
                         rule-ctx
                         [{:status "firing" :labels {} :value "2/5 ready"
                           :fingerprint "" :started_at 1000}]))]
      (is (= "2/5 ready" (get-in alert [:annotations "value"])))))
  (testing "map last_value renders as sorted k=v pairs"
    (let [alert (first (notify/build-payload
                         rule-ctx
                         [{:status "firing" :labels {} :value {:error_rate 0.12 :count 5}
                           :fingerprint "" :started_at 1000}]))]
      (is (= "count=5, error_rate=0.12" (get-in alert [:annotations "value"])))))
  (testing "absent value omits the annotation"
    (let [alert (first (notify/build-payload
                         rule-ctx
                         [{:status "firing" :labels {} :fingerprint "" :started_at 1000}]))]
      (is (not (contains? (:annotations alert) "value"))))))

(deftest timestamps-test
  (testing "firing entry has zero endsAt; resolved entry has a real endsAt"
    (let [[firing resolved]
          (notify/build-payload
            rule-ctx
            [{:status "firing" :labels {} :fingerprint "" :started_at 1700000000000}
             {:status "resolved" :labels {} :fingerprint ""
              :started_at 1700000000000 :resolved_at 1700000600000}])]
      (is (= "0001-01-01T00:00:00Z" (:endsAt firing)))
      (is (re-matches #"20\d{2}-.*Z" (:startsAt firing)))
      (is (re-matches #"20\d{2}-.*Z" (:endsAt resolved))))))

(deftest dismissal-reason-test
  (testing "reason annotation rides along on a resolved entry"
    (let [alert (first (notify/build-payload
                         rule-ctx
                         [{:status "resolved" :labels {:service "api"} :fingerprint "a"
                           :started_at 1000 :resolved_at 2000 :reason "dismissed"}]))]
      (is (= "dismissed" (get-in alert [:annotations "reason"]))))))

(deftest generator-url-test
  (testing "generatorURL is carried from the rule context"
    (let [alert (first (notify/build-payload
                         rule-ctx
                         [{:status "firing" :labels {} :fingerprint "" :started_at 1000}]))]
      (is (= "/alert-rules/rule-1/edit" (:generatorURL alert))))))
