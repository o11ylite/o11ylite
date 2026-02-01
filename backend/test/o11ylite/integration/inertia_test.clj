;; ---------------------------------------------------------
;; o11ylite.integration.inertia-test
;;
;; Integration tests for generic Inertia.js behavior.
;; Tests protocol-level functionality that applies to all Inertia pages.
;; ---------------------------------------------------------

(ns o11ylite.integration.inertia-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; HTML Structure

(deftest html-structure-test
  (testing "Inertia HTML has correct document structure"
    (let [response (h/get-request "/")
          html (h/body response)]
      ;; Check DOCTYPE
      (is (str/starts-with? html "<!DOCTYPE html>"))
      ;; Check essential elements
      (is (str/includes? html "<html"))
      (is (str/includes? html "<head"))
      (is (str/includes? html "<body"))
      ;; Check meta tags
      (is (str/includes? html "charset=\"utf-8\""))
      (is (str/includes? html "viewport"))
      ;; Check title
      (is (str/includes? html "<title>"))
      ;; Check module script for JS entry
      (is (str/includes? html "type=\"module\"")))))

;; ---------------------------------------------------------
;; XHR Response Headers

(deftest xhr-response-headers-test
  (testing "Inertia XHR response includes correct headers"
    (let [response (h/inertia-json-request "/")]
      (is (= "true" (h/header response "x-inertia")))
      (is (h/json-response? response)))))

(deftest xhr-response-structure-test
  (testing "Inertia XHR response has required fields"
    (let [response (h/inertia-json-request "/")
          body (h/body response)]
      (is (string? (:component body)))
      (is (map? (:props body)))
      (is (string? (:url body)))
      (is (some? (:version body))))))

;; ---------------------------------------------------------
;; Asset Version Handling

(deftest version-mismatch-test
  (testing "Version mismatch returns 409 with X-Inertia-Location header"
    (let [response (h/inertia-request "/" {:version "wrong-version"})]
      (is (= 409 (h/status response)))
      (is (= "/" (h/header response "x-inertia-location"))))))

(deftest version-match-test
  (testing "Matching version returns normal 200 response"
    (let [response (h/inertia-json-request "/" {:version "dev"})]
      (is (= 200 (h/status response))))))

;; ---------------------------------------------------------
;; CSRF Protection

(deftest csrf-cookie-test
  (testing "Page routes set XSRF-TOKEN cookie"
    (let [response (h/get-request "/")
          set-cookie (h/header response "set-cookie")]
      (is (some? set-cookie))
      (is (str/includes? (or set-cookie "") "XSRF-TOKEN")))))

;; ---------------------------------------------------------
;; 404 Handling

(deftest not-found-test
  (testing "Non-existent route returns 404"
    (let [response (h/get-request "/nonexistent/path")]
      (is (= 404 (h/status response))))))
