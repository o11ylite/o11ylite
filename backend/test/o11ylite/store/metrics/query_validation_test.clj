;; ---------------------------------------------------------
;; o11ylite.store.metrics.query-validation-test
;;
;; Unit tests for metadata-aware metrics query validation.
;; Tests aggregation compatibility against metric types.
;; Uses mocking instead of real database for fast execution.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.query-validation-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.store.metrics.query-validation :as validation]))

;; ---------------------------------------------------------
;; Mock Helpers

(defn- mock-metadata
  "Create a mock metadata lookup function.
   metrics-map is {metric-name {:metric_type :gauge|:sum|:histogram ...}}"
  [metrics-map]
  (fn [_sqlite metric-name]
    (get metrics-map metric-name)))

(defn- validate-with-mock
  "Validate query with mocked metadata.
   metrics-map defines known metrics, query is the full query map.
   `duckdb` is passed as nil — these tests only exercise the metadata
   branch and never reference attribute fields, so the field-existence
   check is short-circuited by the empty-references guard."
  [metrics-map query]
  (with-redefs [metadata/get-metric (mock-metadata metrics-map)]
    (validation/validate-with-metadata nil nil query)))

(defn- validate-single
  "Validate a single metric query with mocked metadata."
  [metric-type metric-name agg]
  (validate-with-mock
    {metric-name {:metric_type metric-type}}
    {:time_range {:start 1702000000000 :end 1702003600000}
     :metrics [{:id "A" :name metric-name :agg agg}]}))

;; ---------------------------------------------------------
;; Gauge Metrics

(deftest gauge-valid-aggregations-test
  (testing "gauge allows sum"
    (is (nil? (validate-single :gauge "test.gauge" "sum"))))

  (testing "gauge allows avg"
    (is (nil? (validate-single :gauge "test.gauge" "avg"))))

  (testing "gauge allows min"
    (is (nil? (validate-single :gauge "test.gauge" "min"))))

  (testing "gauge allows max"
    (is (nil? (validate-single :gauge "test.gauge" "max"))))

  (testing "gauge allows last"
    (is (nil? (validate-single :gauge "test.gauge" "last")))))

