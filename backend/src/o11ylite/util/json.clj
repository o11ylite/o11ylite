;; ---------------------------------------------------------
;; o11ylite.util.json
;;
;; JSON encoding utilities with safe handling for large integers.
;; JavaScript's Number type uses 64-bit floats, which can only safely
;; represent integers up to 2^53-1. Larger values (like Snowflake IDs)
;; must be serialized as strings to preserve precision.
;; ---------------------------------------------------------

(ns o11ylite.util.json
  (:require
   [jsonista.core :as json])
  (:import
   [com.fasterxml.jackson.core JsonGenerator]
   [com.fasterxml.jackson.databind SerializerProvider]
   [com.fasterxml.jackson.databind.module SimpleModule]
   [com.fasterxml.jackson.databind.ser.std StdSerializer]))

;; ---------------------------------------------------------
;; Constants

(def ^:private max-safe-integer
  "JavaScript's Number.MAX_SAFE_INTEGER (2^53 - 1)"
  9007199254740991)

(def ^:private min-safe-integer
  "JavaScript's Number.MIN_SAFE_INTEGER (-(2^53 - 1))"
  -9007199254740991)

;; ---------------------------------------------------------
;; Custom Serializer

(defn- safe-long-serializer
  "Create a Jackson serializer that writes large Longs as strings.
   Values within JavaScript's safe integer range are written as numbers."
  []
  (proxy [StdSerializer] [Long]
    (serialize [^Long value ^JsonGenerator gen ^SerializerProvider _provider]
      (if (and (>= value min-safe-integer)
               (<= value max-safe-integer))
        (.writeNumber gen (long value))
        (.writeString gen (str value))))))

;; ---------------------------------------------------------
;; Object Mapper

(def api-mapper
  "JSON object mapper for API responses.
   Handles large integers (like Snowflake IDs) by serializing them as strings
   when they exceed JavaScript's safe integer range."
  (let [module (doto (SimpleModule.)
                 (.addSerializer Long (safe-long-serializer))
                 (.addSerializer Long/TYPE (safe-long-serializer)))]
    (json/object-mapper {:modules [module]})))

(defn write-str
  "Encode value as JSON string using the API mapper.
   Large integers are automatically converted to strings."
  [value]
  (json/write-value-as-string value api-mapper))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test with safe integer (should be number)
  (write-str {:count 42})
  ;; => "{\"count\":42}"

  ;; Test with large integer (should be string)
  (write-str {:id 273040663627431938})
  ;; => "{\"id\":\"273040663627431938\"}"

  ;; Test with MAX_SAFE_INTEGER boundary
  (write-str {:safe 9007199254740991
              :unsafe 9007199254740992})
  ;; => "{\"safe\":9007199254740991,\"unsafe\":\"9007199254740992\"}"

  ;; Test with nested structure
  (write-str {:user {:id 273040663627431938
                     :name "test"}
              :count 100})
  ;; => "{\"user\":{\"id\":\"273040663627431938\",\"name\":\"test\"},\"count\":100}"

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
