;; ---------------------------------------------------------
;; o11ylite.integration.routes.explore-test
;;
;; Integration tests for the Explore page.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.explore-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.store.services :as services]
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

(deftest explore-returns-services-and-fields-test
  (testing "Explore Inertia response includes services and fields props"
    (let [response (h/inertia-json-request "/explore")
          body (h/body response)
          props (:props body)]
      (is (= 200 (h/status response)))
      (is (= "Explore" (:component body)))
      ;; Props should include services and fields as arrays
      (is (vector? (:services props)))
      (is (vector? (:fields props))))))

(deftest explore-returns-registered-services-test
  (testing "Explore returns services from the database"
    ;; Register some test services
    (let [sqlite (:db/sqlite h/*system*)]
      (services/register-services! sqlite ["test-service-a" "test-service-b"]))

    (let [response (h/inertia-json-request "/explore")
          body (h/body response)
          service-names (->> (get-in body [:props :services])
                             (map :name)
                             set)]
      (is (contains? service-names "test-service-a"))
      (is (contains? service-names "test-service-b")))))

(deftest explore-returns-field-metadata-test
  (testing "Explore returns field metadata as sorted array with name and type"
    (let [response (h/inertia-json-request "/explore")
          body (h/body response)
          fields (get-in body [:props :fields])
          field-names (set (map :name fields))]
      ;; Core fields should be present (from schema init)
      (is (contains? field-names "service"))
      (is (contains? field-names "timestamp"))
      ;; Each field should have :name and :type
      (let [service-field (first (filter #(= "service" (:name %)) fields))]
        (is (= "string" (:type service-field))))
      ;; Fields should be sorted by name
      (is (= (map :name fields) (sort (map :name fields)))))))
