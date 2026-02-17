;; ---------------------------------------------------------
;; o11ylite.integration.routes.alert-rules-test
;;
;; Integration tests for alert rules Inertia page routes.
;; Tests the happy-path CRUD flow: list, create, edit, update, delete.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.alert-rules-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [jsonista.core :as json]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(def ^:private sample-rule-body
  {:name "Test Alert"
   :description "A test alert rule"
   :enabled true
   :query_mode "events"
   :query (json/write-value-as-string
            {:visualization {:type "table"}})
   :eval_window_ms 300000
   :eval_interval_ms 60000})

(def ^:private rich-query
  "A query with filters, aggregations, group_by, having, and visualization."
  {:filter {:field "service" :op "=" :value "frontend"}
   :aggregations [{:id "A" :field "*" :function "count"}]
   :group_by ["service"]
   :having {:ref "A" :op ">" :value 100}
   :visualization {:type "time_series"}})

(defn- -create-rule!
  "Create an alert rule via POST. Returns the response (303 redirect)."
  ([session] (-create-rule! session {}))
  ([session overrides]
   (h/post-mutation "/alert-rules" session (merge sample-rule-body overrides))))

(defn- -list-rules
  "Fetch alert rules via Inertia JSON request."
  []
  (get-in (h/body (h/inertia-json-request "/alert-rules"))
          [:props :alert_rules]))

;; ---------------------------------------------------------
;; List Page

(deftest list-renders-html-test
  (testing "Alert rules list page renders HTML"
    (let [response (h/get-request "/alert-rules")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      (is (str/includes? (h/body response) "AlertRules")))))

(deftest list-returns-inertia-props-test
  (testing "Alert rules Inertia response includes alert_rules"
    (let [response (h/inertia-json-request "/alert-rules")
          body (h/body response)
          props (:props body)]
      (is (= 200 (h/status response)))
      (is (= "AlertRules" (:component body)))
      (is (vector? (:alert_rules props))))))

;; ---------------------------------------------------------
;; New Page

(deftest new-renders-inertia-page-test
  (testing "New alert rule page renders AlertRuleEdit with nil alert_rule"
    (let [response (h/inertia-json-request "/alert-rules/new")
          body (h/body response)
          props (:props body)]
      (is (= 200 (h/status response)))
      (is (= "AlertRuleEdit" (:component body)))
      (is (nil? (:alert_rule props))))))

;; ---------------------------------------------------------
;; Create

(deftest create-redirects-to-list-test
  (testing "POST /alert-rules creates a rule and redirects to list"
    (let [session (h/csrf-session)
          response (-create-rule! session)]
      (is (= 303 (h/status response)))
      (is (= "/alert-rules" (h/header response "location")))
      ;; Verify rule exists in the list
      (let [rules (-list-rules)
            rule (first rules)]
        (is (= 1 (count rules)))
        (is (= "Test Alert" (:name rule)))
        (is (= "events" (:query_mode rule)))
        (is (= 300000 (:eval_window_ms rule)))
        ;; Verify query data is a map (not a string or nil)
        (is (map? (:query rule))
            "query should be deserialized as a map")
        ;; Verify enabled is a boolean
        (is (true? (:enabled rule))
            "enabled should be true (boolean), not 1 (integer)")))))

;; ---------------------------------------------------------
;; Edit Page

(deftest edit-renders-existing-rule-test
  (testing "Edit page renders AlertRuleEdit with populated alert_rule"
    (let [session (h/csrf-session)]
      (-create-rule! session)
      (let [rule-id (:id (first (-list-rules)))
            response (h/inertia-json-request (str "/alert-rules/" rule-id "/edit"))
            body (h/body response)
            props (:props body)
            alert-rule (:alert_rule props)]
        (is (= 200 (h/status response)))
        (is (= "AlertRuleEdit" (:component body)))
        (is (= "Test Alert" (:name alert-rule)))
        (is (= "events" (:query_mode alert-rule)))
        (is (= 300000 (:eval_window_ms alert-rule)))
        ;; Verify query data is present (not nil/empty)
        (is (map? (:query alert-rule))
            "query should be a map, not nil or a string")
        (is (= {:type "table"} (:visualization (:query alert-rule))))
        ;; Verify enabled is a boolean
        (is (boolean? (:enabled alert-rule))
            "enabled should be a boolean, not an integer")))))

(deftest edit-returns-rich-query-data-test
  (testing "Edit page returns full query data (filters, aggregations, group_by, having)"
    (let [session (h/csrf-session)]
      (-create-rule! session {:name "Rich Query Alert"
                              :query (json/write-value-as-string rich-query)})
      (let [rule-id (:id (first (-list-rules)))
            response (h/inertia-json-request (str "/alert-rules/" rule-id "/edit"))
            alert-rule (get-in (h/body response) [:props :alert_rule])
            query (:query alert-rule)]
        (is (= "Rich Query Alert" (:name alert-rule)))
        ;; Verify all query fields round-trip correctly
        (is (= {:field "service" :op "=" :value "frontend"}
               (:filter query)))
        (is (= [{:id "A" :field "*" :function "count"}]
               (:aggregations query)))
        ;; Keys pass through as-is (snake_case) — no Inertia casing transform
        (is (= ["service"] (:group_by query)))
        (is (= {:ref "A" :op ">" :value 100}
               (:having query)))
        (is (= {:type "time_series"}
               (:visualization query)))))))

;; ---------------------------------------------------------
;; Update

(deftest update-modifies-rule-test
  (testing "PUT /alert-rules/:id updates a rule and redirects to list"
    (let [session (h/csrf-session)]
      (-create-rule! session)
      (let [rule-id (:id (first (-list-rules)))
            response (h/put-mutation
                       (str "/alert-rules/" rule-id) session
                       (merge sample-rule-body
                              {:name "Updated Alert"
                               :description "Updated description"
                               :eval_window_ms 900000
                               :eval_interval_ms 300000}))]
        (is (= 303 (h/status response)))
        (is (= "/alert-rules" (h/header response "location")))
        ;; Verify the update
        (let [rule (first (-list-rules))]
          (is (= "Updated Alert" (:name rule)))
          (is (= 900000 (:eval_window_ms rule))))))))

;; ---------------------------------------------------------
;; Delete

(deftest delete-removes-rule-test
  (testing "DELETE /alert-rules/:id removes a rule and redirects to list"
    (let [session (h/csrf-session)]
      (-create-rule! session)
      (is (= 1 (count (-list-rules))))
      (let [rule-id (:id (first (-list-rules)))
            response (h/delete-mutation
                       (str "/alert-rules/" rule-id) session)]
        (is (= 303 (h/status response)))
        (is (= "/alert-rules" (h/header response "location")))
        (is (empty? (-list-rules)))))))
