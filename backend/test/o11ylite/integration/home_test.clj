;; ---------------------------------------------------------
;; o11ylite.integration.home-test
;;
;; Integration tests for the Home page.
;; ---------------------------------------------------------

(ns o11ylite.integration.home-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Initial Page Load (HTML)

(deftest home-page-html-test
  (testing "Home page returns HTML with Inertia data-page attribute"
    (let [response (h/get-request "/")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      ;; Check HTML contains Inertia container div
      (is (str/includes? (h/body response) "id=\"app\""))
      (is (str/includes? (h/body response) "data-page="))
      ;; Check that page data contains the Home component
      (is (str/includes? (h/body response) "Home")))))

;; ---------------------------------------------------------
;; Inertia XHR Request (JSON)

(deftest home-page-xhr-test
  (testing "Home page XHR request returns correct Inertia JSON with props"
    (let [response (h/inertia-json-request "/")
          body (h/body response)]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      ;; Verify component and URL
      (is (= "Home" (:component body)))
      (is (= "/" (:url body)))
      ;; Verify props
      (is (= "Welcome to O11yLite" (get-in body [:props :greeting]))))))
