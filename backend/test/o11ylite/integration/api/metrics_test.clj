;; ---------------------------------------------------------
;; o11ylite.integration.api.metrics-test
;;
;; Integration tests for metrics metadata API endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.api.metrics-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.store.metrics.metadata :as metadata]
    [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- sqlite
  []
  (:db/sqlite h/*system*))

;; ---------------------------------------------------------
;; GET /api/metrics - List Metrics

(deftest list-metrics-empty-test
  (testing "GET /api/metrics returns empty array when no metrics exist"
    (let [response (h/get-json "/api/metrics")]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= [] (:body response))))))

(deftest list-metrics-returns-summary-test
  (testing "GET /api/metrics returns lightweight summary of all metrics"
    ;; Setup: insert some metrics
    (metadata/upsert-metrics! (sqlite)
                              {"cpu.utilization"
                               {:metric_type :gauge
                                :unit "%"
                                :description "CPU usage percentage"
                                :attributes #{"attr.host.name" "attr.cpu.core"}}
                               "http.server.duration"
                               {:metric_type :histogram
                                :unit "ms"
                                :description "HTTP server request duration"
                                :attributes #{"attr.http.method" "attr.http.route"}
                                :hist_boundaries [0.005 0.01 0.025 0.05 0.1]}
                               "http.requests.total"
                               {:metric_type :sum
                                :unit "1"
                                :description "Total HTTP requests"}})

    (let [response (h/get-json "/api/metrics")
          metrics (:body response)]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= 3 (count metrics)))
      ;; Sorted by name
      (is (= ["cpu.utilization" "http.requests.total" "http.server.duration"]
             (mapv :name metrics)))
      ;; Only lightweight fields returned
      (let [cpu (first metrics)]
        (is (= "cpu.utilization" (:name cpu)))
        (is (= "gauge" (:metric_type cpu)))
        (is (= "%" (:unit cpu)))
        ;; No heavy fields
        (is (nil? (:description cpu)))
        (is (nil? (:attributes cpu)))
        (is (nil? (:hist_boundaries cpu)))))))

;; ---------------------------------------------------------
;; GET /api/metrics/:name - Get Metric Detail

(deftest get-metric-not-found-test
  (testing "GET /api/metrics/:name returns 404 for unknown metric"
    (let [response (h/get-json "/api/metrics/nonexistent.metric")]
      (is (= 404 (h/status response)))
      (is (h/json-response? response))
      (is (= "metric_not_found" (get-in response [:body :error])))
      (is (= "nonexistent.metric" (get-in response [:body :name]))))))

(deftest get-metric-returns-full-metadata-test
  (testing "GET /api/metrics/:name returns full metadata for known metric"
    ;; Setup: insert a metric with all fields
    (metadata/upsert-metrics! (sqlite)
                              {"http.server.duration"
                               {:metric_type :histogram
                                :temporality :delta
                                :unit "ms"
                                :description "HTTP server request duration"
                                :attributes #{"attr.http.method" "attr.http.route" "attr.http.status_code"}
                                :hist_boundaries [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0]}})

    (let [response (h/get-json "/api/metrics/http.server.duration")
          metric (:body response)]
      (is (= 200 (h/status response)))
      (is (h/json-response? response))
      (is (= "http.server.duration" (:name metric)))
      (is (= "HTTP server request duration" (:description metric)))
      (is (= "ms" (:unit metric)))
      (is (= "histogram" (:metric_type metric)))
      (is (= "delta" (:temporality metric)))
      ;; Attributes returned as sorted array
      (is (= ["attr.http.method" "attr.http.route" "attr.http.status_code"] (:attributes metric)))
      ;; Histogram boundaries preserved
      (is (= [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0] (:hist_boundaries metric))))))

(deftest get-metric-gauge-test
  (testing "GET /api/metrics/:name returns gauge metric without histogram fields"
    (metadata/upsert-metrics! (sqlite)
                              {"cpu.utilization"
                               {:metric_type :gauge
                                :unit "%"
                                :description "CPU usage"
                                :attributes #{"attr.host.name"}}})

    (let [response (h/get-json "/api/metrics/cpu.utilization")
          metric (:body response)]
      (is (= 200 (h/status response)))
      (is (= "gauge" (:metric_type metric)))
      (is (nil? (:hist_boundaries metric))))))
