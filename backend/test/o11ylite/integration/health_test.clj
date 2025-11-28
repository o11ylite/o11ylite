;; ---------------------------------------------------------
;; o11ylite.integration.health-test
;;
;; Integration tests for health check endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.health-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; API Health Check

(deftest api-status-test
  (testing "GET /api/status returns JSON with ok status"
    (let [response (h/get-json "/api/status")]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= "ok" (get-in response [:body :status]))))))

;; ---------------------------------------------------------
;; Page Health Check

(deftest page-health-test
  (testing "GET /health returns JSON health status"
    (let [response (h/get-json "/health")]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= "ok" (get-in response [:body :status]))))))
