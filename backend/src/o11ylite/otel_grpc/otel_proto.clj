;; ---------------------------------------------------------
;; o11ylite.otel-grpc.otel-proto
;;
;; OpenTelemetry protocol buffer conversion utilities
;; Converts between Java protobuf objects and Clojure maps
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.otel-proto
  (:import
   [com.google.protobuf ByteString]
   [io.opentelemetry.proto.common.v1 AnyValue KeyValue InstrumentationScope]
   [io.opentelemetry.proto.resource.v1 Resource]
   [io.opentelemetry.proto.trace.v1 Span Span$SpanKind Span$Event Span$Link Status Status$StatusCode ResourceSpans ScopeSpans]
   [io.opentelemetry.proto.collector.trace.v1 ExportTraceServiceRequest ExportTraceServiceResponse ExportTracePartialSuccess]))

;; ---------------------------------------------------------
;; Byte conversions

(defn bytes->hex
  "Convert byte array to hex string."
  [^bytes ba]
  (when ba
    (let [sb (StringBuilder.)]
      (doseq [b ba]
        (.append sb (format "%02x" b)))
      (.toString sb))))

(defn bytestring->hex
  "Convert protobuf ByteString to hex string."
  [^ByteString bs]
  (when bs
    (bytes->hex (.toByteArray bs))))

;; ---------------------------------------------------------
;; Common types

(defn- -any-value->clj
  "Convert AnyValue to Clojure value."
  [^AnyValue av]
  (when av
    (case (.getValueCase av)
      :STRING_VALUE (.getStringValue av)
      :BOOL_VALUE (.getBoolValue av)
      :INT_VALUE (.getIntValue av)
      :DOUBLE_VALUE (.getDoubleValue av)
      :BYTES_VALUE (bytestring->hex (.getBytesValue av))
      :ARRAY_VALUE (mapv -any-value->clj (.getValuesList (.getArrayValue av)))
      :KVLIST_VALUE (into {} (map (fn [^KeyValue kv]
                                    [(.getKey kv) (-any-value->clj (.getValue kv))])
                                  (.getValuesList (.getKvlistValue av))))
      nil)))

(defn- -key-value->clj
  "Convert KeyValue to [key value] pair."
  [^KeyValue kv]
  [(.getKey kv) (-any-value->clj (.getValue kv))])

(defn- -attributes->clj
  "Convert repeated KeyValue to Clojure map."
  [kvs]
  (into {} (map -key-value->clj kvs)))

(defn- -instrumentation-scope->clj
  "Convert InstrumentationScope to Clojure map."
  [^InstrumentationScope scope]
  (when scope
    {:name (.getName scope)
     :version (.getVersion scope)
     :attributes (-attributes->clj (.getAttributesList scope))}))

(defn- -resource->clj
  "Convert Resource to Clojure map."
  [^Resource resource]
  (when resource
    {:attributes (-attributes->clj (.getAttributesList resource))
     :dropped-attributes-count (.getDroppedAttributesCount resource)}))

;; ---------------------------------------------------------
;; Trace types

(defn- -span-kind->clj
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

(defn- -status-code->clj
  "Convert StatusCode enum to keyword."
  [^Status$StatusCode code]
  (case (.getNumber code)
    0 :unset
    1 :ok
    2 :error
    :unknown))

(defn- -status->clj
  "Convert Status to Clojure map."
  [^Status status]
  (when status
    {:code (-status-code->clj (.getCode status))
     :message (.getMessage status)}))

(defn- -event->clj
  "Convert Span.Event to Clojure map."
  [^Span$Event event]
  {:time-unix-nano (.getTimeUnixNano event)
   :name (.getName event)
   :attributes (-attributes->clj (.getAttributesList event))
   :dropped-attributes-count (.getDroppedAttributesCount event)})

(defn- -link->clj
  "Convert Span.Link to Clojure map."
  [^Span$Link link]
  {:trace-id (bytestring->hex (.getTraceId link))
   :span-id (bytestring->hex (.getSpanId link))
   :trace-state (.getTraceState link)
   :attributes (-attributes->clj (.getAttributesList link))
   :dropped-attributes-count (.getDroppedAttributesCount link)
   :flags (.getFlags link)})

(defn- -span->clj
  "Convert Span to Clojure map."
  [^Span span]
  {:trace-id (bytestring->hex (.getTraceId span))
   :span-id (bytestring->hex (.getSpanId span))
   :parent-span-id (bytestring->hex (.getParentSpanId span))
   :trace-state (.getTraceState span)
   :name (.getName span)
   :kind (-span-kind->clj (.getKind span))
   :start-time-unix-nano (.getStartTimeUnixNano span)
   :end-time-unix-nano (.getEndTimeUnixNano span)
   :attributes (-attributes->clj (.getAttributesList span))
   :dropped-attributes-count (.getDroppedAttributesCount span)
   :events (mapv -event->clj (.getEventsList span))
   :dropped-events-count (.getDroppedEventsCount span)
   :links (mapv -link->clj (.getLinksList span))
   :dropped-links-count (.getDroppedLinksCount span)
   :status (-status->clj (.getStatus span))
   :flags (.getFlags span)})

(defn- -scope-spans->clj
  "Convert ScopeSpans to Clojure map."
  [^ScopeSpans ss]
  {:scope (-instrumentation-scope->clj (.getScope ss))
   :spans (mapv -span->clj (.getSpansList ss))
   :schema-url (.getSchemaUrl ss)})

(defn- -resource-spans->clj
  "Convert ResourceSpans to Clojure map."
  [^ResourceSpans rs]
  {:resource (-resource->clj (.getResource rs))
   :scope-spans (mapv -scope-spans->clj (.getScopeSpansList rs))
   :schema-url (.getSchemaUrl rs)})

;; ---------------------------------------------------------
;; Public API

(defn trace-request->clj
  "Convert ExportTraceServiceRequest to Clojure map.
   
   Returns:
   {:resource-spans [{:resource {...}
                      :scope-spans [{:scope {...}
                                     :spans [{:trace-id \"...\"
                                              :span-id \"...\"
                                              :name \"...\"
                                              ...}]}]}]}"
  [^ExportTraceServiceRequest request]
  {:resource-spans (mapv -resource-spans->clj (.getResourceSpansList request))})

(defn trace-response->proto
  "Convert Clojure response map to ExportTraceServiceResponse.
   
   Accepts:
   {:rejected-spans 0
    :error-message \"\"} or nil for success"
  [{:keys [rejected-spans error-message] :or {rejected-spans 0 error-message ""}}]
  (let [partial-success (-> (ExportTracePartialSuccess/newBuilder)
                            (.setRejectedSpans rejected-spans)
                            (.setErrorMessage error-message)
                            (.build))]
    (-> (ExportTraceServiceResponse/newBuilder)
        (.setPartialSuccess partial-success)
        (.build))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example usage in a handler:
  ;; (defn handle-traces [request-map]
  ;;   (let [spans (for [rs (:resource-spans request-map)
  ;;                     ss (:scope-spans rs)
  ;;                     span (:spans ss)]
  ;;                 span)]
  ;;     (println "Received" (count spans) "spans")
  ;;     {:rejected-spans 0}))

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
