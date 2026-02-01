;; ---------------------------------------------------------
;; o11ylite.store.events.query-cursor
;;
;; Cursor encoding and decoding for keyset pagination.
;; Uses base64-encoded JSON: {f: field_name, v: sort_value, id: snowflake_id}
;; For default timestamp sorting, f is "timestamp".
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
   Format: {:f field-name :v sort-value :id snowflake-id}
   For default timestamp sorting, use f=\"timestamp\"."
  [{:keys [f v id]}]
  (let [json-str (json/write-value-as-string {"f" f "v" v "id" id})
        bytes (.getBytes json-str "UTF-8")]
    (.encodeToString (Base64/getEncoder) bytes)))

(defn decode
  "Decode base64 JSON cursor string.
   Returns map with :f, :v, :id. Returns nil if decoding fails."
  [cursor-str]
  (try
    (let [bytes (.decode (Base64/getDecoder) cursor-str)
          json-str (String. bytes "UTF-8")
          data (json/read-value json-str json/keyword-keys-object-mapper)]
      {:f (get data :f)
       :v (get data :v)
       :id (get data :id)})
    (catch Exception _
      nil)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Encode a cursor (default timestamp sort)
  (encode {:f "timestamp" :v 1702000000000 :id 123456789})
  ;; => "eyJmIjoidGltZXN0YW1wIiwidiI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1Njc4OX0="

  ;; Decode the cursor
  (decode "eyJmIjoidGltZXN0YW1wIiwidiI6MTcwMjAwMDAwMDAwMCwiaWQiOjEyMzQ1Njc4OX0=")
  ;; => {:f "timestamp", :v 1702000000000, :id 123456789}

  ;; Encode a cursor with custom sort field
  (encode {:f "service" :v "api-gateway" :id 123456789})
  ;; => "eyJmIjoic2VydmljZSIsInYiOiJhcGktZ2F0ZXdheSIsImlkIjoxMjM0NTY3ODl9"

  ;; Decode the custom sort cursor
  (decode "eyJmIjoic2VydmljZSIsInYiOiJhcGktZ2F0ZXdheSIsImlkIjoxMjM0NTY3ODl9")
  ;; => {:f "service", :v "api-gateway", :id 123456789}

  ;; Invalid cursor returns nil
  (decode "invalid-base64")
  ;; => nil

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
