;; ---------------------------------------------------------
;; o11ylite.otel-grpc.event-test
;;
;; Unit tests for OTLP event transformation.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.event-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [o11ylite.otel-grpc.trace-events :as trace-events]
   [o11ylite.test-helpers.otlp :as otlp]))

;; ---------------------------------------------------------
;; Test Data Builders

(defn- build-sample-request
  "Build a sample trace request with resource, scope, and spans."
  []
  (otlp/build-trace-request
   {:service-name "test-service"
    :resource-attrs {"host.name" "localhost"}
    :tracer-name "test-tracer"
    :tracer-version "1.0.0"
    :scope-attrs {"scope.attr" "scope-value"}
    :spans [{:trace-id "0af7651916cd43dd8448eb211c80319c"
             :span-id "b7ad6b7169203331"
             :name "parent-span"
             :kind :server
             :start-time-ns 1000000000000000000
             :end-time-ns   1000000100000000000
             :attributes {"http.method" "GET"}
             :status :ok
             :events [{:name "event-1"
                       :time-ns 1000000050000000000
                       :attributes {"event.attr" "event-value"}}]}
            {:trace-id "0af7651916cd43dd8448eb211c80319c"
             :span-id "00f067aa0ba902b7"
             :parent-span-id "b7ad6b7169203331"
             :name "child-span"
             :kind :internal
             :start-time-ns 1000000020000000000
             :end-time-ns   1000000080000000000
             :attributes {"db.system" "postgresql"}
             :status :ok}]}))

(defn- build-request-without-service
  "Build a request without service.name - should be rejected."
  []
  (otlp/build-trace-request
   {:tracer-name "test-tracer"
    :spans [{:trace-id "abc123def456abc123def456abc12345"
             :span-id "def456abc1234567"
             :name "orphan-span"
             :kind :internal
             :start-time-ns 1000000000000000000
             :end-time-ns   1000000100000000000
             :status :ok}]}))

;; ---------------------------------------------------------
;; Tests

(deftest trace-request->events-basic-test
  (testing "Converts spans to events with correct structure"
    (let [events (trace-events/trace-request->events (build-sample-request))
          span-events (filter #(= :span (:meta.signal_type %)) events)
          span-event-events (filter #(= :span_event (:meta.signal_type %)) events)]
      (is (= 3 (count events)) "Should have 2 spans + 1 span event")
      (is (= 2 (count span-events)))
      (is (= 1 (count span-event-events))))))

(deftest trace-request->events-prefixes-attributes-test
  (testing "Merges and prefixes resource, scope, and span attributes"
    (let [events (trace-events/trace-request->events (build-sample-request))
          parent-span (first (filter #(= "parent-span" (:name %)) events))]
      ;; Resource attributes (prefixed)
      (is (= "test-service" (get parent-span "attr.service.name")))
      ;; Span attributes (prefixed)
      (is (= "GET" (get parent-span "attr.http.method"))))))

(deftest trace-request->events-span-fields-test
  (testing "Span events have correct span-specific fields"
    (let [events (trace-events/trace-request->events (build-sample-request))
          parent-span (first (filter #(= "parent-span" (:name %)) events))]
      (is (= "test-service" (:service parent-span)))
      (is (= "0af7651916cd43dd8448eb211c80319c" (:trace_id parent-span)))
      (is (= "b7ad6b7169203331" (:span_id parent-span)))
      (is (= "" (:parent_span_id parent-span))) ; Empty string for no parent (protobuf default)
      (is (= :server (:span.kind parent-span)))
      (is (= :ok (:span.status_code parent-span)))
      (is (= 100000000000 (:span.duration_ns parent-span)))
      (is (= "test-tracer" (:scope.name parent-span)))
      (is (= "1.0.0" (:scope.version parent-span))))))

(deftest trace-request->events-span-event-test
  (testing "Span events are converted with inherited context"
    (let [events (trace-events/trace-request->events (build-sample-request))
          span-event (first (filter #(= :span_event (:meta.signal_type %)) events))]
      (is (= "event-1" (:name span-event)))
      (is (= "test-service" (:service span-event)))
      (is (= "0af7651916cd43dd8448eb211c80319c" (:trace_id span-event)))
      (is (= "b7ad6b7169203331" (:span_id span-event)))
      ;; Should have merged and prefixed attributes from resource + span + event
      (is (= "test-service" (get span-event "attr.service.name")))
      (is (= "GET" (get span-event "attr.http.method")))
      (is (= "event-value" (get span-event "attr.event.attr"))))))

(deftest trace-request->events-rejects-without-service-test
  (testing "Rejects spans without service.name"
    (let [events (trace-events/trace-request->events (build-request-without-service))]
      (is (empty? events)))))

(deftest count-rejected-spans-test
  (testing "Counts rejected spans correctly"
    (is (= 0 (trace-events/count-rejected-spans (build-sample-request))))
    (is (= 1 (trace-events/count-rejected-spans (build-request-without-service))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.otel-grpc.event-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
