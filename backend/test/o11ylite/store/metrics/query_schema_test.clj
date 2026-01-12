;; ---------------------------------------------------------
;; o11ylite.store.metrics.query-schema-test
;;
;; Unit tests for metrics query schema validation.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query-schema-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [o11ylite.store.metrics.query-schema :as schema]))

;; ---------------------------------------------------------
;; Helper

(defn valid? [data]
  (nil? (schema/validate schema/metrics-query data)))

(defn invalid? [data]
  (some? (schema/validate schema/metrics-query data)))

;; ---------------------------------------------------------
;; Time Range Validation

(deftest time-range-validation-test
  (testing "time_range is required"
    (is (invalid? {:metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "time_range.start is required"
    (is (invalid? {:time_range {:end 1702003600000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "time_range.end is required"
    (is (invalid? {:time_range {:start 1702000000000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "valid time_range"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))))

;; ---------------------------------------------------------
;; Metrics Validation

(deftest metrics-validation-test
  (testing "metrics is required"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}})))

  (testing "metrics cannot be empty"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics []})))

  (testing "single metric"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "multiple metrics"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "http.server.errors" :agg "sum"}
                           {:id "B" :name "http.server.requests" :agg "sum"}]}))))

;; ---------------------------------------------------------
;; Metric ID Validation

(deftest metric-id-validation-test
  (testing "metric id must be single uppercase letter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "Z" :name "cpu.utilization" :agg "avg"}]})))

  (testing "lowercase not allowed"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "a" :name "cpu.utilization" :agg "avg"}]})))

  (testing "numbers not allowed"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A1" :name "cpu.utilization" :agg "avg"}]})))

  (testing "multiple letters not allowed"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "AB" :name "cpu.utilization" :agg "avg"}]})))

  (testing "duplicate IDs not allowed"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}
                             {:id "A" :name "memory.usage" :agg "avg"}]})))

  (testing "different IDs allowed"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}
                           {:id "B" :name "memory.usage" :agg "avg"}]}))))

;; ---------------------------------------------------------
;; Metric Name Validation

(deftest metric-name-validation-test
  (testing "valid metric names"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "http.server.requests" :agg "sum"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "my_custom_metric" :agg "sum"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "metric-with-dashes" :agg "sum"}]})))

  (testing "metric name must start with letter"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "123metric" :agg "avg"}]}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "_underscore_first" :agg "avg"}]}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name ".dot.first" :agg "avg"}]})))

  (testing "rejects SQL injection attempts"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "cpu; DROP TABLE metrics;" :agg "avg"}]}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "metric\"injection" :agg "avg"}]}))))

;; ---------------------------------------------------------
;; Aggregation Validation

(deftest aggregation-validation-test
  (testing "valid aggregations"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "sum"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "min"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "max"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "last"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "http.requests" :agg "rate"}]}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "http.duration" :agg "count"}]})))

  (testing "invalid aggregation"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "median"}]}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "p99"}]}))))

;; ---------------------------------------------------------
;; Filter Validation

(deftest filter-validation-test
  (testing "simple filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:field "attr.env" :op "=" :value "prod"}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "and filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:and [{:field "attr.env" :op "=" :value "prod"}
                                {:field "attr.region" :op "=" :value "us-east"}]}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "or filter"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :filter {:or [{:field "attr.env" :op "=" :value "prod"}
                               {:field "attr.env" :op "=" :value "staging"}]}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "invalid filter op"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :filter {:field "attr.env" :op "like" :value "prod%"}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "per-metric filter simple"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A"
                            :name "http.server.errors"
                            :agg "sum"
                            :filter {:field "attr.status_code" :op ">=" :value "500"}}]})))

  (testing "per-metric filter with and/or"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A"
                            :name "http.server.errors"
                            :agg "sum"
                            :filter {:and [{:field "attr.status_code" :op ">=" :value "500"}
                                           {:field "attr.method" :op "=" :value "POST"}]}}]}))))

;; ---------------------------------------------------------
;; Group By Validation

(deftest group-by-validation-test
  (testing "valid group_by"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                 :group_by ["attr.host.name"]})))

  (testing "multiple group_by fields"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "http.requests" :agg "sum"}]
                 :group_by ["attr.service" "attr.method"]})))

  (testing "rejects SQL injection in group_by"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                   :group_by ["attr.host; DROP TABLE metrics;"]}))))

;; ---------------------------------------------------------
;; Bucket MS Validation

(deftest bucket-ms-validation-test
  (testing "bucket_ms is optional"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "bucket_ms must be positive"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :bucket_ms 60000
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :bucket_ms 0
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :bucket_ms -1000
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]}))))

;; ---------------------------------------------------------
;; Full Query Examples

(deftest full-query-examples-test
  (testing "single metric query"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A"
                            :name "cpu.utilization"
                            :agg "avg"}]
                 :group_by ["attr.host.name"]})))

  (testing "multi-metric query for error rate"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :bucket_ms 60000
                 :filter {:field "attr.env" :op "=" :value "prod"}
                 :metrics [{:id "A"
                            :name "http.server.errors"
                            :agg "sum"
                            :filter {:field "attr.status_code" :op ">=" :value "500"}}
                           {:id "B"
                            :name "http.server.requests"
                            :agg "sum"}]
                 :group_by ["attr.service"]})))

  (testing "histogram metric query"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A"
                            :name "http.server.duration"
                            :agg "avg"}]
                 :group_by ["attr.service" "attr.method"]}))))
