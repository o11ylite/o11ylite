;; ---------------------------------------------------------
;; o11ylite.store.events.query-schema-test
;;
;; Unit tests for events query schema validation.
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-schema-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.store.events.query-schema :as schema]
    [o11ylite.store.query-util :as query-util]))

;; ---------------------------------------------------------
;; Helper

(defn valid?
  [data]
  (nil? (schema/validate schema/events-query data)))

(defn invalid?
  [data]
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

  (testing "table visualization with displayed_fields"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "table"
                                 :displayed_fields ["timestamp" "service" "name"]}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "table"
                                 :displayed_fields ["attr.http.method" "span.duration_ms"]}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "table"
                                   :displayed_fields ["bad;injection"]}})))

  (testing "time_series visualization requires at least one aggregation"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "time_series"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations []
                   :visualization {:type "time_series"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :visualization {:type "time_series"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :visualization {:type "time_series" :bucket_ms 60000}})))

  (testing "time_series render_as enum"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :visualization {:type "time_series" :render_as "line"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :visualization {:type "time_series" :render_as "stacked_area"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :visualization {:type "time_series" :render_as "bar"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :visualization {:type "time_series" :render_as "pie"}})))

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
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :group_by ["service"]
                 :visualization {:type "time_series"}})))

  (testing "multiple group_by fields"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :group_by ["service" "environment"]
                 :visualization {:type "time_series"}}))))

;; ---------------------------------------------------------
;; Aggregation Validation

(deftest aggregation-validation-test
  (testing "valid aggregation with id"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :visualization {:type "time_series"}})))

  (testing "aggregation requires id"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:field "*" :function "count"}]
                   :visualization {:type "time_series"}})))

  (testing "aggregation id must be single uppercase letter"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "a" :field "*" :function "count"}]
                   :visualization {:type "time_series"}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "AB" :field "*" :function "count"}]
                   :visualization {:type "time_series"}})))

  (testing "duplicate aggregation IDs not allowed"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}
                                  {:id "A" :field "duration_ms" :function "avg"}]
                   :visualization {:type "time_series"}})))

  (testing "aggregation with numeric field"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "duration_ms" :function "p99"}]
                 :visualization {:type "time_series"}})))

  (testing "invalid aggregation function"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "median"}]
                   :visualization {:type "time_series"}})))

  (testing "multiple aggregations with unique IDs"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}
                                {:id "B" :field "duration_ms" :function "avg"}
                                {:id "C" :field "duration_ms" :function "p99"}]
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
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service; DROP TABLE events;"]
                   :visualization {:type "table"}})))

  (testing "rejects SQL injection in aggregation field"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "duration); DROP TABLE events; --" :function "avg"}]
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
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :cursor "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1fQ=="
                   :visualization {:type "time_series"}})))

  (testing "cursor is invalid with table + aggregations"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
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
;;
;; (tests moved to o11ylite.store.events.query-validation-test)

(def ^:private test-events-schema
  "Mock event metadata for testing. Used below by normalize-filter-test."
  {:service   {:type :string}
   :status    {:type :integer}
   :duration  {:type :float}
   :is_error  {:type :boolean}
   :timestamp {:type :instant}})

;; ---------------------------------------------------------
;; Sort Config Validation (ref-based sort)

(deftest sort-config-validation-test
  (testing "sort by raw field is valid for non-aggregated queries"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "table" :sort {:field "service" :order "asc"}}})))

  (testing "sort by ref is valid when aggregation with matching ID exists"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :group_by ["service"]
                 :visualization {:type "table" :sort {:ref "A" :order "desc"}}})))

  (testing "sort by ref is invalid when ref doesn't match any aggregation"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service"]
                   :visualization {:type "table" :sort {:ref "B" :order "desc"}}})))

  (testing "sort by ref is invalid without aggregations"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "table" :sort {:ref "A" :order "desc"}}})))

  (testing "sort by ref with multiple aggregations"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}
                                {:id "B" :field "duration_ms" :function "avg"}]
                 :group_by ["service"]
                 :visualization {:type "table" :sort {:ref "B" :order "asc"}}}))))

;; ---------------------------------------------------------
;; Having Validation

