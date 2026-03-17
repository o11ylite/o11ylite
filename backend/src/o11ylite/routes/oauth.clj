;; ---------------------------------------------------------
;; o11ylite.routes.oauth
;;
;; OAuth 2.0 Authorization Code + PKCE endpoints for agent auth.
;; GET  /oauth/authorize — issues signed authorization code
;; POST /oauth/token     — exchanges code for access token JWT
;; ---------------------------------------------------------

(ns o11ylite.routes.oauth
  (:require
   [o11ylite.auth.scope :as scope]
   [o11ylite.oauth :as oauth]
   [o11ylite.util.response :as response]
   [ring.util.codec :as codec]
   [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; Validation Helpers

(def ^:private localhost-pattern
  "Matches http://localhost:<port> or http://127.0.0.1:<port> with optional path."
  #"^http://(localhost|127\.0\.0\.1)(:\d+)?(/.*)?$")

(defn- -valid-redirect-uri?
  "Returns true if redirect_uri is restricted to localhost."
  [uri]
  (and (string? uri) (re-matches localhost-pattern uri)))

(defn- -redirect-with-error
  "Redirect to redirect_uri with OAuth error params."
  [redirect-uri error description state]
  (let [params (cond-> {"error" error
                        "error_description" description}
                 state (assoc "state" state))
        query (codec/form-encode params)]
    (rr/redirect (str redirect-uri "?" query))))

(defn- -redirect-with-code
  "Redirect to redirect_uri with authorization code and state."
  [redirect-uri code state]
  (let [params (cond-> {"code" code}
                 state (assoc "state" state))
        query (codec/form-encode params)]
    (rr/redirect (str redirect-uri "?" query))))

;; ---------------------------------------------------------
;; Authorize Endpoint

(defn- -validate-authorize-params
  "Validate /oauth/authorize query parameters.
   Returns nil on success, or {:error :description} on failure."
  [{:keys [response_type redirect_uri code_challenge code_challenge_method scope]}]
  (cond
    (not= response_type "code")
    {:error "invalid_request" :description "response_type must be 'code'"}

    (not (-valid-redirect-uri? redirect_uri))
    {:error "invalid_request" :description "redirect_uri must be http://localhost or http://127.0.0.1"
     :bad-redirect true}

    (nil? code_challenge)
    {:error "invalid_request" :description "code_challenge is required"}

    (not= code_challenge_method "S256")
    {:error "invalid_request" :description "code_challenge_method must be 'S256'"}

    (and scope (not (scope/valid-scopes scope)))
    {:error "invalid_request" :description (str "Invalid scope: " scope)}))

(defn- -make-authorize-handler
  "GET /oauth/authorize — Authorization endpoint.
   In open mode: auto-approves immediately.
   In OIDC mode: requires authenticated session; redirects to login if needed."
  [{:keys [auth-config]}]
  (let [signing-key (:jwt-signing-key auth-config)]
    (fn [request]
      (let [params (:params request)
            {:keys [response_type redirect_uri code_challenge
                    code_challenge_method scope state]} params
            scope (or scope "write")
            params-with-defaults (assoc params :scope scope)
            validation-error (-validate-authorize-params params-with-defaults)]
        (cond
          ;; Bad redirect_uri — can't redirect to untrusted URI
          (and validation-error (:bad-redirect validation-error))
          (response/json 400 {:error (:error validation-error)
                              :error_description (:description validation-error)})

          ;; Other validation error — redirect with error
          validation-error
          (-redirect-with-error redirect_uri
                                (:error validation-error)
                                (:description validation-error)
                                state)

          ;; Open mode — auto-approve immediately
          (:open-mode? auth-config)
          (let [code (oauth/sign-authorization-code
                      signing-key
                      {:sub "_open_mode"
                       :scope scope
                       :code-challenge code_challenge
                       :redirect-uri redirect_uri})]
            (-redirect-with-code redirect_uri code state))

          ;; OIDC mode, user logged in — auto-approve
          (get-in request [:session :user])
          (let [sub (get-in request [:session :user :sub])
                code (oauth/sign-authorization-code
                      signing-key
                      {:sub sub
                       :scope scope
                       :code-challenge code_challenge
                       :redirect-uri redirect_uri})]
            (-redirect-with-code redirect_uri code state))

          ;; OIDC mode, not logged in — redirect to login with return_to
          :else
          (let [authorize-url (str "/oauth/authorize?"
                                   (codec/form-encode
                                    (cond-> {:response_type response_type
                                             :redirect_uri redirect_uri
                                             :code_challenge code_challenge
                                             :code_challenge_method code_challenge_method
                                             :scope scope}
                                      state (assoc :state state))))]
            (rr/redirect (str "/auth/login?return_to="
                              (codec/url-encode authorize-url)))))))))

;; ---------------------------------------------------------
;; Token Endpoint

(defn- -parse-token-params
  "Extract token request params from either JSON body or form-encoded params.
   JSON bodies are parsed by wrap-json-body into :body (keyword map).
   Form-encoded bodies are parsed by wrap-params into :params (keyword map)."
  [request]
  (let [content-type (get-in request [:headers "content-type"] "")]
    (cond
      (.contains content-type "application/json")
      (:body request)

      (.contains content-type "application/x-www-form-urlencoded")
      (:params request)

      :else
      (or (:body request) (:params request)))))

(defn token-handler
  "POST /oauth/token — Token endpoint.
   Exchanges authorization code + code_verifier for access token."
  [{:keys [auth-config]}]
  (let [signing-key (:jwt-signing-key auth-config)]
    (fn [request]
      (let [params (-parse-token-params request)
            grant-type (get params :grant_type (get params "grant_type"))
            code (get params :code (get params "code"))
            code-verifier (get params :code_verifier (get params "code_verifier"))
            redirect-uri (get params :redirect_uri (get params "redirect_uri"))]
        (cond
          (not= grant-type "authorization_code")
          (response/json 400 {:error "unsupported_grant_type"
                              :error_description "grant_type must be 'authorization_code'"})

          (or (nil? code) (nil? code-verifier) (nil? redirect-uri))
          (response/json 400 {:error "invalid_request"
                              :error_description "code, code_verifier, and redirect_uri are required"})

          :else
          (let [claims (oauth/verify signing-key code "code")]
            (cond
              (nil? claims)
              (response/json 400 {:error "invalid_grant"
                                  :error_description "Invalid or expired authorization code"})

              (not= (:redirect_uri claims) redirect-uri)
              (response/json 400 {:error "invalid_grant"
                                  :error_description "redirect_uri mismatch"})

              (not (oauth/verify-pkce code-verifier (:code_challenge claims)))
              (response/json 400 {:error "invalid_grant"
                                  :error_description "PKCE verification failed"})

              :else
              (let [access-token (oauth/sign-access-token
                                  signing-key
                                  {:sub (:sub claims)
                                   :scope (:scope claims)})]
                (response/json 200 {:access_token access-token
                                    :token_type "Bearer"
                                    :expires_in 3600
                                    :scope (:scope claims)})))))))))

;; ---------------------------------------------------------
;; Routes

(defn authorize-routes
  "OAuth authorize route (GET). Lives in page-routes for session access."
  [opts]
  ["/oauth"
   ["/authorize" {:get {:handler (-make-authorize-handler opts)}}]])

(defn token-routes
  "OAuth token route (POST). Lives in its own route group with API defaults
   (no CSRF, accepts JSON and form-encoded bodies)."
  [opts]
  ["/oauth"
   ["/token" {:post {:handler (token-handler opts)}}]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
