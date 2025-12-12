;; ---------------------------------------------------------
;; o11ylite.ducklake.events.query-schema-test
;;
;; Unit tests for events query schema validation.
;; ---------------------------------------------------------

(ns o11ylite.ducklake.events.query-schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [o11ylite.ducklake.events.query-schema :as schema]))

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
                 :visualization {:type "table" :limit 100}}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :visualization {:type "table" :limit 501}})))

  (testing "time_series visualization"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "time_series"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "time_series" :bucket_ms 60000}})))

  (testing "heatmap visualization requires exactly one group_by"
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

  (testing "trace visualization"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :visualization {:type "trace"}}))))

;; ---------------------------------------------------------
;; Filter Validation

(deftest filter-validation-test
  (testing "simple filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "service" :op "=" :value "api"}
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
;; Aggregation Validation

(deftest aggregation-validation-test
  (testing "valid aggregation"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "*" :function "count"}]
                 :visualization {:type "time_series"}})))

  (testing "aggregation with alias"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :aggregations [{:field "duration_ms" :function "p99" :alias "latency_p99"}]
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
