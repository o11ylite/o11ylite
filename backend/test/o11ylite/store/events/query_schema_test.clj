;; ---------------------------------------------------------
;; o11ylite.store.events.query-schema-test
;;
;; Unit tests for events query schema validation.
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [o11ylite.store.events.query-schema :as schema]))

;; ---------------------------------------------------------
;; Helper

(defn valid? [data]
  (nil? (schema/validate schema/events-query data)))

(defn invalid? [data]
  (some? (schema/validate schema/events-query data)))

;; ---------------------------------------------------------
;; Time Range Validation

(deftest time-range-validation-test
  (testing "time_range is required"
    (is (invalid? {:visualization {:type "table"}})))

  (testing "time_range.start is required"
    (is (invalid? {:time_range {:end 1702003600000}
                   :visualization {:type "table"}})))

  (testing "time_range.end is required"
    (is (invalid? {:time_range {:start 1702000000000}
                   :visualization {:type "table"}})))

  (testing "valid time_range"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "table"}}))))

;; ---------------------------------------------------------
;; Visualization Validation

(deftest visualization-validation-test
  (testing "visualization is required"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}})))

  (testing "visualization.type must be valid"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "pie_chart"}})))

  (testing "table visualization"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "table"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :limit 100
                 :visualization {:type "table"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :limit 10001
                   :visualization {:type "table"}})))

  (testing "time_series visualization requires at least one aggregation"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "time_series"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations []
                   :visualization {:type "time_series"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}]
                 :visualization {:type "time_series"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}]
                 :visualization {:type "time_series" :bucket_ms 60000}})))

  ;; DEFERRED: Heatmap visualization is deferred to post-v1.
  ;; Schema validation is kept to ensure API contract is stable when implemented.
  (testing "heatmap visualization requires exactly one group_by (DEFERRED)"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "heatmap"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :group_by []
                   :visualization {:type "heatmap"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :group_by ["field1" "field2"]
                   :visualization {:type "heatmap"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :group_by ["duration_ms"]
                 :visualization {:type "heatmap"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :group_by ["duration_ms"]
                 :visualization {:type "heatmap" :y_buckets 100}})))

  ;; Trace: Part of v1, accessed via dedicated /trace/:id page
  (testing "trace visualization requires trace_id = X filter"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "trace"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "service" :op "=" :value "api"}
                   :visualization {:type "trace"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "trace_id" :op "contains" :value "abc"}
                   :visualization {:type "trace"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "trace_id" :op "=" :value "abc123"}
                 :visualization {:type "trace"}}))))

;; ---------------------------------------------------------
;; Filter Validation

(deftest filter-validation-test
  (testing "simple filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "service" :op "=" :value "api"}
                 :visualization {:type "table"}})))

  (testing "starts-with operator"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "service" :op "starts-with" :value "api"}
                 :visualization {:type "table"}})))

  (testing "invalid filter op"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "service" :op "like" :value "api"}
                   :visualization {:type "table"}})))

  (testing "and filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:and [{:field "service" :op "=" :value "api"}
                                {:field "status" :op ">=" :value 500}]}
                 :visualization {:type "table"}})))

  (testing "or filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:or [{:field "status" :op "=" :value 404}
                               {:field "status" :op "=" :value 500}]}
                 :visualization {:type "table"}})))

  (testing "nested filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:and [{:field "service" :op "=" :value "api"}
                                {:or [{:field "status" :op ">=" :value 500}
                                      {:field "error" :op "exists" :value true}]}]}
                 :visualization {:type "table"}}))))

;; ---------------------------------------------------------
;; Group By Validation

(deftest group-by-validation-test
  (testing "valid group_by"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}]
                 :group_by ["service"]
                 :visualization {:type "time_series"}})))

  (testing "multiple group_by fields"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}]
                 :group_by ["service" "environment"]
                 :visualization {:type "time_series"}}))))

;; ---------------------------------------------------------
;; Aggregation Validation

