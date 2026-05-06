;; ---------------------------------------------------------
;; o11ylite.auth.oidc
;;
;; OIDC authentication flow handlers: login, callback, logout.
;; Uses io.github.zhming0/oidc-client for all OIDC operations.
;; ---------------------------------------------------------

(ns o11ylite.auth.oidc
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.util.response :as response]
    [oidc-client.core :as oidc]
    [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -derive-redirect-uri
  "Derive the OAuth redirect URI from the request's Host header.
   Prefers X-Forwarded-Host over Host for reverse-proxy setups."
  [request]
  (let [scheme (or (get-in request [:headers "x-forwarded-proto"]) "http")
        host (or (get-in request [:headers "x-forwarded-host"])
                 (get-in request [:headers "host"]))]
    (str scheme "://" host "/auth/callback")))

;; ---------------------------------------------------------
;; Handlers

(defn login-handler
  "GET /auth/login — Generate PKCE verifier + state + nonce,
   store in session, redirect to authorization URL."
  [{:keys [oidc-config]}]
  (fn [request]
    (if-not oidc-config
      (rr/redirect "/")
      (let [verifier (oidc/random-pkce-code-verifier)
            state (oidc/random-state)
            nonce (oidc/random-nonce)
            redirect-uri (-derive-redirect-uri request)
            return-to (get-in request [:params :return_to])
            auth-url (oidc/build-authorization-url oidc-config
                                                   {:redirect_uri redirect-uri
                                                    :scope "openid email profile"
                                                    :state state
                                                    :nonce nonce
                                                    :code_challenge (oidc/pkce-code-challenge verifier)
                                                    :code_challenge_method "S256"})]
        (-> (rr/redirect auth-url)
            (assoc :session (cond-> {:oidc-state state
                                     :oidc-nonce nonce
                                     :oidc-verifier verifier}
                              return-to (assoc :return-to return-to))))))))

(defn callback-handler
  "GET /auth/callback — Exchange authorization code for tokens,
   fetch userinfo, populate session."
  [{:keys [oidc-config]}]
  (fn [request]
    (if-not oidc-config
      (rr/redirect "/")
      (let [{:keys [code state]} (:params request)
            session-state (get-in request [:session :oidc-state])
            verifier (get-in request [:session :oidc-verifier])]
        (cond
          (not= state session-state)
          (do
            (mulog/log ::oidc-callback-state-mismatch)
            (response/json 400 {:error "State mismatch"}))

          :else
          (let [redirect-uri (-derive-redirect-uri request)
                return-to (get-in request [:session :return-to])
                tokens (oidc/authorization-code-grant oidc-config
                                                      {:code code
                                                       :redirect_uri redirect-uri
                                                       :code_verifier verifier})
                userinfo (oidc/fetch-userinfo oidc-config (:access_token tokens))
                email (:email userinfo)]
            (mulog/log ::oidc-login-success :o11ylite.oidc.sub (:sub userinfo) :o11ylite.oidc.email email)
            (-> (rr/redirect (or return-to "/"))
                (assoc :session {:user {:sub (:sub userinfo)
                                        :email email
                                        :name (:name userinfo)}}))))))))

(defn logout-handler
  "POST /auth/logout — Clear session and redirect to /."
  [_auth-config]
  (fn [_request]
    (-> (rr/redirect "/")
        (assoc :session nil))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Authentication routes (outside auth-protected group)."
  [auth-config]
  ["/auth"
   ["/login" {:get {:handler (login-handler auth-config)}}]
   ["/callback" {:get {:handler (callback-handler auth-config)}}]
   ["/logout" {:post {:handler (logout-handler auth-config)}}]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
