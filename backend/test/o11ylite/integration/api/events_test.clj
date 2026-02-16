;; ---------------------------------------------------------
;; o11ylite.integration.api.events-test
;;
;; Integration tests for events metadata API endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.api.events-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; GET /api/events/fields

(deftest list-fields-test
  (testing "GET /api/events/fields returns field metadata as sorted array"
    (let [response (h/get-json "/api/events/fields")
          fields (:body response)
          field-names (set (map :name fields))]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (vector? fields))
      ;; Core fields should be present (from schema init)
      (is (contains? field-names "service"))
      (is (contains? field-names "timestamp"))
      ;; Each field should have :name and :type
      (let [service-field (first (filter #(= "service" (:name %)) fields))]
        (is (= "string" (:type service-field))))
      ;; Fields should be sorted by name
      (is (= (map :name fields) (sort (map :name fields)))))))