(deftest aggregation-validation-test
  (testing "valid aggregation"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}]
                 :visualization {:type "time_series"}})))

  (testing "aggregation with numeric field"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "duration_ms" :function "p99"}]
                 :visualization {:type "time_series"}})))

  (testing "invalid aggregation function"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:field "*" :function "median"}]
                   :visualization {:type "time_series"}})))

  (testing "multiple aggregations"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}
                                {:field "duration_ms" :function "avg"}
                                {:field "duration_ms" :function "p99"}]
                 :visualization {:type "time_series"}}))))

;; ---------------------------------------------------------
;; Field Name Validation (SQL Injection Prevention)

(deftest field-name-validation-test
  (testing "valid field names"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "service" :op "=" :value "api"}
                 :visualization {:type "table"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "attr.http.method" :op "=" :value "GET"}
                 :visualization {:type "table"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "_private_field" :op "=" :value "x"}
                 :visualization {:type "table"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "field_with_123" :op "=" :value "x"}
                 :visualization {:type "table"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "field-with-dashes" :op "=" :value "x"}
                 :visualization {:type "table"}})))

  (testing "rejects SQL injection attempts in filter field"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "service; DROP TABLE events; --" :op "=" :value "x"}
                   :visualization {:type "table"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "field\"injection" :op "=" :value "x"}
                   :visualization {:type "table"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "field'injection" :op "=" :value "x"}
                   :visualization {:type "table"}})))

  (testing "rejects SQL injection in group_by"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:field "*" :function "count"}]
                   :group_by ["service; DROP TABLE events;"]
                   :visualization {:type "table"}})))

  (testing "rejects SQL injection in aggregation field"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:field "duration); DROP TABLE events; --" :function "avg"}]
                   :visualization {:type "table"}})))

  (testing "field name must start with letter or underscore"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "123field" :op "=" :value "x"}
                   :visualization {:type "table"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field ".dotfirst" :op "=" :value "x"}
                   :visualization {:type "table"}}))))

;; ---------------------------------------------------------
;; Cursor Validation

(deftest cursor-validation-test
  (testing "cursor is valid for table queries without aggregations"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
                 :visualization {:type "table"}})))

  (testing "cursor is invalid with time_series visualization"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:field "*" :function "count"}]
                   :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
                   :visualization {:type "time_series"}})))

  (testing "cursor is invalid with table + aggregations"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:field "*" :function "count"}]
                   :group_by ["service"]
                   :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
                   :visualization {:type "table"}})))

  (testing "cursor is invalid with heatmap visualization"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :group_by ["duration_ms"]
                   :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
                   :visualization {:type "heatmap"}})))

  (testing "cursor is invalid with trace visualization"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "trace_id" :op "=" :value "abc123"}
                   :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
                   :visualization {:type "trace"}}))))

;; ---------------------------------------------------------
;; Type-Aware Filter Validation

(def ^:private test-event-metadata
  "Mock event metadata for testing type-aware validation."
  {:service   {:type :string}
   :status    {:type :integer}
   :duration  {:type :float}
   :is_error  {:type :boolean}
   :timestamp {:type :instant}})

(defn- type-valid?
  "Check if filter operators are valid for field types."
  [query]
  (nil? (schema/validate-filter-ops-with-metadata test-event-metadata query)))

(defn- type-invalid?
  "Check if filter operators are invalid for field types."
  [query]
  (some? (schema/validate-filter-ops-with-metadata test-event-metadata query)))

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

  (testing "having clause validates operators too"
    (is (type-valid? {:having {:field "status" :op ">=" :value 500}})
        "valid having clause should pass")
    (is (type-invalid? {:having {:field "service" :op ">" :value "api"}})
        "invalid op in having clause should fail"))

  (testing "error message includes field info"
    (let [result (schema/validate-filter-ops-with-metadata
                  test-event-metadata
                  {:filter {:field "service" :op ">" :value "api"}})]
      (is (some? result) "should return error")
      (is (string? (:error result)) "error should be a string")
      (is (.contains (:error result) "service") "error should mention field name")
      (is (.contains (:error result) ">") "error should mention invalid operator")
      (is (.contains (:error result) ":string") "error should mention field type"))))
