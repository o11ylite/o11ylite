;; ---------------------------------------------------------
;; o11ylite.api-key.crypto
;;
;; API key generation and hashing.
;; Keys are formatted as "o11y_<32-hex-chars>".
;; Only the SHA-256 hash of the full key is persisted.
;; ---------------------------------------------------------

(ns o11ylite.api-key.crypto
  (:import
    [java.security MessageDigest SecureRandom]
    [java.util HexFormat]))

;; ---------------------------------------------------------
;; Hashing

(def ^:private hex-format (HexFormat/of))

(defn sha256
  "Compute SHA-256 hex digest of a string."
  [s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes ^String s "UTF-8"))]
    (.formatHex hex-format hash-bytes)))

;; ---------------------------------------------------------
;; Key Generation

(def ^:private secure-random (SecureRandom.))

(defn generate-key
  "Generate a new API key.
   Returns {:key \"o11y_...\" :prefix \"o11y_...\" :key-hash \"sha256-hex\"}."
  []
  (let [random-bytes (byte-array 16)
        _ (.nextBytes secure-random random-bytes)
        random-hex (.formatHex hex-format random-bytes)
        full-key (str "o11y_" random-hex)
        prefix (str "o11y_" (subs random-hex 0 8))]
    {:key full-key
     :prefix prefix
     :key-hash (sha256 full-key)}))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (sha256 "o11y_abc123")
  (generate-key)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
