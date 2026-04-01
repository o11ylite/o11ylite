;; ---------------------------------------------------------
;; o11ylite.integration.otel-http-test
;;
;; Integration tests for OTLP HTTP endpoints.
;; Tests both JSON and protobuf binary content types.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-http-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [next.jdbc :as jdbc]
    [o11ylite.test-helpers :as h]
    [o11ylite.test-helpers.http :as http]
    [o11ylite.test-helpers.otlp :as otlp])
  (:import
    [java.io ByteArrayOutputStream]
    [java.util.zip GZIPOutputStream]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Helpers

(defn- duckdb
  []
  (:db/duckdb h/*system*))

(defn- query-events-by-service
  "Query events from DuckLake by service name."
  [service-name]
  (jdbc/execute! (duckdb)
                 ["SELECT * FROM o11ylite.events WHERE service = ? ORDER BY name"
                  service-name]))

;; ---------------------------------------------------------
;; Tests

(deftest otlp-traces-json-test
  (testing "POST /v1/traces accepts JSON with empty resourceSpans"
    (let [response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/json"}
                               :body "{\"resourceSpans\":[]}"})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "content-type"])))))

  (testing "POST /v1/traces accepts JSON with valid trace data"
    (let [trace-json (h/->json
                       {:resourceSpans
                        [{:resource {:attributes [{:key "service.name"
                                                   :value {:stringValue "test-service"}}]}
                          :scopeSpans
                          [{:scope {:name "test-scope"}
                            :spans [{:traceId "0af7651916cd43dd8448eb211c80319c"
                                     :spanId "b7ad6b7169203331"
                                     :name "test-span"
                                     :kind 1
                                     :startTimeUnixNano 1234567890000000000
                                     :endTimeUnixNano 1234567891000000000
                                     :status {:code 1}}]}]}]})
          response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/json"}
                               :body trace-json})]
      (is (= 200 (:status response))))))

(deftest otlp-logs-json-test
  (testing "POST /v1/logs accepts JSON with empty resourceLogs"
    (let [response (http/post "/v1/logs"
                              {:headers {"Content-Type" "application/json"}
                               :body "{\"resourceLogs\":[]}"})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "content-type"])))))

  (testing "POST /v1/logs accepts JSON with valid log data"
    (let [log-json (h/->json
                     {:resourceLogs
                      [{:resource {:attributes [{:key "service.name"
                                                 :value {:stringValue "test-service"}}]}
                        :scopeLogs
                        [{:scope {:name "test-scope"}
                          :logRecords [{:timeUnixNano 1234567890000000000
                                        :severityText "INFO"
                                        :body {:stringValue "Test log message"}}]}]}]})
          response (http/post "/v1/logs"
                              {:headers {"Content-Type" "application/json"}
                               :body log-json})]
      (is (= 200 (:status response))))))

(deftest otlp-content-type-handling-test
  (testing "Responds with JSON by default"
    (let [response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/json"}
                               :body "{\"resourceSpans\":[]}"})]
      (is (= "application/json" (get-in response [:headers "content-type"])))))

  (testing "Responds with protobuf when Accept header requests it"
    (let [response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/json"
                                         "Accept" "application/x-protobuf"}
                               :body "{\"resourceSpans\":[]}"})]
      (is (= "application/x-protobuf" (get-in response [:headers "content-type"]))))))

;; ---------------------------------------------------------
;; Protobuf Binary Tests

(deftest otlp-traces-protobuf-test
  (testing "POST /v1/traces accepts protobuf binary with valid trace data"
    (let [service-name "http-proto-trace-test"
          proto-request (otlp/build-trace-request
                          {:service-name service-name
                           :tracer-name "test-tracer"
                           :spans [{:trace-id "0af7651916cd43dd8448eb211c80319c"
                                    :span-id "b7ad6b7169203331"
                                    :name "http-proto-span"
                                    :kind :server
                                    :attributes {"http.method" "GET"}}]})
          response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/x-protobuf"}
                               :body (.toByteArray proto-request)})]
      (is (= 200 (:status response)))
      ;; Verify data was persisted
      (let [rows (query-events-by-service service-name)]
        (is (= 1 (count rows)))
        (let [row (first rows)]
          (is (= service-name (:service row)))
          (is (= "http-proto-span" (:name row)))
          (is (= "span" (:meta.signal_type row)))
          (is (= "GET" (:attr.http.method row))))))))

(deftest otlp-logs-protobuf-test
  (testing "POST /v1/logs accepts protobuf binary with valid log data"
    (let [service-name "http-proto-log-test"
          proto-request (otlp/build-logs-request
                          {:service-name service-name
                           :logger-name "test-logger"
                           :logs [{:body "Test log via protobuf"
                                   :severity :info
                                   :severity-text "INFO"
                                   :attributes {"user.id" "proto-user-123"}}]})
          response (http/post "/v1/logs"
                              {:headers {"Content-Type" "application/x-protobuf"}
                               :body (.toByteArray proto-request)})]
      (is (= 200 (:status response)))
      ;; Verify data was persisted
      (let [rows (query-events-by-service service-name)]
        (is (= 1 (count rows)))
        (let [row (first rows)]
          (is (= service-name (:service row)))
          (is (= "log" (:meta.signal_type row)))
          (is (= "Test log via protobuf" (:log.body row)))
          (is (= "proto-user-123" (:attr.user.id row))))))))

;; ---------------------------------------------------------
;; Metric Tests - JSON

(deftest otlp-metrics-json-test
  (testing "POST /v1/metrics accepts JSON with empty resourceMetrics"
    (let [response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/json"}
                               :body "{\"resourceMetrics\":[]}"})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "content-type"])))))

  (testing "POST /v1/metrics accepts JSON with valid metric data"
    (let [metric-json (h/->json
                        {:resourceMetrics
                         [{:resource {:attributes [{:key "service.name"
                                                    :value {:stringValue "test-service"}}]}
                           :scopeMetrics
                           [{:scope {:name "test-scope"}
                             :metrics [{:name "cpu.usage"
                                        :description "CPU usage"
                                        :unit "%"
                                        :gauge {:dataPoints [{:timeUnixNano 1234567890000000000
                                                              :asDouble 42.5}]}}]}]}]})
          response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/json"}
                               :body metric-json})]
      (is (= 200 (:status response))))))

;; ---------------------------------------------------------
;; Metric Tests - Protobuf Binary

(defn- query-metrics-by-name
  "Query metrics from DuckLake by metric name."
  [metric-name]
  (jdbc/execute! (duckdb)
                 ["SELECT * FROM o11ylite.metrics WHERE name = ?"
                  metric-name]))

(deftest otlp-metrics-protobuf-test
  (testing "POST /v1/metrics accepts protobuf binary with valid metric data"
    (let [service-name "http-proto-metric-test"
          metric-name "http.proto.cpu.usage"
          proto-request (otlp/build-metrics-request
                          {:service-name service-name
                           :meter-name "test-meter"
                           :metrics [(otlp/build-gauge-metric
                                       {:name metric-name
                                        :description "CPU usage via HTTP protobuf"
                                        :unit "%"
                                        :data-points [{:value 65.5
                                                       :attributes {"host.name" "http-test-server"}}]})]})
          response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/x-protobuf"}
                               :body (.toByteArray proto-request)})]
      (is (= 200 (:status response)))
      ;; Wait for batcher to flush (test config uses 100ms interval)
      (Thread/sleep 200)
      ;; Verify data was persisted
      (let [rows (query-metrics-by-name metric-name)]
        (is (= 1 (count rows)))
        (let [row (first rows)]
          (is (= metric-name (:name row)))
          (is (= service-name (:service row)))
          (is (= 65.5 (:value row)))
          (is (= "http-test-server" (:attr.host.name row))))))))

(deftest otlp-metrics-content-type-handling-test
  (testing "Responds with JSON by default for metrics"
    (let [response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/json"}
                               :body "{\"resourceMetrics\":[]}"})]
      (is (= "application/json" (get-in response [:headers "content-type"])))))

  (testing "Responds with protobuf when Accept header requests it for metrics"
    (let [response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/json"
                                         "Accept" "application/x-protobuf"}
                               :body "{\"resourceMetrics\":[]}"})]
      (is (= "application/x-protobuf" (get-in response [:headers "content-type"]))))))

;; ---------------------------------------------------------
;; Gzip Compression Tests
;;
;; The OTLP spec requires servers to support gzip-compressed request bodies.
;; The OTel Collector's otlphttp exporter sends gzip by default.

(defn- gzip-bytes
  "Gzip-compress a byte array."
  ^bytes [^bytes data]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gzip (GZIPOutputStream. baos)]
      (.write gzip data))
    (.toByteArray baos)))

(deftest otlp-gzip-protobuf-test
  (testing "POST /v1/traces accepts gzip-compressed protobuf"
    (let [service-name "http-gzip-trace-test"
          proto-request (otlp/build-trace-request
                          {:service-name service-name
                           :tracer-name "test-tracer"
                           :spans [{:trace-id "0af7651916cd43dd8448eb211c80319c"
                                    :span-id "b7ad6b7169203331"
                                    :name "gzip-proto-span"
                                    :kind :server
                                    :attributes {"http.method" "POST"}}]})
          response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/x-protobuf"
                                         "Content-Encoding" "gzip"}
                               :body (gzip-bytes (.toByteArray proto-request))})]
      (is (= 200 (:status response)))
      (let [rows (query-events-by-service service-name)]
        (is (= 1 (count rows)))
        (is (= "gzip-proto-span" (:name (first rows)))))))

  (testing "POST /v1/logs accepts gzip-compressed protobuf"
    (let [service-name "http-gzip-log-test"
          proto-request (otlp/build-logs-request
                          {:service-name service-name
                           :logger-name "test-logger"
                           :logs [{:body "Gzip log via protobuf"
                                   :severity :info
                                   :severity-text "INFO"
                                   :attributes {"env" "test"}}]})
          response (http/post "/v1/logs"
                              {:headers {"Content-Type" "application/x-protobuf"
                                         "Content-Encoding" "gzip"}
                               :body (gzip-bytes (.toByteArray proto-request))})]
      (is (= 200 (:status response)))
      ;; Query without ORDER BY name — DuckLake has a known issue where
      ;; ORDER BY on a column with NULLs can corrupt other projected columns.
      (let [rows (jdbc/execute! (duckdb)
                                ["SELECT * FROM o11ylite.events WHERE service = ?"
                                 service-name])]
        (is (= 1 (count rows)))
        (is (= "Gzip log via protobuf" (:log.body (first rows)))))))

  (testing "POST /v1/metrics accepts gzip-compressed protobuf"
    (let [service-name "http-gzip-metric-test"
          metric-name "http.gzip.cpu.usage"
          proto-request (otlp/build-metrics-request
                          {:service-name service-name
                           :meter-name "test-meter"
                           :metrics [(otlp/build-gauge-metric
                                       {:name metric-name
                                        :description "CPU usage via gzip protobuf"
                                        :unit "%"
                                        :data-points [{:value 77.3
                                                       :attributes {"host.name" "gzip-test-server"}}]})]})
          response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/x-protobuf"
                                         "Content-Encoding" "gzip"}
                               :body (gzip-bytes (.toByteArray proto-request))})]
      (is (= 200 (:status response)))
      (Thread/sleep 200)
      (let [rows (query-metrics-by-name metric-name)]
        (is (= 1 (count rows)))
        (is (= 77.3 (:value (first rows))))))))

(deftest otlp-gzip-json-test
  (testing "POST /v1/traces accepts gzip-compressed JSON"
    (let [trace-json "{\"resourceSpans\":[]}"
          response (http/post "/v1/traces"
                              {:headers {"Content-Type" "application/json"
                                         "Content-Encoding" "gzip"}
                               :body (gzip-bytes (.getBytes trace-json "UTF-8"))})]
      (is (= 200 (:status response)))))

  (testing "POST /v1/metrics accepts gzip-compressed JSON"
    (let [metric-json "{\"resourceMetrics\":[]}"
          response (http/post "/v1/metrics"
                              {:headers {"Content-Type" "application/json"
                                         "Content-Encoding" "gzip"}
                               :body (gzip-bytes (.getBytes metric-json "UTF-8"))})]
      (is (= 200 (:status response))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[kaocha.repl :as k])
  (k/run #'otlp-traces-json-test)
  (k/run #'otlp-logs-json-test)
  (k/run #'otlp-content-type-handling-test)
  (k/run #'otlp-metrics-json-test)
  (k/run #'otlp-metrics-protobuf-test)
  (k/run #'otlp-metrics-content-type-handling-test)
  (k/run #'otlp-gzip-protobuf-test)
  (k/run #'otlp-gzip-json-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
