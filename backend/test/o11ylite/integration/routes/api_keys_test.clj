;; ---------------------------------------------------------
;; o11ylite.integration.routes.api-keys-test
;;
;; Integration tests for API key management routes.
;; Tests the CRUD flow: list, create, delete.
;; API keys are immutable — no update/edit.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.api-keys-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- -create-key!
  "Create an API key via POST. Returns the response (303 redirect)."
  ([session] (-create-key! session {}))
  ([session overrides]
   (h/post-mutation "/system/api-keys" session
                    (merge {:name "Test Key" :scope "ingest"} overrides))))

(defn- -list-keys
  "Fetch API keys via Inertia JSON request."
  []
  (get-in (h/body (h/inertia-json-request "/system/api-keys"))
          [:props :api_keys]))

;; ---------------------------------------------------------
;; List Page

(deftest list-renders-html-test
  (testing "API keys list page renders HTML"
    (let [response (h/get-request "/system/api-keys")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      (is (str/includes? (h/body response) "ApiKeys")))))

(deftest list-returns-inertia-props-test
  (testing "API keys Inertia response includes api_keys"
    (let [response (h/inertia-json-request "/system/api-keys")
          body (h/body response)
          props (:props body)]
      (is (= 200 (h/status response)))
      (is (= "ApiKeys" (:component body)))
      (is (vector? (:api_keys props))))))

;; ---------------------------------------------------------
;; New Page

(deftest new-renders-inertia-page-test
  (testing "New API key page renders ApiKeyCreate"
    (let [response (h/inertia-json-request "/system/api-keys/new")
          body (h/body response)]
      (is (= 200 (h/status response)))
      (is (= "ApiKeyCreate" (:component body))))))

;; ---------------------------------------------------------
;; Create

(deftest create-redirects-to-list-test
  (testing "POST /system/api-keys creates a key and redirects to list"
    (let [session (h/csrf-session "/system/api-keys")
          response (-create-key! session {:name "Prod Ingest" :scope "ingest"})]
      (is (= 303 (h/status response)))
      (is (= "/system/api-keys" (h/header response "location")))
      ;; Verify key exists in the list
      (let [keys (-list-keys)
            key-row (first keys)]
        (is (= 1 (count keys)))
        (is (= "Prod Ingest" (:name key-row)))
        (is (= "ingest" (:scope key-row)))
        ;; Prefix should start with "o11y_"
        (is (str/starts-with? (:prefix key-row) "o11y_"))
        ;; key_hash should NOT be in the list response
        (is (nil? (:key_hash key-row)))))))

(deftest create-all-scopes-test
  (testing "API keys can be created with all valid scopes"
    (let [session (h/csrf-session "/system/api-keys")]
      (doseq [scope ["ingest" "read" "write" "admin"]]
        (-create-key! session {:name (str "Key " scope) :scope scope}))
      (let [keys (-list-keys)]
        (is (= 4 (count keys)))
        (is (= #{"ingest" "read" "write" "admin"}
               (into #{} (map :scope) keys)))))))

(deftest create-validation-error-test
  (testing "POST with empty name redirects back with errors"
    (let [session (h/csrf-session "/system/api-keys")
          response (-create-key! session {:name "" :scope "ingest"})]
      (is (= 303 (h/status response)))
      (is (= "/system/api-keys/new" (h/header response "location"))))))

;; ---------------------------------------------------------
;; Delete

(deftest delete-removes-key-test
  (testing "DELETE /system/api-keys/:id removes a key and redirects"
    (let [session (h/csrf-session "/system/api-keys")]
      (-create-key! session)
      (is (= 1 (count (-list-keys))))
      (let [key-id (:id (first (-list-keys)))
            response (h/delete-mutation
                       (str "/system/api-keys/" key-id) session)]
        (is (= 303 (h/status response)))
        (is (= "/system/api-keys" (h/header response "location")))
        (is (empty? (-list-keys)))))))
