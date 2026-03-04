;; ---------------------------------------------------------
;; o11ylite.api-key.store
;;
;; SQLite CRUD operations for the api_keys table.
;; Keys are immutable — create or delete only, no update.
;; ---------------------------------------------------------

(ns o11ylite.api-key.store
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -now-ms
  []
  (System/currentTimeMillis))

;; ---------------------------------------------------------
;; Public API

(defn create!
  "Insert a new API key row. The full key is NOT stored —
   only the SHA-256 hash. Returns the id."
  [sqlite {:keys [id name prefix key-hash scope]}]
  (let [now (-now-ms)]
    (jdbc/execute!
      sqlite
      ["INSERT INTO api_keys (id, name, prefix, key_hash, scope, created_at)
        VALUES (?, ?, ?, ?, ?, ?)"
       id name prefix key-hash scope now])
    id))

(defn delete!
  "Delete an API key by ID."
  [sqlite id]
  (jdbc/execute!
    sqlite
    ["DELETE FROM api_keys WHERE id = ?" id]))

(defn get-by-id
  "Fetch a single API key by ID. Returns nil if not found."
  [sqlite id]
  (jdbc/execute-one!
    sqlite
    ["SELECT id, name, prefix, scope, created_at, last_used_at
      FROM api_keys WHERE id = ?" id]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn list-all
  "Fetch all API keys (without hashes), ordered by created_at desc."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["SELECT id, name, prefix, scope, created_at, last_used_at
      FROM api_keys ORDER BY created_at DESC"]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn list-all-with-hashes
  "Fetch all API keys including hashes. Used for cache refresh."
  [sqlite]
  (jdbc/execute!
    sqlite
    ["SELECT id, name, prefix, key_hash, scope, last_used_at
      FROM api_keys"]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn any-keys-exist?
  "Returns true if at least one API key exists in the database."
  [sqlite]
  (let [row (jdbc/execute-one!
              sqlite
              ["SELECT EXISTS(SELECT 1 FROM api_keys) AS has_keys"]
              {:builder-fn rs/as-unqualified-lower-maps})]
    (pos? (:has_keys row))))

(defn touch-last-used!
  "Update last_used_at for a key by its hash."
  [sqlite key-hash]
  (jdbc/execute!
    sqlite
    ["UPDATE api_keys SET last_used_at = ? WHERE key_hash = ?"
     (-now-ms) key-hash]))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  (list-all sqlite)
  (any-keys-exist? sqlite)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
