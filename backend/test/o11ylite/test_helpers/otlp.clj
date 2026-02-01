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
    [io.opentelemetry.proto.logs.v1 LogRecord SeverityNumber ResourceLogs ScopeLogs]
    [io.opentelemetry.proto.metrics.v1 Gauge Sum Histogram AggregationTemporality NumberDataPoint HistogramDataPoint ResourceMetrics ScopeMetrics Metric]
    [io.opentelemetry.proto.collector.trace.v1 TraceServiceGrpc ExportTraceServiceRequest]
    [io.opentelemetry.proto.collector.logs.v1 LogsServiceGrpc ExportLogsServiceRequest]
    [io.opentelemetry.proto.collector.metrics.v1 MetricsServiceGrpc ExportMetricsServiceRequest]
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

(defn- -severity-number
  "Convert keyword to SeverityNumber enum."
  [severity]
  (case severity
    :trace SeverityNumber/SEVERITY_NUMBER_TRACE
    :debug SeverityNumber/SEVERITY_NUMBER_DEBUG
    :info SeverityNumber/SEVERITY_NUMBER_INFO
    :warn SeverityNumber/SEVERITY_NUMBER_WARN
    :error SeverityNumber/SEVERITY_NUMBER_ERROR
    :fatal SeverityNumber/SEVERITY_NUMBER_FATAL
    SeverityNumber/SEVERITY_NUMBER_UNSPECIFIED))

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
   Ingestion is synchronous - data is queryable immediately after this returns.

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

;; ---------------------------------------------------------
;; Log Builders

