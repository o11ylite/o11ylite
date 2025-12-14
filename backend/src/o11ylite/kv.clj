;; ---------------------------------------------------------
;; o11ylite.kv
;;
;; Key-value store backed by SQLite.
;; Values are serialized/deserialized with nippy.
;; ---------------------------------------------------------

(ns o11ylite.kv
  (:require
   [next.jdbc :as jdbc]
   [next.jdbc.result-set :as rs]
   [taoensso.nippy :as nippy]))

;; ---------------------------------------------------------
;; Public API

(defn get-value
  "Get a value by key. Returns nil if not found."
  [sqlite key]
  (when-let [row (jdbc/execute-one!
                  sqlite
                  ["SELECT value FROM kv WHERE key = ?" key]
                  {:builder-fn rs/as-unqualified-lower-maps})]
    (nippy/thaw (:value row))))

(defn set-value!
  "Set a value for key. Upserts (inserts or updates)."
  [sqlite key value]
  (let [frozen (nippy/freeze value)]
    (jdbc/execute!
     sqlite
     ["INSERT INTO kv (key, value) VALUES (?, ?)
       ON CONFLICT(key) DO UPDATE SET value = excluded.value"
      key frozen]))
  nil)

(defn delete-value!
  "Delete a key. No-op if key doesn't exist."
  [sqlite key]
  (jdbc/execute! sqlite ["DELETE FROM kv WHERE key = ?" key])
  nil)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.core :as ig])

  ;; Start SQLite
  (def sqlite (ig/init-key :db/sqlite {:data-path "./.tmp"}))

  ;; Set a value
  (set-value! sqlite "test-key" {:foo "bar" :count 42})

  ;; Get it back
  (get-value sqlite "test-key")
  ;; => {:foo "bar" :count 42}

  ;; Update it
  (set-value! sqlite "test-key" {:foo "updated"})
  (get-value sqlite "test-key")
  ;; => {:foo "updated"}

  ;; Delete it
  (delete-value! sqlite "test-key")
  (get-value sqlite "test-key")
  ;; => nil

  ;; Cleanup
  (ig/halt-key! :db/sqlite sqlite)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
