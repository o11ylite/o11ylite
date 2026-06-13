;; ---------------------------------------------------------
;; o11ylite.alert-rule.fingerprint
;;
;; Stable per-group fingerprints for alert instances.
;;
;; A fingerprint identifies one group within a rule's results. It is a
;; SHA-256 hash of the rule's group-by column/value pairs, canonicalized
;; so the same group always hashes identically across evaluations:
;;
;;   - pairs are sorted by column name
;;   - values are coerced to a canonical string form (see -canon-value)
;;   - NULL is distinct from the empty string and from absence
;;   - integral floats collapse to their integer form (1.0 -> "1") so a
;;     value does not drift when DuckDB returns it as a different numeric
;;     type between evaluations
;;
;; A rule with no group-by produces the empty fingerprint "" — the
;; degenerate single-instance case. This is intentional: the same table,
;; state machine, and webhook path serve grouped and ungrouped rules.
;; ---------------------------------------------------------

(ns o11ylite.alert-rule.fingerprint
  (:require
    [clojure.string :as str])
  (:import
    [java.nio.charset StandardCharsets]
    [java.security MessageDigest]))

;; ---------------------------------------------------------
;; Private Helpers

(def ^:private -null-sentinel
  "Canonical token for a NULL group-by value. Distinct from the empty
   string so {col nil} and {col \"\"} hash differently."
  "\u0000NULL\u0000")

(defn- -canon-value
  "Coerce a group-by value to its canonical string form.
   Integral doubles collapse to integer form so 1.0 and 1 agree."
  [v]
  (cond
    (nil? v) -null-sentinel
    (string? v) v
    (boolean? v) (str v)
    (and (number? v)
         (not (ratio? v))
         (== v (Math/rint (double v)))
         (<= (Math/abs (double v)) 9.007199254740992E15))
    (str (long v))
    :else (str v)))

(defn- -sha256-hex
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.digest digest (.getBytes s StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" %) bytes))))

;; ---------------------------------------------------------
;; Public API

(def empty-fingerprint
  "Fingerprint of the no-group-by case. A rule without group-by has
   exactly one group, identified by this constant."
  "")

(defn fingerprint
  "Compute the stable fingerprint for a group, given a map of
   group-by column name -> value. Column names are coerced to strings
   and sorted; values are canonicalized. Returns a lowercase hex SHA-256
   string, or the empty fingerprint for an empty/nil label map (the
   no-group-by case)."
  [labels]
  (if (empty? labels)
    empty-fingerprint
    (let [pairs (->> labels
                     (map (fn [[k v]] [(name k) (-canon-value v)]))
                     (sort-by first))
          ;; Unit separator between key and value, record separator
          ;; between pairs — control chars that cannot appear in a
          ;; column name, so the joined string is unambiguous.
          canonical (str/join "\u001e"
                              (map (fn [[k v]] (str k "\u001f" v)) pairs))]
      (-sha256-hex canonical))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Order-independent: same fingerprint regardless of key order
  (= (fingerprint {:service "api" :region "us"})
     (fingerprint {:region "us" :service "api"}))
  ;; => true

  ;; No group-by -> empty fingerprint
  (fingerprint {})
  ;; => ""

  ;; 1 and 1.0 agree
  (= (fingerprint {:code 1}) (fingerprint {:code 1.0}))
  ;; => true

  ;; nil distinct from empty string
  (not= (fingerprint {:host nil}) (fingerprint {:host ""}))
  ;; => true

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
