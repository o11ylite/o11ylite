;; ---------------------------------------------------------
;; o11ylite.integration.otel-grpc-test
;;
;; Integration tests for OpenTelemetry gRPC endpoints.
;; ---------------------------------------------------------

(ns o11ylite.integration.otel-grpc-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [o11ylite.test-helpers :as h]))

(use-fixtures :each h/with-system)

;; ---------------------------------------------------------
;; Tests

(deftest trace-export-single-span-test
  (testing "TraceService/Export accepts a single span"
    (let [response (h/export-traces!
                    {:service-name "test-service"
                     :tracer-name "test-tracer"
                     :spans [{:trace-id "0af7651916cd43dd8448eb211c80319c"
                              :span-id "b7ad6b7169203331"
                              :name "GET /api/users"
                              :kind :server
                              :start-time-ns 1000000000
                              :end-time-ns   1000100000
                              :attributes {"http.method" "GET"
                                           "http.status_code" 200}
                              :status :ok}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedSpans))))))

(deftest trace-export-multiple-spans-test
  (testing "TraceService/Export accepts multiple spans with parent-child relationship"
    (let [trace-id "0af7651916cd43dd8448eb211c80319c"
          parent-span-id "b7ad6b7169203331"
          child-span-id "00f067aa0ba902b7"
          response (h/export-traces!
                    {:service-name "test-service"
                     :tracer-name "test-tracer"
                     :spans [{:trace-id trace-id
                              :span-id parent-span-id
                              :name "parent-operation"
                              :kind :server
                              :start-time-ns 1000000000
                              :end-time-ns   1000200000
                              :status :ok}
                             {:trace-id trace-id
                              :span-id child-span-id
                              :parent-span-id parent-span-id
                              :name "child-operation"
                              :kind :internal
                              :start-time-ns 1000050000
                              :end-time-ns   1000150000
                              :status :ok}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedSpans))))))

(deftest trace-export-with-attributes-test
  (testing "TraceService/Export accepts spans with various attribute types"
    (let [response (h/export-traces!
                    {:service-name "test-service"
                     :tracer-name "test-tracer"
                     :spans [{:trace-id "0af7651916cd43dd8448eb211c80319c"
                              :span-id "b7ad6b7169203331"
                              :name "test-span"
                              :attributes {"string.attr" "value"
                                           "int.attr" 42
                                           "bool.attr" true
                                           "float.attr" 3.14}}]})]
      (is (some? response))
      (is (= 0 (-> response .getPartialSuccess .getRejectedSpans))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Run tests manually
  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.otel-grpc-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
