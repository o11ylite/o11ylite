;; ---------------------------------------------------------
;; o11ylite.integration.routes.home-test
;;
;; Integration tests for the Home page redirect.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.home-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Home Redirect
;; Note: The HTTP client follows redirects automatically, so we test
;; that we end up at /explore with the Explore component rendered.

(deftest home-redirects-to-explore-test
  (testing "Home page redirects to /explore and renders Explore component"
    (let [response (h/get-request "/")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      ;; After redirect, we should see the Explore component
      (is (str/includes? (h/body response) "Explore")))))
