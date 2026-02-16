;; ---------------------------------------------------------
;; o11ylite.integration.routes.explore-test
;;
;; Integration tests for the Explore page.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.explore-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; HTML Response

(deftest explore-renders-html-test
  (testing "Explore page renders HTML with Explore component"
    (let [response (h/get-request "/explore")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      (is (str/includes? (h/body response) "Explore")))))

;; ---------------------------------------------------------
;; Inertia XHR Response

(deftest explore-returns-inertia-page-test
  (testing "Explore Inertia response renders Explore component"
    (let [response (h/inertia-json-request "/explore")
          body (h/body response)]
      (is (= 200 (h/status response)))
      (is (= "Explore" (:component body))))))
