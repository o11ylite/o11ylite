;; ---------------------------------------------------------
;; o11ylite.integration.routes.about-test
;;
;; Integration tests for the About page.
;; ---------------------------------------------------------

(ns o11ylite.integration.routes.about-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each (h/with-partial-system [:server/web]))

;; ---------------------------------------------------------
;; Page render

(deftest about-page-test
  (testing "renders HTML with About component"
    (let [response (h/get-request "/system/about")]
      (is (= 200 (h/status response)))
      (is (h/html-response? response))
      (is (str/includes? (h/body response) "About"))))

  (testing "returns Inertia JSON with expected prop keys"
    (let [response (h/inertia-json-request "/system/about")
          body (h/body response)]
      (is (= 200 (h/status response)))
      (is (= "About" (:component body)))
      (let [props (:props body)]
        (is (string? (:o11ylite_version props)))
        (is (string? (:duckdb_version props)))
        (is (string? (:ducklake_version props)))
        (is (string? (:sqlite_version props)))
        (is (integer? (:events_count props)))
        (is (integer? (:metrics_count props)))
        (is (integer? (:parquet_files props)))
        (is (string? (:parquet_data_size props)))))))
