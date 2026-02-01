;; ---------------------------------------------------------
;; o11ylite.integration.api.health-test
;;
;; Integration tests for health check API endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.api.health-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; API Health Checks

(deftest api-status-test
  (testing "GET /api/status returns JSON with ok status"
    (let [response (h/get-json "/api/status")]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= "ok" (get-in response [:body :status]))))))

(deftest api-health-test
  (testing "GET /api/health returns JSON with ok status"
    (let [response (h/get-json "/api/health")]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= "ok" (get-in response [:body :status]))))))