(deftest having-validation-test
  (testing "having is valid with aggregations and matching ref"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :group_by ["service"]
                 :having {:ref "A" :op ">" :value 100}
                 :visualization {:type "table"}})))

  (testing "having requires aggregations"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :having {:ref "A" :op ">" :value 100}
                   :visualization {:type "table"}})))

  (testing "having ref must match an aggregation ID"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service"]
                   :having {:ref "B" :op ">" :value 100}
                   :visualization {:type "table"}})))

  (testing "having supports all numeric operators"
    (doseq [op [">" "<" ">=" "<=" "=" "!="]]
      (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service"]
                   :having {:ref "A" :op op :value 100}
                   :visualization {:type "table"}})
          (str "having should accept operator: " op))))

  (testing "having value must be numeric"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service"]
                   :having {:ref "A" :op ">" :value "not-a-number"}
                   :visualization {:type "table"}})))

  (testing "having works with time_series visualization"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}]
                 :having {:ref "A" :op ">" :value 10}
                 :visualization {:type "time_series"}})))

  (testing "having supports AND composition"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}
                                {:id "B" :field "duration_ms" :function "avg"}]
                 :group_by ["service"]
                 :having {:and [{:ref "A" :op ">" :value 10}
                                {:ref "B" :op "<" :value 500}]}
                 :visualization {:type "table"}})))

  (testing "having supports OR composition"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}
                                {:id "B" :field "duration_ms" :function "avg"}]
                 :group_by ["service"]
                 :having {:or [{:ref "A" :op ">" :value 100}
                               {:ref "B" :op ">=" :value 1000}]}
                 :visualization {:type "table"}})))

  (testing "having supports nested AND/OR composition"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:id "A" :field "*" :function "count"}
                                {:id "B" :field "duration_ms" :function "avg"}]
                 :group_by ["service"]
                 :having {:or [{:and [{:ref "A" :op ">" :value 10}
                                      {:ref "B" :op "<" :value 500}]}
                               {:ref "A" :op ">" :value 1000}]}
                 :visualization {:type "table"}})))

  (testing "composed having rejects invalid ref"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service"]
                   :having {:and [{:ref "A" :op ">" :value 10}
                                  {:ref "Z" :op "<" :value 500}]}
                   :visualization {:type "table"}})))

  (testing "composed having rejects non-numeric value"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :aggregations [{:id "A" :field "*" :function "count"}]
                   :group_by ["service"]
                   :having {:and [{:ref "A" :op ">" :value "bad"}]}
                   :visualization {:type "table"}}))))

;; ---------------------------------------------------------
;; Filter Value Coercion

(deftest normalize-filter-test
  (testing "coerces boolean string to boolean"
    (is (= true  (:value (:filter (query-util/normalize-filter test-events-schema
                                                               {:filter {:field "is_error" :op "=" :value "true"}})))))
    (is (= false (:value (:filter (query-util/normalize-filter test-events-schema
                                                               {:filter {:field "is_error" :op "=" :value "false"}}))))))

  (testing "boolean coercion is case-insensitive"
    (is (= true (:value (:filter (query-util/normalize-filter test-events-schema
                                                              {:filter {:field "is_error" :op "=" :value "True"}}))))))

  (testing "leaves actual booleans unchanged"
    (is (= true (:value (:filter (query-util/normalize-filter test-events-schema
                                                              {:filter {:field "is_error" :op "=" :value true}}))))))

  (testing "coerces integer string to long"
    (is (= 200 (:value (:filter (query-util/normalize-filter test-events-schema
                                                             {:filter {:field "status" :op "=" :value "200"}}))))))

  (testing "coerces float string to double"
    (is (= 3.14 (:value (:filter (query-util/normalize-filter test-events-schema
                                                              {:filter {:field "duration" :op ">" :value "3.14"}}))))))

  (testing "leaves strings unchanged for string fields"
    (is (= "api" (:value (:filter (query-util/normalize-filter test-events-schema
                                                               {:filter {:field "service" :op "=" :value "api"}}))))))

  (testing "leaves unknown fields unchanged"
    (is (= "anything" (:value (:filter (query-util/normalize-filter test-events-schema
                                                                    {:filter {:field "unknown" :op "=" :value "anything"}}))))))

  (testing "coerces values in compound AND filters"
    (let [result (query-util/normalize-filter test-events-schema
                                              {:filter {:and [{:field "is_error" :op "=" :value "true"}
                                                              {:field "status" :op ">=" :value "500"}]}})]
      (is (= true (get-in result [:filter :and 0 :value])))
      (is (= 500  (get-in result [:filter :and 1 :value])))))

  (testing "coerces values in compound OR filters"
    (let [result (query-util/normalize-filter test-events-schema
                                              {:filter {:or [{:field "is_error" :op "=" :value "false"}
                                                             {:field "duration" :op ">" :value "100.5"}]}})]
      (is (= false (get-in result [:filter :or 0 :value])))
      (is (= 100.5 (get-in result [:filter :or 1 :value])))))

  (testing "returns query unchanged when no filter present"
    (let [query {:time_range {:start 0 :end 1}}]
      (is (= query (query-util/normalize-filter test-events-schema query))))))
