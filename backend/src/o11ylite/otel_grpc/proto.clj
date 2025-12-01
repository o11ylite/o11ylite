;; ---------------------------------------------------------
;; o11ylite.otel-grpc.proto
;;
;; Shared OTLP protobuf helpers for traces, logs, and metrics.
;; Handles common conversions: bytes, AnyValue, attributes, time, Resource.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.proto
  (:import
   [com.google.protobuf ByteString]
   [io.opentelemetry.proto.common.v1 AnyValue AnyValue$ValueCase KeyValue InstrumentationScope]
   [io.opentelemetry.proto.resource.v1 Resource]
   [java.time Instant]))

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
;; Time conversions

(defn nanos->instant
  "Convert nanoseconds since epoch to java.time.Instant."
  [nanos]
  (when (and nanos (pos? nanos))
    (Instant/ofEpochSecond
     (quot nanos 1000000000)
     (mod nanos 1000000000))))

;; ---------------------------------------------------------
;; AnyValue / Attribute conversions

(defn any-value->clj
  "Convert AnyValue protobuf to Clojure value."
  [^AnyValue av]
  (when av
    (condp = (.getValueCase av)
      AnyValue$ValueCase/STRING_VALUE (.getStringValue av)
      AnyValue$ValueCase/BOOL_VALUE (.getBoolValue av)
      AnyValue$ValueCase/INT_VALUE (.getIntValue av)
      AnyValue$ValueCase/DOUBLE_VALUE (.getDoubleValue av)
      AnyValue$ValueCase/BYTES_VALUE (bytestring->hex (.getBytesValue av))
      AnyValue$ValueCase/ARRAY_VALUE (mapv any-value->clj (.getValuesList (.getArrayValue av)))
      AnyValue$ValueCase/KVLIST_VALUE (into {} (map (fn [^KeyValue kv]
                                                      [(.getKey kv) (any-value->clj (.getValue kv))])
                                                    (.getValuesList (.getKvlistValue av))))
      nil)))

(defn extract-attributes
  "Extract attributes from a list of KeyValue to a map."
  [kvs]
  (into {} (map (fn [^KeyValue kv]
                  [(.getKey kv) (any-value->clj (.getValue kv))])
                kvs)))

;; ---------------------------------------------------------
;; Resource helpers

(defn extract-service-name
  "Extract service.name from Resource protobuf."
  [^Resource resource]
  (when resource
    (some (fn [^KeyValue kv]
            (when (= "service.name" (.getKey kv))
              (any-value->clj (.getValue kv))))
          (.getAttributesList resource))))

;; ---------------------------------------------------------
;; Scope helpers

(defn extract-scope
  "Extract scope info from InstrumentationScope protobuf.
   Returns map with :name, :version, :attributes."
  [^InstrumentationScope scope]
  (when scope
    {:name (.getName scope)
     :version (.getVersion scope)
     :attributes (extract-attributes (.getAttributesList scope))}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example AnyValue conversions:
  ;; String -> "foo"
  ;; Bool -> true/false
  ;; Int -> 42
  ;; Double -> 3.14
  ;; Bytes -> "deadbeef" (hex)
  ;; Array -> ["a" "b" "c"]
  ;; KvList -> {"key" "value"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
