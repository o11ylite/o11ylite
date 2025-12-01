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
  "Convert Span.Event protobuf directly to unified event."
  [^Span$Event span-event ^Span span resource-attrs scope-attrs scope-name scope-version service-name observed-time]
  (let [trace-id (proto/bytestring->hex (.getTraceId span))
        span-id (proto/bytestring->hex (.getSpanId span))
        event-attrs (proto/extract-attributes (.getAttributesList span-event))
        span-attrs (proto/extract-attributes (.getAttributesList span))]
    {:service service-name
     :timestamp (proto/nanos->instant (.getTimeUnixNano span-event))
     
     :trace-id trace-id
     :span-id span-id
     
     :name (.getName span-event)
     
     ;; Instrumentation scope
     :scope/name scope-name
     :scope/version scope-version
     
     ;; Merged attributes: resource + scope + span + event
     :attributes (merge resource-attrs scope-attrs span-attrs event-attrs)
     
     ;; Meta
     :meta/observed-time observed-time
     :meta/signal-type :span-event}))

(defn- -span->event
  "Convert Span protobuf directly to unified event."
  [^Span span resource-attrs scope-attrs scope-name scope-version service-name observed-time]
  (let [start-nanos (.getStartTimeUnixNano span)
        end-nanos (.getEndTimeUnixNano span)
        ^Status status (.getStatus span)
        span-attrs (proto/extract-attributes (.getAttributesList span))]
    {:service service-name
     :timestamp (proto/nanos->instant start-nanos)
     
     :trace-id (proto/bytestring->hex (.getTraceId span))
     :span-id (proto/bytestring->hex (.getSpanId span))
     :parent-span-id (proto/bytestring->hex (.getParentSpanId span))
     
     :name (.getName span)
     :span/kind (-span-kind->kw (.getKind span))
     :span/status-code (-status-code->kw (.getCode status))
     :span/status-message (.getMessage status)
     :span/start-time (proto/nanos->instant start-nanos)
     :span/end-time (proto/nanos->instant end-nanos)
     :span/duration-ns (when (and (pos? end-nanos) (pos? start-nanos))
                         (- end-nanos start-nanos))
     
     ;; Instrumentation scope
     :scope/name scope-name
     :scope/version scope-version
     
     ;; Merged attributes: resource + scope + span
     :attributes (merge resource-attrs scope-attrs span-attrs)
     
     ;; Meta
     :meta/observed-time observed-time
     :meta/signal-type :span}))

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

(defn count-rejected-spans
  "Count spans that would be rejected (no service.name)."
  [^ExportTraceServiceRequest request]
  (->> (.getResourceSpansList request)
       (filter #(nil? (proto/extract-service-name (.getResource ^ResourceSpans %))))
       (mapcat #(.getScopeSpansList ^ResourceSpans %))
       (mapcat #(.getSpansList ^ScopeSpans %))
       count))

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

  ;; Example event structure:
  ;; {:service "my-service"
  ;;  :timestamp #inst "2024-01-15T10:30:00Z"
  ;;  :trace-id "0af7651916cd43dd8448eb211c80319c"
  ;;  :span-id "b7ad6b7169203331"
  ;;  :parent-span-id "00f067aa0ba902b7"
  ;;  :name "GET /api/users"
  ;;  :span/kind :server
  ;;  :span/status-code :ok
  ;;  :span/start-time #inst "2024-01-15T10:30:00Z"
  ;;  :span/end-time #inst "2024-01-15T10:30:00.100Z"
  ;;  :span/duration-ns 100000000
  ;;  :scope/name "http-server"
  ;;  :scope/version "1.0.0"
  ;;  :attributes {"http.method" "GET"
  ;;               "http.status_code" 200
  ;;               "service.name" "my-service"}
  ;;  :meta/observed-time #inst "2024-01-15T10:30:01Z"
  ;;  :meta/signal-type :span}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
