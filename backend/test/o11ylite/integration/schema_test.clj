;; ---------------------------------------------------------
;; o11ylite.integration.schema-test
;;
;; Integration tests for DuckLake schema operations.
;; ---------------------------------------------------------

(ns o11ylite.integration.schema-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.ducklake.schema :as schema]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helper to get DuckDB datasource from system

(defn- duckdb []
  (:db/duckdb h/*system*))

;; ---------------------------------------------------------
;; Tests

(deftest add-fields-single-test
  (testing "add-fields! adds a single field for each app type"
    (let [ds (duckdb)
          test-cases [{:type :string  :field "test.string.field"}
                      {:type :integer :field "test.integer.field"}
                      {:type :float   :field "test.float.field"}
                      {:type :boolean :field "test.boolean.field"}
                      {:type :instant :field "test.instant.field"}]]
      (doseq [{:keys [type field]} test-cases]
        (testing (str "type " type)
          (let [fields-before (schema/fetch-event-fields ds)]
            (is (nil? (get fields-before field)))
            (schema/add-fields! ds {field {:type type}})
            (let [fields-after (schema/fetch-event-fields ds)]
              (is (= type (:type (get fields-after field)))))))))))

(deftest add-fields-multiple-test
  (testing "add-fields! adds multiple fields of different types"
    (let [ds (duckdb)
          new-fields {"multi.string" {:type :string}
                      "multi.integer" {:type :integer}
                      "multi.float" {:type :float}}
          fields-before (schema/fetch-event-fields ds)]
      (doseq [field-name (keys new-fields)]
        (is (nil? (get fields-before field-name))))
      (schema/add-fields! ds new-fields)
      (let [fields-after (schema/fetch-event-fields ds)]
        (is (= :string (:type (get fields-after "multi.string"))))
        (is (= :integer (:type (get fields-after "multi.integer"))))
        (is (= :float (:type (get fields-after "multi.float"))))))))

(deftest add-fields-empty-map-test
  (testing "add-fields! with empty map is a no-op"
    (let [ds (duckdb)
          fields-before (schema/fetch-event-fields ds)]
      (schema/add-fields! ds {})
      (let [fields-after (schema/fetch-event-fields ds)]
        (is (= fields-before fields-after))))))

(deftest add-fields-idempotent-test
  (testing "add-fields! is idempotent (IF NOT EXISTS)"
    (let [ds (duckdb)
          field-name "test.idempotent.field"
          fields {field-name {:type :string}}]
      (schema/add-fields! ds fields)
      (let [fields-after-first (schema/fetch-event-fields ds)]
        (is (= :string (:type (get fields-after-first field-name))))
        ;; Adding same field again should not throw
        (schema/add-fields! ds fields)
        (let [fields-after-second (schema/fetch-event-fields ds)]
          (is (= fields-after-first fields-after-second)))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.schema-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
