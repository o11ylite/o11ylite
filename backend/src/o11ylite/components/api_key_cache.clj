;; ---------------------------------------------------------
;; o11ylite.components.api-key-cache
;;
;; Integrant component for the in-memory API key cache.
;; Maps SHA-256 key hashes to {:id :name :scope} for fast
;; lookup during request authentication.
;; ---------------------------------------------------------

(ns o11ylite.components.api-key-cache
  (:require
    [com.brunobonacci.mulog :as mulog]
    [integrant.core :as ig]
    [o11ylite.api-key.cache :as cache]
    [o11ylite.api-key.crypto :as crypto]
    [o11ylite.api-key.store :as store]))

;; ---------------------------------------------------------
;; Public API

(defn any-keys?
  "Returns true if at least one API key is registered."
  [api-key-cache]
  (cache/any-keys? (:cache api-key-cache)))

(defn refresh!
  "Refresh the cache from the database."
  [api-key-cache]
  (cache/refresh! (:cache api-key-cache) (:sqlite api-key-cache)))

(defn validate-token
  "Validate a raw API key token string.
   Returns {:id :name :scope} on success, nil on failure.
   Throttles last_used_at writes to once per minute per key."
  [api-key-cache token]
  (when token
    (let [hash (crypto/sha256 token)
          result (cache/lookup-by-hash (:cache api-key-cache) hash)]
      (when result
        ;; Throttle last_used_at DB writes — cache returns true at most once per minute per key
        (when (cache/touch-last-used! (:cache api-key-cache) hash (System/currentTimeMillis))
          (future (store/touch-last-used! (:sqlite api-key-cache) hash)))
        result))))

(defn- -extract-bearer-token
  "Extract token from 'Bearer <token>' authorization header."
  [auth-header]
  (when (and auth-header (.startsWith ^String auth-header "Bearer "))
    (subs auth-header 7)))

(defn validate-request
  "Validate an API key from a Ring request's Authorization header.
   Returns {:id :name :scope} on success, nil on failure."
  [api-key-cache request]
  (let [auth-header (get-in request [:headers "authorization"])
        token (-extract-bearer-token auth-header)]
    (validate-token api-key-cache token)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :auth/api-key-cache
  [_ {:keys [sqlite]}]
  (mulog/log ::api-key-cache-starting)
  (let [cache-atom (atom {})]
    (cache/refresh! cache-atom sqlite)
    {:cache cache-atom
     :sqlite sqlite}))

(defmethod ig/halt-key! :auth/api-key-cache
  [_ _]
  (mulog/log ::api-key-cache-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def akc (:auth/api-key-cache system))

  ;; Check if any keys exist
  (any-keys? akc)

  ;; Manual refresh
  (refresh! akc)

  ;; Validate a request
  (validate-request akc {:headers {"authorization" "Bearer o11y_abc123"}})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