(deftest gauge-invalid-aggregations-test
  (testing "gauge does not allow rate"
    (let [result (validate-single :gauge "test.gauge" "rate")]
      (is (some? result))
      (is (string? (:error result)))
      (is (re-find #"not valid for gauge" (:error result)))))

  (testing "gauge does not allow count"
    (let [result (validate-single :gauge "test.gauge" "count")]
      (is (some? result))
      (is (re-find #"not valid for gauge" (:error result))))))

;; ---------------------------------------------------------
;; Sum (Counter) Metrics

(deftest sum-valid-aggregations-test
  (testing "sum allows sum"
    (is (nil? (validate-single :sum "test.sum" "sum"))))

  (testing "sum allows rate"
    (is (nil? (validate-single :sum "test.sum" "rate")))))

(deftest sum-invalid-aggregations-test
  (testing "sum does not allow avg"
    (let [result (validate-single :sum "test.sum" "avg")]
      (is (some? result))
      (is (re-find #"not valid for sum" (:error result)))))

  (testing "sum does not allow min"
    (let [result (validate-single :sum "test.sum" "min")]
      (is (some? result))
      (is (re-find #"not valid for sum" (:error result)))))

  (testing "sum does not allow max"
    (let [result (validate-single :sum "test.sum" "max")]
      (is (some? result))
      (is (re-find #"not valid for sum" (:error result)))))

  (testing "sum does not allow last"
    (let [result (validate-single :sum "test.sum" "last")]
      (is (some? result))
      (is (re-find #"not valid for sum" (:error result)))))

  (testing "sum does not allow count"
    (let [result (validate-single :sum "test.sum" "count")]
      (is (some? result))
      (is (re-find #"not valid for sum" (:error result))))))

;; ---------------------------------------------------------
;; Histogram Metrics

(deftest histogram-valid-aggregations-test
  (testing "histogram allows count"
    (is (nil? (validate-single :histogram "test.histogram" "count"))))

  (testing "histogram allows sum"
    (is (nil? (validate-single :histogram "test.histogram" "sum"))))

  (testing "histogram allows avg"
    (is (nil? (validate-single :histogram "test.histogram" "avg"))))

  (testing "histogram allows min"
    (is (nil? (validate-single :histogram "test.histogram" "min"))))

  (testing "histogram allows max"
    (is (nil? (validate-single :histogram "test.histogram" "max")))))

(deftest histogram-invalid-aggregations-test
  (testing "histogram does not allow rate"
    (let [result (validate-single :histogram "test.histogram" "rate")]
      (is (some? result))
      (is (re-find #"not valid for histogram" (:error result)))))

  (testing "histogram does not allow last"
    (let [result (validate-single :histogram "test.histogram" "last")]
      (is (some? result))
      (is (re-find #"not valid for histogram" (:error result))))))

;; ---------------------------------------------------------
;; Unknown Metrics

(deftest unknown-metric-skipped-test
  (testing "unknown metric is skipped (returns nil)"
    ;; Empty metadata map = all metrics unknown
    (let [result (validate-with-mock
                   {}
                   {:time_range {:start 1702000000000 :end 1702003600000}
                    :metrics [{:id "A" :name "unknown.metric" :agg "avg"}]})]
      (is (nil? result))))

  (testing "unknown metric allows any aggregation"
    (is (nil? (validate-with-mock {} {:time_range {:start 1 :end 2}
                                      :metrics [{:id "A" :name "unknown" :agg "rate"}]})))
    (is (nil? (validate-with-mock {} {:time_range {:start 1 :end 2}
                                      :metrics [{:id "A" :name "unknown" :agg "count"}]})))
    (is (nil? (validate-with-mock {} {:time_range {:start 1 :end 2}
                                      :metrics [{:id "A" :name "unknown" :agg "last"}]})))))

;; ---------------------------------------------------------
;; Multi-Metric Queries

(deftest multi-metric-validation-test
  (let [metrics-map {"multi.gauge" {:metric_type :gauge}
                     "multi.sum" {:metric_type :sum}}]

    (testing "all valid metrics pass"
      (let [result (validate-with-mock
                     metrics-map
                     {:time_range {:start 1702000000000 :end 1702003600000}
                      :metrics [{:id "A" :name "multi.gauge" :agg "avg"}
                                {:id "B" :name "multi.sum" :agg "sum"}]})]
        (is (nil? result))))

    (testing "first invalid metric fails"
      (let [result (validate-with-mock
                     metrics-map
                     {:time_range {:start 1702000000000 :end 1702003600000}
                      :metrics [{:id "A" :name "multi.gauge" :agg "rate"}  ; invalid
                                {:id "B" :name "multi.sum" :agg "sum"}]})]
        (is (some? result))
        (is (re-find #"id: A" (:error result)))))

    (testing "second invalid metric fails"
      (let [result (validate-with-mock
                     metrics-map
                     {:time_range {:start 1702000000000 :end 1702003600000}
                      :metrics [{:id "A" :name "multi.gauge" :agg "avg"}
                                {:id "B" :name "multi.sum" :agg "avg"}]})]  ; invalid
        (is (some? result))
        (is (re-find #"id: B" (:error result)))))))

;; ---------------------------------------------------------
;; Error Message Format

(deftest error-message-format-test
  (testing "error message includes metric name, id, aggregation, type, and allowed values"
    (let [result (validate-single :sum "error.format.test" "avg")
          error (:error result)]
      (is (re-find #"error\.format\.test" error))
      (is (re-find #"id: A" error))
      (is (re-find #"'avg'" error))
      (is (re-find #"sum metrics" error))
      (is (re-find #"rate" error))   ; allowed value
      (is (re-find #"sum" error))))) ; allowed value

;; ---------------------------------------------------------
;; Per-Metric Filter Diagnostics

(defn- filter-diagnostics
  "Run filter-diagnostics with mocked metadata and the given returned series."
  [metrics-map query series]
  (with-redefs [metadata/get-metric (mock-metadata metrics-map)]
    (validation/filter-diagnostics nil query series)))

(defn- metric-with-filter
  [filter]
  {:id "A" :name "test.metric" :agg "avg" :filter filter})

(deftest filter-diagnostics-test
  (let [attrs #{"attr.host.name"}
        metrics-map {"test.metric" {:metric_type :gauge :attributes attrs}}
        missing-attr-query {:filter {:field "attr.service.name" :op "=" :value "x"}
                            :metrics [(metric-with-filter nil)]}]
    (testing "missing attr.* field with no returned series produces a diagnostic"
      (let [result (filter-diagnostics metrics-map missing-attr-query [])
            diagnostic (first result)]
        (is (= 1 (count result)))
        (is (= "warning" (:severity diagnostic)))
        (is (= "metric_filter_not_applicable" (:code diagnostic)))
        (is (= "test.metric" (:metric diagnostic)))
        (is (= "A" (:id diagnostic)))
        (is (= ["attr.service.name"] (:fields diagnostic)))
        (is (str/includes? (:message diagnostic) "test.metric"))
        (is (str/includes? (:message diagnostic) "attr.service.name"))))

    (testing "no diagnostic when the metric returned data (metadata may be stale)"
      (is (empty? (filter-diagnostics metrics-map missing-attr-query [{:id "A"}]))))

    (testing "known attr.* field and non-attribute fields produce no diagnostic"
      (is (empty? (filter-diagnostics
                    metrics-map
                    {:filter {:field "attr.host.name" :op "=" :value "x"}
                     :metrics [(metric-with-filter nil)]}
                    [])))
      (is (empty? (filter-diagnostics
                    metrics-map
                    {:filter {:field "service" :op "=" :value "x"}
                     :metrics [(metric-with-filter nil)]}
                    []))))

    (testing "metadata stored without the attr. prefix still matches"
      (is (empty? (filter-diagnostics
                    {"test.metric" {:metric_type :gauge :attributes #{"host.name"}}}
                    {:filter {:field "attr.host.name" :op "=" :value "x"}
                     :metrics [(metric-with-filter nil)]}
                    []))))

    (testing "AND needs every branch; OR needs only one"
      (is (= 1 (count (filter-diagnostics
                        metrics-map
                        {:filter {:and [{:field "attr.host.name" :op "=" :value "x"}
                                        {:field "attr.missing" :op "=" :value "y"}]}
                         :metrics [(metric-with-filter nil)]}
                        []))))
      (is (empty? (filter-diagnostics
                    metrics-map
                    {:filter {:or [{:field "attr.host.name" :op "=" :value "x"}
                                   {:field "attr.missing" :op "=" :value "y"}]}
                     :metrics [(metric-with-filter nil)]}
                    [])))
      (is (= 1 (count (filter-diagnostics
                        metrics-map
                        {:filter {:or [{:field "attr.missing" :op "=" :value "x"}
                                       {:field "attr.other.missing" :op "=" :value "y"}]}
                         :metrics [(metric-with-filter nil)]}
                        [])))))

    (testing "per-metric filters are checked too"
      (let [result (filter-diagnostics
                     metrics-map
                     {:metrics [(metric-with-filter
                                  {:field "attr.missing" :op "=" :value "x"})]}
                     [])]
        (is (= ["attr.missing"] (get-in result [0 :fields])))))

    (testing "unknown metrics have no metadata and produce no diagnostic"
      (let [result (with-redefs [metadata/get-metric (constantly nil)]
                     (validation/filter-diagnostics
                       nil
                       {:filter {:field "attr.anything" :op "=" :value "x"}
                        :metrics [(metric-with-filter nil)]}
                       []))]
        (is (empty? result))))))
