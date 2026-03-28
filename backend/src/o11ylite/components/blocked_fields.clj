;; ---------------------------------------------------------
;; o11ylite.components.blocked-fields
;;
;; In-memory cache of blocked field sets, backed by SQLite kv store.
;; Provides zero-IO reads for the ingestion hot path.
;;
;; Component shape: {:event-fields    <atom #{"field-name" ...}>
;;                   :metric-fields   <atom #{"field-name" ...}>
;;                   :metric-fields-kw <atom #{:field-name ...}>}
;;
;; metric-fields-kw is a derived keyword set kept in sync with
;; metric-fields on every write. It exists because metric data
;; points use keyword keys, and converting strings→keywords on
;; every ingestion batch is wasteful.
;;
;; Reads (get-*) deref atoms — no DB access.
;; Writes (block-*/unblock-*) persist to SQLite first, then
;; update the atom, so the cache is always consistent.
;; ---------------------------------------------------------

(ns o11ylite.components.blocked-fields
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.kv :as kv]))

;; ---------------------------------------------------------
;; KV Keys

(def ^:private event-fields-key "blocked-event-fields")
(def ^:private metric-fields-key "blocked-metric-fields")

;; ---------------------------------------------------------
;; Private Helpers

(defn- -load-set
  "Load a blocked-fields set from SQLite. Returns #{} if missing."
  [sqlite kv-key]
  (or (kv/get-value sqlite kv-key) #{}))

(defn- -strings->keywords
  "Convert a set of field name strings to keywords."
  [string-set]
  (into #{} (map keyword) string-set))

;; ---------------------------------------------------------
;; Public API — Reads (atom deref, no I/O)

(defn get-blocked-event-fields
  "Return the cached set of blocked event field names."
  [blocked-fields]
  @(:event-fields blocked-fields))

(defn get-blocked-metric-fields
  "Return the cached set of blocked metric field names (strings)."
  [blocked-fields]
  @(:metric-fields blocked-fields))

(defn get-blocked-metric-fields-kw
  "Return the cached set of blocked metric field names as keywords.
   Pre-computed on writes — no per-call conversion."
  [blocked-fields]
  @(:metric-fields-kw blocked-fields))

;; ---------------------------------------------------------
;; Public API — Writes (SQLite + atom update)

(defn block-event-fields!
  "Add `field-names` to the blocked-event-fields set.
   Persists to SQLite, then updates the in-memory cache."
  [blocked-fields sqlite field-names]
  (let [updated (into (get-blocked-event-fields blocked-fields) field-names)]
    (kv/set-value! sqlite event-fields-key updated)
    (reset! (:event-fields blocked-fields) updated)))

(defn unblock-event-fields!
  "Remove `field-names` from the blocked-event-fields set.
   Persists to SQLite, then updates the in-memory cache."
  [blocked-fields sqlite field-names]
  (let [updated (apply disj (get-blocked-event-fields blocked-fields) field-names)]
    (kv/set-value! sqlite event-fields-key updated)
    (reset! (:event-fields blocked-fields) updated)))

(defn block-metric-fields!
  "Add `field-names` to the blocked-metric-fields set.
   Persists to SQLite, then updates both string and keyword caches."
  [blocked-fields sqlite field-names]
  (let [updated (into (get-blocked-metric-fields blocked-fields) field-names)]
    (kv/set-value! sqlite metric-fields-key updated)
    (reset! (:metric-fields blocked-fields) updated)
    (reset! (:metric-fields-kw blocked-fields) (-strings->keywords updated))))

(defn unblock-metric-fields!
  "Remove `field-names` from the blocked-metric-fields set.
   Persists to SQLite, then updates both string and keyword caches."
  [blocked-fields sqlite field-names]
  (let [updated (apply disj (get-blocked-metric-fields blocked-fields) field-names)]
    (kv/set-value! sqlite metric-fields-key updated)
    (reset! (:metric-fields blocked-fields) updated)
    (reset! (:metric-fields-kw blocked-fields) (-strings->keywords updated))))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :cache/blocked-fields
  [_ {:keys [sqlite]}]
  (mulog/log ::blocked-fields-starting)
  (let [event-set (-load-set sqlite event-fields-key)
        metric-set (-load-set sqlite metric-fields-key)]
    (mulog/log ::blocked-fields-started
               :blocked-event-field-count (count event-set)
               :blocked-metric-field-count (count metric-set))
    {:event-fields (atom event-set)
     :metric-fields (atom metric-set)
     :metric-fields-kw (atom (-strings->keywords metric-set))}))

(defmethod ig/halt-key! :cache/blocked-fields
  [_ _]
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])

  (def bf (:cache/blocked-fields system))
  (def sqlite (:db/sqlite system))

  ;; Read (no I/O)
  (get-blocked-event-fields bf)
  ;; => #{}

  ;; Block some fields
  (block-event-fields! bf sqlite ["attr.http.method" "attr.bad.field"])
  (get-blocked-event-fields bf)
  ;; => #{"attr.http.method" "attr.bad.field"}

  ;; Unblock one
  (unblock-event-fields! bf sqlite ["attr.http.method"])
  (get-blocked-event-fields bf)
  ;; => #{"attr.bad.field"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
