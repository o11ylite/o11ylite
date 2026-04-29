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

(defn valid?
  [data]
  (nil? (schema/validate schema/metrics-query data)))

(defn invalid?
  [data]
  (some? (schema/validate schema/metrics-query data)))

(defn- query-base
  "Helper for building query base."
  []
  {:time_range {:start 1702000000000 :end 1702003600000}
   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})

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
                            :filter {:field "attr.status_code" :op "starts-with" :value "5"}}]})))

  (testing "per-metric filter with and/or"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A"
                            :name "http.server.errors"
                            :agg "sum"
                            :filter {:and [{:field "attr.status_code" :op "starts-with" :value "5"}
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
;; Visualization Validation

(deftest visualization-validation-test
  (testing "visualization is optional (defaults to time_series for metrics)"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]})))

  (testing "render_as enum accepts line, stacked_area, and bar"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                 :visualization {:type "time_series" :render_as "line"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                 :visualization {:type "time_series" :render_as "stacked_area"}}))
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                 :visualization {:type "time_series" :render_as "bar"}})))

  (testing "render_as rejects unknown values"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}]
                   :visualization {:type "time_series" :render_as "pie"}}))))

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
                            :filter {:field "attr.status_code" :op "starts-with" :value "5"}}
                           {:id "B"
                            :name "http.server.requests"
                            :agg "sum"}]
                 :group_by ["attr.service"]})))

  (testing "invalid numeric operators (metrics attributes are strings)"
    (doseq [op [">" "<" ">=" "<="]]
      (is (invalid? (assoc (query-base) :filter {:field "attr.env" :op op :value "prod"})))))

  (testing "valid starts-with operator"
    (is (valid? (assoc (query-base) :filter {:field "attr.env" :op "starts-with" :value "prod"}))))

  (testing "histogram metric query"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A"
                            :name "http.server.duration"
                            :agg "avg"}]
                 :group_by ["attr.service" "attr.method"]}))))

;; ---------------------------------------------------------
;; Having Validation

(deftest having-validation-test
  (testing "having is optional"
    (is (valid? (query-base))))

  (testing "valid having with matching metric ID"
    (is (valid? (assoc (query-base) :having {:ref "A" :op ">" :value 80}))))

  (testing "having ref must match a metric ID"
    (is (invalid? (assoc (query-base) :having {:ref "B" :op ">" :value 80}))))

  (testing "having with valid metric ID from multi-metric query"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "cpu.utilization" :agg "avg"}
                           {:id "B" :name "memory.usage" :agg "avg"}]
                 :having {:ref "B" :op "<" :value 50}})))

  (testing "having supports all numeric operators"
    (doseq [op [">" "<" ">=" "<=" "=" "!="]]
      (is (valid? (assoc (query-base) :having {:ref "A" :op op :value 80}))
          (str "having should accept operator: " op))))

  (testing "having value must be numeric"
    (is (invalid? (assoc (query-base) :having {:ref "A" :op ">" :value "not-a-number"}))))

  (testing "having ref must be valid format"
    (is (invalid? (assoc (query-base) :having {:ref "a" :op ">" :value 80})))
    (is (invalid? (assoc (query-base) :having {:ref "AB" :op ">" :value 80}))))

  (testing "having ref may target a declared formula id"
    (is (valid? {:time_range {:start 1702000000000 :end 1702003600000}
                 :metrics [{:id "A" :name "mem.free" :agg "avg"}
                           {:id "B" :name "mem.total" :agg "avg"}]
                 :formulas [{:id "F1" :expr "A / B * 100"}]
                 :having {:ref "F1" :op ">" :value 50}})))

  (testing "having ref must reference a *declared* formula id"
    (is (invalid? {:time_range {:start 1702000000000 :end 1702003600000}
                   :metrics [{:id "A" :name "mem.free" :agg "avg"}]
                   :having {:ref "F1" :op ">" :value 50}}))))

;; ---------------------------------------------------------
;; Formulas Validation

(deftest formulas-validation-test
  (testing "formulas optional"
    (is (valid? (query-base))))

  (testing "valid formula"
    (is (valid? (assoc (query-base)
                       :metrics [{:id "A" :name "mem.free" :agg "avg"}
                                 {:id "B" :name "mem.total" :agg "avg"}]
                       :formulas [{:id "F1"
                                   :expr "A / B * 100"
                                   :name "free mem %"
                                   :unit "%"}]))))

  (testing "formula id must be F1-F9"
    (is (invalid? (assoc (query-base)
                         :formulas [{:id "X" :expr "A"}])))
    (is (invalid? (assoc (query-base)
                         :formulas [{:id "F0" :expr "A"}]))))

  (testing "formula ids must be unique"
    (is (invalid? (assoc (query-base)
                         :metrics [{:id "A" :name "x" :agg "avg"}
                                   {:id "B" :name "y" :agg "avg"}]
                         :formulas [{:id "F1" :expr "A"}
                                    {:id "F1" :expr "B"}]))))

  (testing "formula expr must parse"
    (is (invalid? (assoc (query-base)
                         :formulas [{:id "F1" :expr "A +"}]))))

  (testing "formula refs must reference declared metrics"
    (is (invalid? (assoc (query-base)
                         :metrics [{:id "A" :name "x" :agg "avg"}]
                         :formulas [{:id "F1" :expr "A / B"}]))))

  (testing "empty expr rejected"
    (is (invalid? (assoc (query-base)
                         :formulas [{:id "F1" :expr ""}])))
    ;; whitespace-only slips past schema :min 1 and is caught by formula/parse
    (is (invalid? (assoc (query-base)
                         :formulas [{:id "F1" :expr "   "}]))))

  (testing "formula must reference at least one metric"
    (is (invalid? (assoc (query-base)
                         :formulas [{:id "F1" :expr "1 + 2"}])))))
