;; ---------------------------------------------------------
;; o11ylite.auth.middleware
;;
;; Ring middleware for authentication and authorization.
;; Three layers:
;; 1. Identity middleware (pages + API) - resolves :auth/principal
;; 2. API key middleware (OTLP routes) - validates ingestion auth
;; 3. Scope check middleware (API routes) - enforces required scope
;;
;; These middleware always enforce auth. The caller decides whether
;; to include them based on :open-mode? in auth-config.
;; ---------------------------------------------------------

(ns o11ylite.auth.middleware
  (:require
    [o11ylite.auth.scope :as scope]
    [o11ylite.components.api-key-cache :as api-key-cache]
    [o11ylite.util.response :as response]
    [ring.util.codec :as codec]
    [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; Helpers

(defn- -auth-path?
  "Returns true if the request path starts with /auth/."
  [request]
  (.startsWith ^String (:uri request) "/auth/"))

(defn- -api-request?
  "Returns true if the request path starts with /api/."
  [request]
  (.startsWith ^String (:uri request) "/api/"))

(defn- -session-principal
  "Build a principal from the OIDC session user.
   Uses 'sub' as the unique identifier per OIDC spec."
  [session-user]
  {:type :user
   :scope "admin"
   :sub (:sub session-user)
   :name (:name session-user)
   :email (:email session-user)})

;; ---------------------------------------------------------
;; 1. Identity Middleware (pages + API routes)
;;
;; Resolves principal from session (OIDC user) or Authorization header (API key).
;; When no principal found:
;;   - Page requests → redirect to /auth/login
;;   - API requests → 401 JSON

(defn make-wrap-identity
  "Create identity middleware. Always enforces — caller should omit in open mode."
  [{:keys [api-key-cache]}]
  (fn [handler]
    (fn [request]
      (cond
        (-auth-path? request)
        (handler request)

        (get-in request [:session :user])
        (handler (assoc request :auth/principal
                        (-session-principal (get-in request [:session :user]))))

        :else
        (if-let [key-info (api-key-cache/validate-request api-key-cache request)]
          (handler (assoc request :auth/principal
                          {:type :api-key
                           :scope (:scope key-info)
                           :id (:id key-info)
                           :name (:name key-info)}))
          (if (-api-request? request)
            (response/json 401 {:error "Authentication required"})
            (let [return-to (cond-> (:uri request)
                              (:query-string request)
                              (str "?" (:query-string request)))]
              (rr/redirect (str "/auth/login?return_to="
                                (codec/url-encode return-to))))))))))

;; ---------------------------------------------------------
;; 2. API Key Middleware (OTLP HTTP routes)
;;
;; If no API keys exist in DB → allow all (open mode).
;; If keys exist → require valid key with "ingest" scope.
;; Note: this has its own "open when no keys" logic because
;; API key auth is opt-in independently of OIDC.

(defn make-wrap-otlp-auth
  "Create OTLP ingestion auth middleware."
  [{:keys [api-key-cache]}]
  (fn [handler]
    (fn [request]
      (if-not (api-key-cache/any-keys? api-key-cache)
        ;; No keys → open mode
        (handler request)
        ;; Keys exist → require valid key with ingest scope
        (if-let [key-info (api-key-cache/validate-request api-key-cache request)]
          (if (scope/has-scope? (:scope key-info) "ingest")
            (handler request)
            (response/json 403 {:error "Insufficient scope. Required: ingest"}))
          (response/json 401 {:error "API key required"}))))))

;; ---------------------------------------------------------
;; 3. Scope Check Middleware (API routes)
;;
;; Checks :auth/principal scope against required scope.
;; Always enforces — caller should omit in open mode.

(defn make-wrap-require-scope
  "Create a middleware that requires a specific scope on :auth/principal.
   Always enforces — caller should omit in open mode."
  [required-scope]
  (fn [handler]
    (fn [request]
      (if-let [principal (:auth/principal request)]
        (if (scope/has-scope? (:scope principal) required-scope)
          (handler request)
          (response/json 403 {:error (str "Insufficient scope. Required: " required-scope)}))
        (response/json 401 {:error "Authentication required"})))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
