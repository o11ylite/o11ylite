;; ---------------------------------------------------------
;; o11ylite.store.events.query-cursor
;;
;; Cursor encoding and decoding for keyset pagination.
;; Uses base64-encoded JSON to store (timestamp, id) pairs.
;; ---------------------------------------------------------

(ns o11ylite.store.events.query-cursor
  (:require
   [jsonista.core :as json])
  (:import
   [java.util Base64]))

;; ---------------------------------------------------------
;; Cursor Encoding/Decoding

(defn encode
  "Encode cursor data to base64 JSON string.
   Takes a map with :ts (timestamp in epoch-ms, can be double) and :id (Snowflake ID).
   Coerces ts to long for consistent JSON encoding."
  [{:keys [ts id]}]
  (let [json-str (json/write-value-as-string {"ts" (long ts) "id" id})
        bytes (.getBytes json-str "UTF-8")]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn decode
  "Decode base64 JSON cursor string to map with :ts and :id.
   Returns nil if decoding fails."
  [cursor-str]
  (try
    (let [bytes (.decode (Base64/getDecoder) cursor-str)
          json-str (String. bytes "UTF-8")
          data (json/read-value json-str json/keyword-keys-object-mapper)]
      {:ts (get data :ts)
       :id (get data :id)})
    (catch Exception _
      nil)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Encode a cursor
  (encode {:ts 1702000000000 :id 123456789})
  ;; => "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1Njc4OX0="

  ;; Decode the cursor back
  (decode "eyJ0cyI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1Njc4OX0=")
  ;; => {:ts 1702000000000, :id 123456789}

  ;; Invalid cursor returns nil
  (decode "invalid-base64")
  ;; => nil

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
