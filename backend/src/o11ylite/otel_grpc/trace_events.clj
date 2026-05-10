;; ---------------------------------------------------------
;; o11ylite.otel-grpc.trace-events
;;
;; Converts OTLP trace protobuf directly to unified events.
;; Single-pass conversion from Java protobuf to event maps.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.trace-events
  (:require
    [o11ylite.otel-grpc.proto :as proto])
  (:import
    [io.opentelemetry.proto.resource.v1 Resource]
    [io.opentelemetry.proto.trace.v1 Span Span$SpanKind Span$Event Status Status$StatusCode ResourceSpans ScopeSpans]
    [io.opentelemetry.proto.collector.trace.v1 ExportTraceServiceRequest ExportTraceServiceResponse ExportTracePartialSuccess]
    [java.time Instant]))

;; ---------------------------------------------------------
;; Trace-specific enum conversions

(defn- -span-kind->kw
  "Convert SpanKind enum to keyword."
  [^Span$SpanKind kind]
  (case (.getNumber kind)
    0 :unspecified
    1 :internal
    2 :server
    3 :client
    4 :producer
    5 :consumer
    :unknown))

(defn- -status-code->kw
  "Convert StatusCode enum to keyword."
  [^Status$StatusCode code]
  (case (.getNumber code)
    0 :unset
    1 :ok
    2 :error
    :unknown))

;; ---------------------------------------------------------
;; Span -> Events (direct from protobuf)

(defn- -span-event->event
  "Convert Span.Event protobuf directly to unified event.
   Attributes are prefixed with 'attr.' and merged into the event map."
  [^Span$Event span-event ^Span span resource-attrs scope-attrs scope-name scope-version service-name observed-time]
  (let [trace-id (proto/bytestring->hex (.getTraceId span))
        span-id (proto/bytestring->hex (.getSpanId span))
        event-attrs (proto/extract-attributes (.getAttributesList span-event))
        prefixed-attrs (proto/prefix-attributes resource-attrs scope-attrs event-attrs)]
    (merge
      {:service service-name
       :timestamp (proto/nanos->instant (.getTimeUnixNano span-event))

       :trace_id trace-id
       :span_id span-id

       :name (.getName span-event)

       ;; Instrumentation scope
       :scope.name scope-name
       :scope.version scope-version

       ;; Meta
       :meta.observed_time observed-time
       :meta.signal_type :span_event}
      prefixed-attrs)))

(defn- -span->event
  "Convert Span protobuf directly to unified event.
   Attributes are prefixed with 'attr.' and merged into the event map."
  [^Span span resource-attrs scope-attrs scope-name scope-version service-name observed-time]
  (let [start-nanos (.getStartTimeUnixNano span)
        end-nanos (.getEndTimeUnixNano span)
        ^Status status (.getStatus span)
        span-attrs (proto/extract-attributes (.getAttributesList span))
        prefixed-attrs (proto/prefix-attributes resource-attrs scope-attrs span-attrs)]
    (merge
      {:service service-name
       :timestamp (proto/nanos->instant start-nanos)

       :trace_id (proto/bytestring->hex (.getTraceId span))
       :span_id (proto/bytestring->hex (.getSpanId span))
       :parent_span_id (proto/bytestring->hex (.getParentSpanId span))

       :name (.getName span)
       :span.kind (-span-kind->kw (.getKind span))
       :span.status_code (-status-code->kw (.getCode status))
       :span.status_message (.getMessage status)
       :span.start_time (proto/nanos->instant start-nanos)
       :span.end_time (proto/nanos->instant end-nanos)
       :span.duration_ms (when (and (pos? end-nanos) (pos? start-nanos))
                           (/ (- end-nanos start-nanos) 1e6))

       ;; Instrumentation scope
       :scope.name scope-name
       :scope.version scope-version

       ;; Meta
       :meta.observed_time observed-time
       :meta.signal_type :span}
      prefixed-attrs)))

(defn- -span->events
  "Convert Span protobuf to events (span + span events)."
  [^Span span resource-attrs scope-attrs scope-name scope-version service-name observed-time]
  (let [span-event (-span->event span resource-attrs scope-attrs scope-name scope-version service-name observed-time)
        span-events (map #(-span-event->event % span resource-attrs scope-attrs scope-name scope-version service-name observed-time)
                         (.getEventsList span))]
    (cons span-event span-events)))

;; ---------------------------------------------------------
;; Public API

(defn trace-request->events
  "Convert ExportTraceServiceRequest protobuf directly to unified events.

   A single span can produce multiple events:
   - One :span event for the span itself
   - Multiple :span-event events for span events

   Returns a sequence of event maps. Rejects (skips) resource spans without service.name."
  [^ExportTraceServiceRequest request]
  (let [observed-time (Instant/now)]
    (for [^ResourceSpans resource-spans (.getResourceSpansList request)
          :let [^Resource resource (.getResource resource-spans)
                service-name (proto/extract-service-name resource)]
          :when service-name
          :let [resource-attrs (proto/extract-attributes (.getAttributesList resource))]
          ^ScopeSpans scope-spans (.getScopeSpansList resource-spans)
          :let [scope (proto/extract-scope (.getScope scope-spans))
                scope-attrs (:attributes scope)
                scope-name (:name scope)
                scope-version (:version scope)]
          ^Span span (.getSpansList scope-spans)
          event (-span->events span resource-attrs scope-attrs scope-name scope-version service-name observed-time)]
      event)))

(defn trace-response->proto
  "Convert Clojure response map to ExportTraceServiceResponse.

   Accepts:
   {:rejected-span-count 0
    :error-message \"\"} or nil for success"
  [{:keys [rejected-span-count error-message] :or {rejected-span-count 0 error-message ""}}]
  (let [partial-success (-> (ExportTracePartialSuccess/newBuilder)
                            (.setRejectedSpans rejected-span-count)
                            (.setErrorMessage error-message)
                            (.build))]
    (-> (ExportTraceServiceResponse/newBuilder)
        (.setPartialSuccess partial-success)
        (.build))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example event structure (attributes prefixed with attr.):
  {:service "my-service"
   :timestamp #inst "2024-01-15T10:30:00Z"
   :trace_id "0af7651916cd43dd8448eb211c80319c"
   :span_id "b7ad6b7169203331"
   :parent_span_id "00f067aa0ba902b7"
   :name "GET /api/users"
   :span.kind :server
   :span.status_code :ok
   :span.start_time #inst "2024-01-15T10:30:00Z"
   :span.end_time #inst "2024-01-15T10:30:00.100Z"
   :span.duration_ms 100.0
   :scope.name "http-server"
   :scope.version "1.0.0"
   :meta.observed_time #inst "2024-01-15T10:30:01Z"
   :meta.signal_type :span
   ;; Prefixed attributes (from resource, scope, and span)
   "attr.http.method" "GET"
   "attr.http.status_code" 200
   "attr.service.name" "my-service"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