(defn build-log-record
  "Build a LogRecord protobuf from a flat map.

   Required keys:
   - :body        - log message body (string or any value)

   Optional keys:
   - :time-ns          - event time in nanoseconds (default: now)
   - :severity         - :trace :debug :info :warn :error :fatal
   - :severity-text    - string severity (e.g. \"INFO\")
   - :attributes       - map of attributes
   - :trace-id         - 32 char hex string (optional trace context)
   - :span-id          - 16 char hex string (optional trace context)"
  [{:keys [body time-ns severity severity-text attributes trace-id span-id]
    :or {time-ns (System/nanoTime)
         severity :unspecified
         severity-text ""
         attributes {}}}]
  (cond-> (LogRecord/newBuilder)
    true (.setTimeUnixNano time-ns)
    true (.setSeverityNumber (-severity-number severity))
    (seq severity-text) (.setSeverityText severity-text)
    true (.setBody (-any-value body))
    true (.addAllAttributes (-attributes attributes))
    trace-id (.setTraceId (-hex->bytes trace-id))
    span-id (.setSpanId (-hex->bytes span-id))
    true (.build)))

(defn build-logs-request
  "Build an ExportLogsServiceRequest from a flat map.

   Keys:
   - :service-name   - resource service.name attribute (optional, omit to test rejection)
   - :logger-name    - instrumentation scope name
   - :logger-version - instrumentation scope version (optional)
   - :logs           - vector of log record maps (see build-log-record)"
  [{:keys [service-name logger-name logger-version logs]
    :or {logger-version ""}}]
  (let [resource (cond-> (Resource/newBuilder)
                   service-name (.addAttributes (-key-value ["service.name" service-name]))
                   true (.build))
        scope (cond-> (InstrumentationScope/newBuilder)
                true (.setName logger-name)
                (seq logger-version) (.setVersion logger-version)
                true (.build))
        built-logs (map build-log-record logs)
        scope-logs (-> (ScopeLogs/newBuilder)
                       (.setScope scope)
                       (.addAllLogRecords built-logs)
                       (.build))
        resource-logs (-> (ResourceLogs/newBuilder)
                          (.setResource resource)
                          (.addScopeLogs scope-logs)
                          (.build))]
    (-> (ExportLogsServiceRequest/newBuilder)
        (.addResourceLogs resource-logs)
        (.build))))

(defn export-logs!
  "Export logs to the test gRPC server.
   Ingestion is synchronous - data is queryable immediately after this returns.

   Takes a map with :service-name, :logger-name, :logs etc.
   Returns the ExportLogsServiceResponse."
  [request-map]
  (let [channel (-> (ManagedChannelBuilder/forAddress "localhost" test-port)
                    (.usePlaintext)
                    (.build))
        stub (LogsServiceGrpc/newBlockingStub channel)
        request (build-logs-request request-map)]
    (try
      (.export stub request)
      (finally
        (.shutdown channel)
        (.awaitTermination channel 5 TimeUnit/SECONDS)))))

;; ---------------------------------------------------------
;; Metric Builders

(defn- -build-number-data-point
  "Build a NumberDataPoint protobuf from a map."
  [{:keys [value time-ns attributes]
    :or {time-ns (System/nanoTime)
         attributes {}}}]
  (-> (NumberDataPoint/newBuilder)
      (.setTimeUnixNano time-ns)
      (.setAsDouble (double value))
      (.addAllAttributes (-attributes attributes))
      (.build)))

(defn build-gauge-metric
  "Build a Gauge Metric protobuf from a map.

   Required keys:
   - :name        - metric name

   Optional keys:
   - :description - metric description
   - :unit        - metric unit
   - :data-points - vector of data point maps with :value, :time-ns, :attributes"
  [{:keys [name description unit data-points]
    :or {description ""
         unit ""
         data-points [{:value 0.0}]}}]
  (let [gauge (-> (Gauge/newBuilder)
                  (.addAllDataPoints (map -build-number-data-point data-points))
                  (.build))]
    (-> (Metric/newBuilder)
        (.setName name)
        (.setDescription description)
        (.setUnit unit)
        (.setGauge gauge)
        (.build))))

(defn- -aggregation-temporality
  "Convert keyword to AggregationTemporality enum."
  [temporality]
  (case temporality
    :delta AggregationTemporality/AGGREGATION_TEMPORALITY_DELTA
    :cumulative AggregationTemporality/AGGREGATION_TEMPORALITY_CUMULATIVE
    AggregationTemporality/AGGREGATION_TEMPORALITY_UNSPECIFIED))

(defn build-sum-metric
  "Build a Sum Metric protobuf from a map.

   Required keys:
   - :name        - metric name

   Optional keys:
   - :description  - metric description
   - :unit         - metric unit
   - :temporality  - :delta or :cumulative (default: :delta)
   - :monotonic?   - whether the sum is monotonic (default: true)
   - :data-points  - vector of data point maps with :value, :time-ns, :attributes"
  [{:keys [name description unit temporality monotonic? data-points]
    :or {description ""
         unit ""
         temporality :delta
         monotonic? true
         data-points [{:value 0.0}]}}]
  (let [sum (-> (Sum/newBuilder)
                (.setAggregationTemporality (-aggregation-temporality temporality))
                (.setIsMonotonic monotonic?)
                (.addAllDataPoints (map -build-number-data-point data-points))
                (.build))]
    (-> (Metric/newBuilder)
        (.setName name)
        (.setDescription description)
        (.setUnit unit)
        (.setSum sum)
        (.build))))

(defn- -build-histogram-data-point
  "Build a HistogramDataPoint protobuf from a map."
  [{:keys [bucket-counts boundaries count sum min max time-ns attributes]
    :or {time-ns (System/nanoTime)
         attributes {}}}]
  (cond-> (HistogramDataPoint/newBuilder)
    true (.setTimeUnixNano time-ns)
    true (.addAllBucketCounts (map long bucket-counts))
    true (.addAllExplicitBounds (map double boundaries))
    true (.setCount (long count))
    sum (.setSum (double sum))
    min (.setMin (double min))
    max (.setMax (double max))
    true (.addAllAttributes (-attributes attributes))
    true (.build)))

(defn build-histogram-metric
  "Build a Histogram Metric protobuf from a map.

   Required keys:
   - :name        - metric name
   - :boundaries  - vector of bucket boundaries (e.g. [0.005 0.01 0.025 0.05 0.1])

   Optional keys:
   - :description  - metric description
   - :unit         - metric unit
   - :temporality  - :delta or :cumulative (default: :delta)
   - :data-points  - vector of data point maps with:
                     :bucket-counts - vector of counts (length = boundaries + 1)
                     :count         - total count
                     :sum           - total sum (optional)
                     :min           - minimum value (optional)
                     :max           - maximum value (optional)
                     :time-ns       - timestamp (optional)
                     :attributes    - map of attributes (optional)"
  [{:keys [name description unit temporality boundaries data-points]
    :or {description ""
         unit ""
         temporality :delta
         data-points [{:bucket-counts [0] :count 0}]}}]
  ;; Inject boundaries into each data point (boundaries are metric-level but stored per-point in proto)
  (let [data-points-with-boundaries (map #(assoc % :boundaries boundaries) data-points)
        histogram (-> (Histogram/newBuilder)
                      (.setAggregationTemporality (-aggregation-temporality temporality))
                      (.addAllDataPoints (map -build-histogram-data-point data-points-with-boundaries))
                      (.build))]
    (-> (Metric/newBuilder)
        (.setName name)
        (.setDescription description)
        (.setUnit unit)
        (.setHistogram histogram)
        (.build))))

(defn build-metrics-request
  "Build an ExportMetricsServiceRequest from a flat map.

   Keys:
   - :service-name   - resource service.name attribute (optional, omit to test rejection)
   - :resource-attrs - additional resource attributes map (optional)
   - :meter-name     - instrumentation scope name
   - :meter-version  - instrumentation scope version (optional)
   - :metrics        - vector of Metric protobufs (use build-gauge-metric etc.)"
  [{:keys [service-name resource-attrs meter-name meter-version metrics]
    :or {meter-version "" resource-attrs {}}}]
  (let [all-resource-attrs (cond-> resource-attrs
                             service-name (assoc "service.name" service-name))
        resource (-> (Resource/newBuilder)
                     (.addAllAttributes (-attributes all-resource-attrs))
                     (.build))
        scope (cond-> (InstrumentationScope/newBuilder)
                true (.setName meter-name)
                (seq meter-version) (.setVersion meter-version)
                true (.build))
        scope-metrics (-> (ScopeMetrics/newBuilder)
                          (.setScope scope)
                          (.addAllMetrics metrics)
                          (.build))
        resource-metrics (-> (ResourceMetrics/newBuilder)
                             (.setResource resource)
                             (.addScopeMetrics scope-metrics)
                             (.build))]
    (-> (ExportMetricsServiceRequest/newBuilder)
        (.addResourceMetrics resource-metrics)
        (.build))))

(defn export-metrics!
  "Export metrics to the test gRPC server.
   
   Takes a map with :service-name, :meter-name, :metrics etc.
   Returns the ExportMetricsServiceResponse."
  [request-map]
  (let [channel (-> (ManagedChannelBuilder/forAddress "localhost" test-port)
                    (.usePlaintext)
                    (.build))
        stub (MetricsServiceGrpc/newBlockingStub channel)
        request (build-metrics-request request-map)]
    (try
      (.export stub request)
      (finally
        (.shutdown channel)
        (.awaitTermination channel 5 TimeUnit/SECONDS)))))
