;; ---------------------------------------------------------
;; o11ylite.api-key.cache
;;
;; In-memory cache of API key hashes for fast authentication.
;; Maps SHA-256 hash -> {:id :name :scope}.
;; ---------------------------------------------------------

(ns o11ylite.api-key.cache
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.api-key.store :as store]))

;; ---------------------------------------------------------
;; Cache Operations

(defn- -build-cache
  "Load all keys from DB and build the cache map."
  [sqlite]
  (let [rows (store/list-all-with-hashes sqlite)]
    (into {} (map (fn [{:keys [key_hash id name scope last_used_at]}]
                    [key_hash {:id id :name name :scope scope
                               :last-used-at (or last_used_at 0)}]))
          rows)))

(defn refresh!
  "Refresh the API key cache from the database."
  [cache-atom sqlite]
  (let [new-cache (-build-cache sqlite)]
    (reset! cache-atom new-cache)
    (mulog/log ::refreshed :o11ylite.api_key_cache.entry_count (count new-cache))
    new-cache))

(defn lookup-by-hash
  "Look up an API key in the cache by its SHA-256 hash.
   Returns {:id :name :scope} or nil."
  [cache-atom key-hash]
  (get @cache-atom key-hash))

(def touch-interval-ms
  "Minimum interval between last_used_at DB writes per key (1 minute)."
  60000)

(defn touch-last-used!
  "Atomically update the in-memory last-used-at timestamp for a key hash.
   Returns true if the DB should be updated (i.e. the cached value was stale
   by more than `touch-interval-ms`), false otherwise."
  [cache-atom key-hash now-ms]
  (let [prev (get-in @cache-atom [key-hash :last-used-at] 0)]
    (if (< prev (- now-ms touch-interval-ms))
      (do (swap! cache-atom assoc-in [key-hash :last-used-at] now-ms)
          true)
      false)))

(defn any-keys?
  "Returns true if the cache has any API keys."
  [cache-atom]
  (pos? (count @cache-atom)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
