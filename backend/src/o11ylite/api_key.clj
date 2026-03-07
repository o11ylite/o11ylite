;; ---------------------------------------------------------
;; o11ylite.api-key
;;
;; Facade for API key operations.
;; Re-exports from child namespaces for convenience.
;;
;; Namespace structure:
;; - api-key.store:  Database CRUD operations
;; - api-key.schema: Malli validation
;; - api-key.crypto: Key generation and SHA-256 hashing
;; - api-key.cache:  In-memory hash-to-key lookup cache
;; ---------------------------------------------------------

(ns o11ylite.api-key
  (:require
    [o11ylite.api-key.cache :as cache]
    [o11ylite.api-key.crypto :as crypto]
    [o11ylite.api-key.store :as store]))

;; ---------------------------------------------------------
;; Re-exports from store

(def create! store/create!)
(def delete! store/delete!)
(def get-by-id store/get-by-id)
(def list-all store/list-all)
(def list-all-with-hashes store/list-all-with-hashes)
(def any-keys-exist? store/any-keys-exist?)
(def touch-last-used! store/touch-last-used!)

;; ---------------------------------------------------------
;; Re-exports from crypto

(def sha256 crypto/sha256)
(def generate-key crypto/generate-key)

;; ---------------------------------------------------------
;; Re-exports from cache

(def refresh-cache! cache/refresh!)
(def lookup-by-hash cache/lookup-by-hash)
(def any-keys-cached? cache/any-keys?)

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def sqlite (:db/sqlite system))

  (list-all sqlite)
  (any-keys-exist? sqlite)
  (generate-key)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
