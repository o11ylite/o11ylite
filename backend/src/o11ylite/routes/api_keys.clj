;; ---------------------------------------------------------
;; o11ylite.routes.api-keys
;;
;; API key management Inertia page routes.
;; Keys are immutable — create or delete only.
;; The full key is shown only once at creation time.
;; ---------------------------------------------------------

(ns o11ylite.routes.api-keys
  (:require
    [o11ylite.api-key :as api-key]
    [o11ylite.api-key.crypto :as crypto]
    [o11ylite.api-key.schema :as api-key-schema]
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.components.api-key-cache :as api-key-cache]
    [o11ylite.util.response :as response]
    [o11ylite.util.validation :as validation]
    [ring.util.response :as rr])
  (:import
    [com.github.f4b6a3.uuid UuidCreator]))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-list-handler
  "GET /system/api-keys - List all API keys."
  [{:keys [sqlite]}]
  (fn [_request]
    (let [keys (api-key/list-all sqlite)]
      (response/inertia "ApiKeys" {:api_keys keys}))))

(defn- -make-new-handler
  "GET /system/api-keys/new - Render create form."
  [_opts]
  (fn [request]
    (response/inertia "ApiKeyCreate"
                      {:errors (get-in request [:flash :errors] {})})))

(defn- -make-create-handler
  "POST /system/api-keys - Create a new API key.
   Returns the full key in the created_key prop (shown once)."
  [{:keys [sqlite api-key-cache]}]
  (fn [request]
    (let [params {:name (get-in request [:body :name])
                  :scope (get-in request [:body :scope])}]
      (if-let [validation-error (api-key-schema/validate params)]
        (-> (rr/redirect "/system/api-keys/new" :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (let [{:keys [key prefix key-hash]} (crypto/generate-key)
              id (str (UuidCreator/getTimeOrderedEpoch))]
          (api-key/create! sqlite {:id id
                                   :name (:name params)
                                   :prefix prefix
                                   :key-hash key-hash
                                   :scope (:scope params)})
          ;; Refresh the in-memory cache
          (api-key-cache/refresh! api-key-cache)
          ;; Redirect to list with the created key in flash (shown once)
          (-> (rr/redirect "/system/api-keys" :see-other)
              (assoc :flash {:created-key key
                             :created-key-name (:name params)})))))))

(defn- -make-delete-handler
  "DELETE /system/api-keys/:id - Delete an API key."
  [{:keys [sqlite api-key-cache]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])]
      (api-key/delete! sqlite id)
      ;; Refresh the in-memory cache
      (api-key-cache/refresh! api-key-cache)
      (rr/redirect "/system/api-keys" :see-other))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "API key management routes. Requires admin scope when auth is active."
  [{:keys [auth-config] :as opts}]
  (let [middleware (when-not (:open-mode? auth-config)
                     [(auth-mw/make-wrap-require-scope "admin")])]
    ["/system/api-keys" {:middleware middleware}
     ["" {:get {:handler (-make-list-handler opts)}
          :post {:handler (-make-create-handler opts)}}]
     ["/new" {:get {:handler (-make-new-handler opts)}}]
     ["/:id" {:delete {:handler (-make-delete-handler opts)}}]]))
