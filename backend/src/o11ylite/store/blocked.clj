;; ---------------------------------------------------------
;; o11ylite.store.blocked
;;
;; Blocked field management backed by the kv store.
;; Stores two sets of field names that should be hidden from
;; the UI and skipped during ingestion:
;;   - "blocked-event-fields"  — event attribute columns
;;   - "blocked-metric-fields" — metric attribute columns
;;
;; A missing kv key means nothing is blocked (empty set).
;; ---------------------------------------------------------

(ns o11ylite.store.blocked
  (:require
    [o11ylite.kv :as kv]))

;; ---------------------------------------------------------
;; Keys

(def ^:private event-fields-key "blocked-event-fields")
(def ^:private metric-fields-key "blocked-metric-fields")

;; ---------------------------------------------------------
;; Read

(defn get-blocked-event-fields
  "Return the set of blocked event field names. Never nil."
  [sqlite]
  (or (kv/get-value sqlite event-fields-key) #{}))

(defn get-blocked-metric-fields
  "Return the set of blocked metric field names. Never nil."
  [sqlite]
  (or (kv/get-value sqlite metric-fields-key) #{}))

;; ---------------------------------------------------------
;; Write — event fields

(defn block-event-fields!
  "Add `field-names` (collection of strings) to the blocked-event-fields set."
  [sqlite field-names]
  (let [current (get-blocked-event-fields sqlite)]
    (kv/set-value! sqlite event-fields-key (into current field-names))))

(defn unblock-event-fields!
  "Remove `field-names` (collection of strings) from the blocked-event-fields set."
  [sqlite field-names]
  (let [current (get-blocked-event-fields sqlite)]
    (kv/set-value! sqlite event-fields-key (apply disj current field-names))))

;; ---------------------------------------------------------
;; Write — metric fields

(defn block-metric-fields!
  "Add `field-names` (collection of strings) to the blocked-metric-fields set."
  [sqlite field-names]
  (let [current (get-blocked-metric-fields sqlite)]
    (kv/set-value! sqlite metric-fields-key (into current field-names))))

(defn unblock-metric-fields!
  "Remove `field-names` (collection of strings) from the blocked-metric-fields set."
  [sqlite field-names]
  (let [current (get-blocked-metric-fields sqlite)]
    (kv/set-value! sqlite metric-fields-key (apply disj current field-names))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def sqlite (:db/sqlite system))

  ;; Initially empty
  (get-blocked-event-fields sqlite)
  ;; => #{}

  ;; Block some fields
  (block-event-fields! sqlite ["attr.http.method" "attr.bad.field"])
  (get-blocked-event-fields sqlite)
  ;; => #{"attr.http.method" "attr.bad.field"}

  ;; Unblock one
  (unblock-event-fields! sqlite ["attr.http.method"])
  (get-blocked-event-fields sqlite)
  ;; => #{"attr.bad.field"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
