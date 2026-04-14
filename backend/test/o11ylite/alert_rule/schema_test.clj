;; ---------------------------------------------------------
;; o11ylite.alert-rule.schema-test
;;
;; Unit tests for alert rule schema validation.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.alert-rule.schema :as schema]))

;; ---------------------------------------------------------
;; Helpers

(defn valid?
  [data]
  (nil? (schema/validate data)))

(defn invalid?
  [data]
  (some? (schema/validate data)))

;; ---------------------------------------------------------
;; Valid Rule Structure

(deftest valid-rule-structure-test
  (testing "minimal valid events rule"
    (is (valid? {:name "High error rate"
                 :enabled true
                 :query_mode "events"
                 :query {:visualization {:type "table"}}
                 :eval_window_ms 300000
                 :eval_interval_ms 60000
                 :alert_on "result"})))

  (testing "valid events rule with all fields"
    (is (valid? {:name "High error rate"
                 :description "Alert when errors spike"
                 :enabled true
                 :query_mode "events"
                 :query {:filter {:field "service" :op "=" :value "api"}
                         :aggregations [{:id "A" :field "*" :function "count"}]
                         :group_by ["status"]
                         :having {:ref "A" :op ">" :value 100}
                         :visualization {:type "time_series"}}
                 :eval_window_ms 300000
                 :eval_interval_ms 60000
                 :alert_on "result"})))

  (testing "valid metrics rule with metrics array and having"
    (is (valid? {:name "CPU alert"
                 :enabled true
                 :query_mode "metrics"
                 :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                         :having {:ref "A" :op ">" :value 80}}
                 :eval_window_ms 300000
                 :eval_interval_ms 60000
                 :alert_on "result"})))

  (testing "valid rule with no_result alert_on"
    (is (valid? {:name "Silence detection"
                 :enabled true
                 :query_mode "events"
                 :query {:visualization {:type "table"}}
                 :eval_window_ms 300000
                 :eval_interval_ms 60000
                 :alert_on "no_result"}))))

;; ---------------------------------------------------------
;; Rule-Level Validation

(deftest rule-level-validation-test
  (testing "missing name rejected"
    (is (invalid? {:enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "invalid eval_window_ms rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 999
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "invalid eval_interval_ms rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 999
                   :alert_on "result"})))

  (testing "unknown query_mode rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "unknown"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "invalid alert_on value rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "invalid"})))

  (testing "missing alert_on rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000}))))

;; ---------------------------------------------------------
;; Events Query Validation

(deftest events-query-validation-test
  (testing "unknown keys in events query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:filters [{:field "service" :op "=" :value "api"}]
                           :visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing ":time_range in events query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:time_range {:start 1702000000000 :end 1702003600000}
                           :visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing ":cursor in events query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:cursor "some-cursor"
                           :visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing ":limit in events query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:limit 100
                           :visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "valid having requires aggregations"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:having {:ref "A" :op ">" :value 100}
                           :visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "duplicate aggregation IDs rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:aggregations [{:id "A" :field "*" :function "count"}
                                          {:id "A" :field "duration_ms" :function "avg"}]
                           :visualization {:type "time_series"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "having ref must reference existing aggregation"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:aggregations [{:id "A" :field "*" :function "count"}]
                           :having {:ref "B" :op ">" :value 100}
                           :visualization {:type "time_series"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"}))))

;; ---------------------------------------------------------
;; Metrics Query Validation

(deftest metrics-query-validation-test
  (testing ":time_range in metrics query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "metrics"
                   :query {:time_range {:start 1702000000000 :end 1702003600000}
                           :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "valid metrics query with metrics array"
    (is (valid? {:name "Test"
                 :enabled true
                 :query_mode "metrics"
                 :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}
                 :eval_window_ms 300000
                 :eval_interval_ms 60000
                 :alert_on "result"})))

  (testing "duplicate metric IDs rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "metrics"
                   :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}
                                     {:id "A" :name "memory.usage" :agg "avg"}]}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "having ref must reference existing metric"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "metrics"
                   :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                           :having {:ref "B" :op ">" :value 80}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "metrics query with visualization rejected"
    (is (invalid? {:name "CPU alert"
                   :enabled true
                   :query_mode "metrics"
                   :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                           :having {:ref "A" :op ">" :value 80}
                           :visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "metrics query without visualization valid"
    (is (valid? {:name "CPU alert"
                 :enabled true
                 :query_mode "metrics"
                 :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}
                 :eval_window_ms 300000
                 :eval_interval_ms 60000
                 :alert_on "result"}))))

;; ---------------------------------------------------------
;; Closed Schema Behavior

(deftest closed-schema-behavior-test
  (testing "extra keys at alert-rule level rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"
                   :extra_key "should not be here"})))

  (testing "extra keys in events query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "events"
                   :query {:visualization {:type "table"}
                           :unknown_key "not allowed"}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"})))

  (testing "extra keys in metrics query rejected"
    (is (invalid? {:name "Test"
                   :enabled true
                   :query_mode "metrics"
                   :query {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                           :unknown_key "not allowed"}
                   :eval_window_ms 300000
                   :eval_interval_ms 60000
                   :alert_on "result"}))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.alert-rule.schema-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
