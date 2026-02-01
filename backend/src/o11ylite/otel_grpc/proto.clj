;; ---------------------------------------------------------
;; o11ylite.otel-grpc.proto
;;
;; Shared OTLP protobuf helpers for traces, logs, and metrics.
;; Handles common conversions: bytes, AnyValue, attributes, time, Resource.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.proto
  (:require
    [clojure.string :as str]
    [jsonista.core :as json])
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
;;
;; - Primitives (string, bool, int, double, bytes) returned as-is
;; - Arrays serialized to JSON strings (no natural flat representation)
;; - Kvlists returned as maps (flattened by extract-attributes with dot notation)

(defn- -any-value->clj-for-json
  "Convert AnyValue to Clojure value for JSON serialization (arrays only).
   Preserves nested structure for proper JSON output."
  [^AnyValue av]
  (when av
    (condp = (.getValueCase av)
      AnyValue$ValueCase/STRING_VALUE (.getStringValue av)
      AnyValue$ValueCase/BOOL_VALUE (.getBoolValue av)
      AnyValue$ValueCase/INT_VALUE (.getIntValue av)
      AnyValue$ValueCase/DOUBLE_VALUE (.getDoubleValue av)
      AnyValue$ValueCase/BYTES_VALUE (bytestring->hex (.getBytesValue av))
      AnyValue$ValueCase/ARRAY_VALUE (mapv -any-value->clj-for-json (.getValuesList (.getArrayValue av)))
      AnyValue$ValueCase/KVLIST_VALUE (into {} (map (fn [^KeyValue kv]
                                                      [(.getKey kv) (-any-value->clj-for-json (.getValue kv))])
                                                    (.getValuesList (.getKvlistValue av))))
      nil)))

(defn any-value->clj
  "Convert AnyValue protobuf to Clojure value.
   - Primitives (string, bool, int, double, bytes) returned as-is
   - Arrays serialized to JSON strings
   - Kvlists returned as maps (will be flattened by extract-attributes)"
  [^AnyValue av]
  (when av
    (condp = (.getValueCase av)
      AnyValue$ValueCase/STRING_VALUE (.getStringValue av)
      AnyValue$ValueCase/BOOL_VALUE (.getBoolValue av)
      AnyValue$ValueCase/INT_VALUE (.getIntValue av)
      AnyValue$ValueCase/DOUBLE_VALUE (.getDoubleValue av)
      AnyValue$ValueCase/BYTES_VALUE (bytestring->hex (.getBytesValue av))
      AnyValue$ValueCase/ARRAY_VALUE (json/write-value-as-string (mapv -any-value->clj-for-json (.getValuesList (.getArrayValue av))))
      AnyValue$ValueCase/KVLIST_VALUE (into {} (map (fn [^KeyValue kv]
                                                      [(.getKey kv) (any-value->clj (.getValue kv))])
                                                    (.getValuesList (.getKvlistValue av))))
      nil)))

(defn- -flatten-nested
  "Flatten a nested map with dot-separated string keys.
   {\"user\" {\"id\" 123}} -> {\"user.id\" 123}"
  [prefix m]
  (reduce-kv (fn [acc k v]
               (let [key (if prefix (str prefix "." k) k)]
                 (if (map? v)
                   (merge acc (-flatten-nested key v))
                   (assoc acc key v))))
             {}
             m))

(defn extract-attributes
  "Extract attributes from a list of KeyValue to a map with string keys.
   Nested kvlists are flattened with dot notation.
   Returns string keys (will be converted to keywords by prefix-attributes)."
  [kvs]
  (reduce (fn [acc ^KeyValue kv]
            (let [k (.getKey kv)
                  v (any-value->clj (.getValue kv))]
              (if (map? v)
                (merge acc (-flatten-nested k v))
                (assoc acc k v))))
          {}
          kvs))

;; ---------------------------------------------------------
;; Attribute prefixing
;;
;; Design decisions:
;;
;; 1. Why 'attr.' prefix?
;;    OTLP semantic conventions namespace attributes (http.*, db.*, rpc.*),
;;    but custom/user attributes may not (e.g., "successCount", "userId").
;;    The prefix provides clear separation between core event fields and
;;    dynamic attributes, preventing collisions with future core fields.
;;    We chose 'attr.' over 'attributes.' for brevity in queries:
;;      SELECT * FROM events WHERE "attr.http.status_code" = 500
;;
;; 2. Why keyword keys throughout?
;;    - JDBC returns keyword keys, so write/read are consistent
;;    - Idiomatic Clojure map access: (:attr.http.method row)
;;    - Keywords with dots are valid: :attr.http.status_code
;;    - Keyword interning for dynamic attributes is acceptable -
;;      high-cardinality values go in the value, not the key

(defn prefix-attributes
  "Add 'attr.' prefix to attribute keys and convert to keywords.
   Merges all attribute maps, prefixes each key, and returns keywords.
   
   Slashes in attribute names are converted to dots to avoid creating
   namespaced keywords (which cause issues with SQL column naming since
   `(name :foo/bar)` returns just \"bar\", losing the namespace).

   Example: {\"http.method\" \"GET\"} -> {:attr.http.method \"GET\"}
            {\"mulog/timestamp\" 123} -> {:attr.mulog.timestamp 123}"
  [& attr-maps]
  (reduce-kv (fn [acc k v]
               ;; Replace slashes with dots to avoid namespaced keywords
               (let [safe-key (str/replace (str k) "/" ".")]
                 (assoc acc (keyword (str "attr." safe-key)) v)))
             {}
             (apply merge attr-maps)))

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
  ;; Array -> "[\"a\",\"b\",\"c\"]" (JSON string)
  ;; KvList -> {"key" "value"} (map, flattened by extract-attributes)
  
  ;; Nested kvlist flattening example:
  ;; Attribute "user" with kvlist {"id": 123, "name": "alice"}
  ;; -> {"user.id" 123, "user.name" "alice"}

  ;; Test attribute prefixing
  (prefix-attributes {"http.method" "GET" "service.name" "my-svc"}
                     {"custom.attr" 123})
  ;; => {:attr.http.method "GET", :attr.service.name "my-svc", :attr.custom.attr 123}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
