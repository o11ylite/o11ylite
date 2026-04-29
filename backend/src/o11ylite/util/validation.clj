;; ---------------------------------------------------------
;; o11ylite.util.validation
;;
;; Converts Malli humanized errors into Inertia's flat
;; {field "error string"} format for flash error display.
;; ---------------------------------------------------------

(ns o11ylite.util.validation
  (:require
    [clojure.string :as str]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -field-name
  "Convert a key to a display string."
  [k]
  (cond
    (keyword? k) (name k)
    (integer? k) (str k)
    :else (str k)))

(defn- -first-message
  "Extract the first error message string from a Malli humanized value.
   Handles vectors of strings, nested vectors, maps, and bare strings."
  [v]
  (cond
    (string? v) v
    (sequential? v) (when (seq v)
                      (recur (first v)))
    (map? v) (let [[k inner] (first v)]
               (str (-field-name k) ": " (-first-message inner)))
    :else (str v)))

(defn- -flatten-entry
  "Flatten a single [key value] entry from Malli humanized errors.
   Returns [string-key string-message]."
  [[k v]]
  [(-field-name k)
   (cond
     (string? v)
     v

     (sequential? v)
     (or (-first-message v) (str v))

     (map? v)
     (str/join ", " (map (fn [[ik iv]]
                           (str (-field-name ik) ": " (-first-message iv)))
                         v))

     :else
     (str v))])

;; ---------------------------------------------------------
;; Public API

(defn flatten-for-inertia
  "Convert Malli humanized errors to Inertia's flat error format.
   Input:  {:name [\"missing required key\"]
            :query {:metrics [\"should have at least 1 elements\"]}}
   Output: {\"name\" \"missing required key\"
            \"query\" \"metrics: should have at least 1 elements\"}

   Schemas should attach :fn validator errors to a specific field via
   :error/path so they land in the map shape; otherwise top-level
   vectors collapse to a single first-message string."
  [errors]
  (cond
    (map? errors)
    (into {} (map -flatten-entry) errors)

    (sequential? errors)
    (-first-message errors)

    :else
    (str errors)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (flatten-for-inertia {:name ["missing required key"]})
  ;; => {"name" "missing required key"}

  (flatten-for-inertia {:query {:metrics ["should have at least 1 elements"]}})
  ;; => {"query" "metrics: should have at least 1 elements"}

  (flatten-for-inertia {:query {:metrics {0 {:id ["missing required key"]}}}})
  ;; => {"query" "metrics: 0: id: missing required key"}

  (flatten-for-inertia ["metric IDs must be unique"])
  ;; => "metric IDs must be unique"

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
