;; ---------------------------------------------------------
;; o11ylite.integration.api.events-test
;;
;; Integration tests for events metadata API endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.api.events-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.components.blocked-fields :as blocked-fields]
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

(deftest list-fields-excludes-blocked-test
  (testing "GET /api/events/fields excludes blocked fields"
    (let [bf (:cache/blocked-fields h/*system*)
          sqlite (:db/sqlite h/*system*)
          ;; Verify "service" is present before blocking
          before-names (set (map :name (:body (h/get-json "/api/events/fields"))))]
      (is (contains? before-names "service"))
      ;; Block the "service" field
      (blocked-fields/block-event-fields! bf sqlite ["service"])
      (let [after-response (h/get-json "/api/events/fields")
            after-names (set (map :name (:body after-response)))]
        (is (= 200 (h/status after-response)))
        (is (not (contains? after-names "service"))
            "Blocked field should not appear in the response")
        ;; Other fields should still be present
        (is (contains? after-names "timestamp"))))))
