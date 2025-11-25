;; ---------------------------------------------------------
;; o11ylite.backend.-test
;;
;; Example unit tests for o11ylite.backend
;;
;; - `deftest` - test a specific function
;; - `testing` logically group assertions within a function test
;; - `is` assertion:  expected value then function call
;; ---------------------------------------------------------


(ns o11ylite.backend-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [o11ylite.backend :as backend]))


(deftest application-test
  (testing "TODO: Start with a failing test, make it pass, then refactor"

    ;; TODO: fix greet function to pass test
    (is (= "o11ylite application developed by the secret engineering team"
           (backend/greet)))

    ;; TODO: fix test by calling greet with {:team-name "Practicalli Engineering"}
    (is (= (backend/greet "Practicalli Engineering")
           "o11ylite service developed by the Practicalli Engineering team"))))
