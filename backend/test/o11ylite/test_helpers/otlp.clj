;; ---------------------------------------------------------
;; o11ylite.test-helpers.otlp
;;
;; OTLP protobuf builders and gRPC client for integration tests.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers.otlp
  (:import
   [com.google.protobuf ByteString]
   [io.grpc ManagedChannelBuilder]
   [io.opentelemetry.proto.common.v1 AnyValue KeyValue InstrumentationScope]
   [io.opentelemetry.proto.resource.v1 Resource]
   [io.opentelemetry.proto.trace.v1 Span Span$SpanKind Span$Event Status Status$StatusCode ResourceSpans ScopeSpans]
   [io.opentelemetry.proto.collector.trace.v1 TraceServiceGrpc ExportTraceServiceRequest]
   [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------
;; Configuration

(def test-port
  "Port for test gRPC server (different from dev port 4317)"
  4318)

;; ---------------------------------------------------------
;; Internal Helpers

(defn- -hex->bytes
  "Convert hex string to ByteString."
  [^String hex]
  (let [len (/ (count hex) 2)
        ba (byte-array len)]
    (dotimes [i len]
      (aset ba i (unchecked-byte (Integer/parseInt (subs hex (* i 2) (+ (* i 2) 2)) 16))))
    (ByteString/copyFrom ba)))

(defn- -span-kind
  "Convert keyword to SpanKind enum."
  [kind]
  (case kind
    :internal Span$SpanKind/SPAN_KIND_INTERNAL
    :server Span$SpanKind/SPAN_KIND_SERVER
    :client Span$SpanKind/SPAN_KIND_CLIENT
    :producer Span$SpanKind/SPAN_KIND_PRODUCER
    :consumer Span$SpanKind/SPAN_KIND_CONSUMER
    Span$SpanKind/SPAN_KIND_UNSPECIFIED))

(defn- -status-code
  "Convert keyword to StatusCode enum."
  [status]
  (case status
    :ok Status$StatusCode/STATUS_CODE_OK
    :error Status$StatusCode/STATUS_CODE_ERROR
    Status$StatusCode/STATUS_CODE_UNSET))

(defn- -any-value
  "Convert Clojure value to AnyValue."
  [v]
  (let [builder (AnyValue/newBuilder)]
    (cond
      (string? v) (.setStringValue builder v)
      (boolean? v) (.setBoolValue builder v)
      (int? v) (.setIntValue builder (long v))
      (float? v) (.setDoubleValue builder (double v))
      :else (.setStringValue builder (str v)))
    (.build builder)))

(defn- -key-value
  "Convert [k v] pair to KeyValue."
  [[k v]]
  (-> (KeyValue/newBuilder)
      (.setKey (name k))
      (.setValue (-any-value v))
      (.build)))

(defn- -attributes
  "Convert map to repeated KeyValue."
  [m]
  (map -key-value m))

(defn- -build-span-event
  "Build a Span.Event protobuf from a map."
  [{:keys [name time-ns attributes]
    :or {attributes {}}}]
  (-> (Span$Event/newBuilder)
      (.setName name)
      (.setTimeUnixNano time-ns)
      (.addAllAttributes (-attributes attributes))
      (.build)))

;; ---------------------------------------------------------
;; Public Builders

(defn build-span
  "Build a Span protobuf from a flat map.

   Required keys:
   - :trace-id    - 32 char hex string
   - :span-id     - 16 char hex string
   - :name        - span name

   Optional keys:
   - :parent-span-id  - 16 char hex string
   - :kind            - :internal :server :client :producer :consumer
   - :start-time-ns   - start time in nanoseconds (default: now)
   - :end-time-ns     - end time in nanoseconds (default: now)
   - :attributes      - map of attributes
   - :status          - :ok :error :unset
   - :events          - vector of span event maps with :name, :time-ns, :attributes"
  [{:keys [trace-id span-id parent-span-id name kind
           start-time-ns end-time-ns attributes status events]
    :or {kind :internal
         start-time-ns (System/nanoTime)
         end-time-ns (System/nanoTime)
         attributes {}
         status :unset
         events []}}]
  (cond-> (Span/newBuilder)
    true (.setTraceId (-hex->bytes trace-id))
    true (.setSpanId (-hex->bytes span-id))
    parent-span-id (.setParentSpanId (-hex->bytes parent-span-id))
    true (.setName name)
    true (.setKind (-span-kind kind))
    true (.setStartTimeUnixNano start-time-ns)
    true (.setEndTimeUnixNano end-time-ns)
    true (.addAllAttributes (-attributes attributes))
    (seq events) (.addAllEvents (map -build-span-event events))
    true (.setStatus (-> (Status/newBuilder)
                         (.setCode (-status-code status))
                         (.build)))
    true (.build)))

(defn build-trace-request
  "Build an ExportTraceServiceRequest from a flat map.

   Keys:
   - :service-name   - resource service.name attribute (optional, omit to test rejection)
   - :tracer-name    - instrumentation scope name
   - :tracer-version - instrumentation scope version (optional)
   - :spans          - vector of span maps (see build-span)"
  [{:keys [service-name tracer-name tracer-version spans]
    :or {tracer-version ""}}]
  (let [resource (cond-> (Resource/newBuilder)
                   service-name (.addAttributes (-key-value ["service.name" service-name]))
                   true (.build))
        scope (cond-> (InstrumentationScope/newBuilder)
                true (.setName tracer-name)
                (seq tracer-version) (.setVersion tracer-version)
                true (.build))
        built-spans (map build-span spans)
        scope-spans (-> (ScopeSpans/newBuilder)
                        (.setScope scope)
                        (.addAllSpans built-spans)
                        (.build))
        resource-spans (-> (ResourceSpans/newBuilder)
                           (.setResource resource)
                           (.addScopeSpans scope-spans)
                           (.build))]
    (-> (ExportTraceServiceRequest/newBuilder)
        (.addResourceSpans resource-spans)
        (.build))))

;; ---------------------------------------------------------
;; gRPC Client

(defn export-traces!
  "Export traces to the test gRPC server.

   Takes a map with :service-name, :tracer-name, :spans etc.
   Returns the ExportTraceServiceResponse."
  [request-map]
  (let [channel (-> (ManagedChannelBuilder/forAddress "localhost" test-port)
                    (.usePlaintext)
                    (.build))
        stub (TraceServiceGrpc/newBlockingStub channel)
        request (build-trace-request request-map)]
    (try
      (.export stub request)
      (finally
        (.shutdown channel)
        (.awaitTermination channel 5 TimeUnit/SECONDS)))))
