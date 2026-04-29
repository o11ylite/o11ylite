;; ---------------------------------------------------------
;; o11ylite.util.validation-test
;;
;; Unit tests for converting Malli humanized errors into the
;; flat {field message} shape the Inertia layer expects.
;; ---------------------------------------------------------

(ns o11ylite.util.validation-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.util.validation :as v]))

(deftest flatten-for-inertia-test
  (testing "flattens top-level field errors"
    (is (= {"name" "missing required key"}
           (v/flatten-for-inertia {:name ["missing required key"]}))))

  (testing "joins nested map keys into a single message"
    (is (= {"query" "metrics: should have at least 1 elements"}
           (v/flatten-for-inertia
             {:query {:metrics ["should have at least 1 elements"]}}))))

  (testing "deeply nested errors include the full path"
    (is (= {"query" "metrics: 0: id: missing required key"}
           (v/flatten-for-inertia
             {:query {:metrics {0 {:id ["missing required key"]}}}})))))

(deftest cross-field-validator-shape-test
  ;; Schemas should attach :fn validator errors to a specific field via
  ;; :error/path. When they do, this is what flatten-for-inertia emits —
  ;; the same map shape the Inertia middleware and frontend expect.
  (testing "field-attached :fn errors produce a regular field map"
    (is (= {"alert_target"
            "alert_target must reference a declared metric or formula id"}
           (v/flatten-for-inertia
             {:alert_target
              ["alert_target must reference a declared metric or formula id"]})))))
