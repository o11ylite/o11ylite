;; ---------------------------------------------------------
;; o11ylite.store.events.query-validation-test
;;
;; Unit tests for metadata-aware validation of events queries.
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-validation-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.store.events.query-validation :as validation]))

;; ---------------------------------------------------------
;; Type-Aware Filter Validation

(def ^:private test-events-schema
  "Mock event metadata for testing type-aware validation."
  {:service   {:type :string}
   :status    {:type :integer}
   :duration  {:type :float}
   :is_error  {:type :boolean}
   :timestamp {:type :instant}})

(defn- type-valid?
  "Check if filter operators are valid for field types."
  [query]
  (nil? (validation/validate-filter-ops-with-metadata test-events-schema query)))

(defn- type-invalid?
  "Check if filter operators are invalid for field types."
  [query]
  (some? (validation/validate-filter-ops-with-metadata test-events-schema query)))

(deftest type-aware-filter-validation-test
  (testing "string fields accept string operators"
    (doseq [op ["=" "!=" "contains" "exists" "starts-with"]]
      (is (type-valid? {:filter {:field "service" :op op :value "api"}})
          (str "string field should accept operator: " op))))

  (testing "string fields reject numeric operators"
    (doseq [op [">" "<" ">=" "<="]]
      (is (type-invalid? {:filter {:field "service" :op op :value "api"}})
          (str "string field should reject operator: " op))))

  (testing "integer fields accept numeric operators"
    (doseq [op ["=" "!=" ">" "<" ">=" "<=" "exists"]]
      (is (type-valid? {:filter {:field "status" :op op :value 500}})
          (str "integer field should accept operator: " op))))

  (testing "integer fields reject string operators"
    (doseq [op ["contains" "starts-with"]]
      (is (type-invalid? {:filter {:field "status" :op op :value 500}})
          (str "integer field should reject operator: " op))))

  (testing "float fields accept numeric operators"
    (doseq [op ["=" "!=" ">" "<" ">=" "<=" "exists"]]
      (is (type-valid? {:filter {:field "duration" :op op :value 100.5}})
          (str "float field should accept operator: " op))))

  (testing "float fields reject string operators"
    (doseq [op ["contains" "starts-with"]]
      (is (type-invalid? {:filter {:field "duration" :op op :value 100.5}})
          (str "float field should reject operator: " op))))

  (testing "boolean fields accept boolean operators"
    (doseq [op ["=" "!=" "exists"]]
      (is (type-valid? {:filter {:field "is_error" :op op :value true}})
          (str "boolean field should accept operator: " op))))

  (testing "boolean fields reject comparison and string operators"
    (doseq [op [">" "<" ">=" "<=" "contains" "starts-with"]]
      (is (type-invalid? {:filter {:field "is_error" :op op :value true}})
          (str "boolean field should reject operator: " op))))

  (testing "instant fields accept comparison operators"
    (doseq [op ["=" "!=" ">" "<" ">=" "<=" "exists"]]
      (is (type-valid? {:filter {:field "timestamp" :op op :value 1702000000000}})
          (str "instant field should accept operator: " op))))

  (testing "instant fields reject string operators"
    (doseq [op ["contains" "starts-with"]]
      (is (type-invalid? {:filter {:field "timestamp" :op op :value 1702000000000}})
          (str "instant field should reject operator: " op))))

  (testing "unknown fields are allowed (skipped validation)"
    (is (type-valid? {:filter {:field "unknown_field" :op ">" :value 100}})
        "unknown fields should be allowed to support querying before data exists"))

  (testing "nested AND filters validate all clauses"
    (is (type-valid? {:filter {:and [{:field "service" :op "=" :value "api"}
                                     {:field "status" :op ">=" :value 500}]}})
        "valid nested AND should pass")
    (is (type-invalid? {:filter {:and [{:field "service" :op ">" :value "api"}
                                       {:field "status" :op ">=" :value 500}]}})
        "invalid op in nested AND should fail"))

  (testing "nested OR filters validate all clauses"
    (is (type-valid? {:filter {:or [{:field "status" :op "=" :value 404}
                                    {:field "status" :op "=" :value 500}]}})
        "valid nested OR should pass")
    (is (type-invalid? {:filter {:or [{:field "service" :op ">" :value "api"}
                                      {:field "status" :op "=" :value 500}]}})
        "invalid op in nested OR should fail"))

  (testing "deeply nested filters validate correctly"
    (is (type-valid? {:filter {:and [{:field "service" :op "=" :value "api"}
                                     {:or [{:field "status" :op ">=" :value 500}
                                           {:field "is_error" :op "=" :value true}]}]}})
        "valid deeply nested filter should pass")
    (is (type-invalid? {:filter {:and [{:field "service" :op "=" :value "api"}
                                       {:or [{:field "status" :op "contains" :value "500"}
                                             {:field "is_error" :op "=" :value true}]}]}})
        "invalid op in deeply nested filter should fail"))

  (testing "error message includes field info"
    (let [result (validation/validate-filter-ops-with-metadata
                   test-events-schema
                   {:filter {:field "service" :op ">" :value "api"}})]
      (is (some? result) "should return error")
      (is (string? (:error result)) "error should be a string")
      (is (.contains (:error result) "service") "error should mention field name")
      (is (.contains (:error result) ">") "error should mention invalid operator")
      (is (.contains (:error result) ":string") "error should mention field type"))))
