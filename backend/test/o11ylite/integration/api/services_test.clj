;; ---------------------------------------------------------
;; o11ylite.integration.api.services-test
;;
;; Integration tests for services API endpoint.
;; ---------------------------------------------------------

(ns o11ylite.integration.api.services-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.store.services :as services]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; GET /api/services

(deftest list-services-empty-test
  (testing "GET /api/services returns empty array when no services registered"
    (let [response (h/get-json "/api/services")]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= [] (:body response))))))

(deftest list-services-returns-registered-test
  (testing "GET /api/services returns registered services"
    (let [sqlite (:db/sqlite h/*system*)]
      (services/upsert-services! sqlite
                                 ["test-service-a" "test-service-b"]
                                 (System/currentTimeMillis)))

    (let [response (h/get-json "/api/services")
          service-names (->> (:body response) (map :name) set)]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (contains? service-names "test-service-a"))
      (is (contains? service-names "test-service-b")))))
