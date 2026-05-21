;; ---------------------------------------------------------
;; o11ylite.integration.schema-test
;;
;; Integration tests for DuckLake schema operations.
;; ---------------------------------------------------------

(ns o11ylite.integration.schema-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.store.schema :as schema]
    [o11ylite.test-helpers :as h]))

;; Only start storage (creates events table) and the read pool we exercise.
(use-fixtures :each (h/with-partial-system [:storage/init :db/duckdb-reader]))

;; ---------------------------------------------------------
;; Helper to get DuckDB datasource from system

(defn- duckdb
  []
  (:db/duckdb-reader h/*system*))

;; ---------------------------------------------------------
;; Tests

(deftest add-fields-single-test
  (testing "add-fields! adds a single field for each app type"
    (let [ds (duckdb)
          test-cases [{:type :string  :field :test.string.field}
                      {:type :integer :field :test.integer.field}
                      {:type :float   :field :test.float.field}
                      {:type :boolean :field :test.boolean.field}
                      {:type :instant :field :test.instant.field}]]
      (doseq [{:keys [type field]} test-cases]
        (testing (str "type " type)
          (let [fields-before (schema/fetch-event-fields ds)]
            (is (nil? (get fields-before field)))
            (schema/add-event-fields! ds {field {:type type}})
            (let [fields-after (schema/fetch-event-fields ds)]
              (is (= type (:type (get fields-after field)))))))))))

(deftest add-fields-multiple-test
  (testing "add-fields! adds multiple fields of different types"
    (let [ds (duckdb)
          new-fields {:multi.string {:type :string}
                      :multi.integer {:type :integer}
                      :multi.float {:type :float}}
          fields-before (schema/fetch-event-fields ds)]
      (doseq [field-key (keys new-fields)]
        (is (nil? (get fields-before field-key))))
      (schema/add-event-fields! ds new-fields)
      (let [fields-after (schema/fetch-event-fields ds)]
        (is (= :string (:type (:multi.string fields-after))))
        (is (= :integer (:type (:multi.integer fields-after))))
        (is (= :float (:type (:multi.float fields-after))))))))

(deftest add-fields-empty-map-test
  (testing "add-fields! with empty map is a no-op"
    (let [ds (duckdb)
          fields-before (schema/fetch-event-fields ds)]
      (schema/add-event-fields! ds {})
      (let [fields-after (schema/fetch-event-fields ds)]
        (is (= fields-before fields-after))))))

(deftest add-fields-idempotent-test
  (testing "add-fields! is idempotent (IF NOT EXISTS)"
    (let [ds (duckdb)
          field-key :test.idempotent.field
          fields {field-key {:type :string}}]
      (schema/add-event-fields! ds fields)
      (let [fields-after-first (schema/fetch-event-fields ds)]
        (is (= :string (:type (get fields-after-first field-key))))
        ;; Adding same field again should not throw
        (schema/add-event-fields! ds fields)
        (let [fields-after-second (schema/fetch-event-fields ds)]
          (is (= fields-after-first fields-after-second)))))))

(deftest drop-event-fields-test
  (testing "drop-event-fields! removes multiple columns from the events table"
    (let [ds (duckdb)]
      (schema/add-event-fields! ds {:attr.drop.a {:type :string}
                                    :attr.drop.b {:type :integer}
                                    :attr.drop.c {:type :float}})
      (let [fields-before (schema/fetch-event-fields ds)]
        (is (some? (get fields-before :attr.drop.a)))
        (is (some? (get fields-before :attr.drop.b)))
        (is (some? (get fields-before :attr.drop.c))))
      (schema/drop-event-fields! ds ["attr.drop.a" "attr.drop.b"])
      (let [fields-after (schema/fetch-event-fields ds)]
        (is (nil? (get fields-after :attr.drop.a)))
        (is (nil? (get fields-after :attr.drop.b)))
        (is (some? (get fields-after :attr.drop.c)))))))

(deftest drop-metric-fields-test
  (testing "drop-metric-fields! removes multiple columns from the metrics table"
    (let [ds (duckdb)]
      (schema/add-metrics-fields! ds #{:attr.drop.metric.a :attr.drop.metric.b :attr.drop.metric.c})
      (let [field-names (schema/fetch-metrics-field-names ds)]
        (is (contains? field-names :attr.drop.metric.a))
        (is (contains? field-names :attr.drop.metric.b))
        (is (contains? field-names :attr.drop.metric.c)))
      (schema/drop-metric-fields! ds ["attr.drop.metric.a" "attr.drop.metric.b"])
      (let [field-names (schema/fetch-metrics-field-names ds)]
        (is (not (contains? field-names :attr.drop.metric.a)))
        (is (not (contains? field-names :attr.drop.metric.b)))
        (is (contains? field-names :attr.drop.metric.c))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.schema-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
