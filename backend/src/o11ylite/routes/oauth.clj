;; ---------------------------------------------------------
;; o11ylite.routes.oauth
;;
;; Built-in OAuth 2.1 authorization server for agents and MCP clients.
;;
;; GET  /.well-known/oauth-authorization-server — RFC 8414 metadata
;; GET  /oauth/authorize — validates the request; auto-approves local
;;      agents (no client_id or opaque client_id + localhost redirect),
;;      renders a consent page for Client ID Metadata Document clients
;; POST /oauth/authorize — consent page decision (CSRF-protected)
;; POST /oauth/token     — authorization_code and refresh_token grants
;;
;; Authorization codes and access tokens are stateless JWTs (see
;; o11ylite.oauth). Refresh tokens are stored and rotated (see
;; o11ylite.oauth.refresh-token). Authorization responses carry `iss`
;; (RFC 9207); a `resource` parameter (RFC 8707) binds tokens to the
;; MCP endpoint.
;; ---------------------------------------------------------

(ns o11ylite.routes.oauth
  (:require
    [clojure.string :as str]
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.auth.public-url :as public-url]
    [o11ylite.auth.scope :as scope]
    [o11ylite.oauth :as oauth]
    [o11ylite.oauth.client-metadata :as client-metadata]
    [o11ylite.oauth.refresh-token :as refresh-token]
    [o11ylite.util.response :as response]
    [ring.util.codec :as codec]
    [ring.util.response :as rr])
  (:import
    [java.net URI]))

;; ---------------------------------------------------------
;; Constants

(def ^:private default-scope
  "Scope granted when the client does not send one."
  "write")

(def scopes-supported
  "Scopes advertised in metadata documents. ingest/admin stay valid for
   explicit requests but are not useful to agents."
  ["read" "write"])

(def ^:private authorization-param-keys
  "Authorization request parameters carried from the consent page back
   to the POST handler."
  [:response_type :client_id :redirect_uri :code_challenge
   :code_challenge_method :scope :state :resource])

(def ^:private legacy-redirect-pattern
  "Local agents without a metadata document may only redirect to
   http://localhost:<port> or http://127.0.0.1:<port>."
  #"^http://(localhost|127\.0\.0\.1)(:\d+)?(/.*)?$")

;; ---------------------------------------------------------
;; Redirect Helpers

(defn- -redirect-to-client
  "Redirect to the client's redirect_uri with query params plus `iss`
   (RFC 9207) and `state` when present."
  [redirect-uri issuer state params]
  (let [query (codec/form-encode (cond-> (assoc params "iss" issuer)
                                   state (assoc "state" state)))
        separator (if (str/includes? redirect-uri "?") "&" "?")]
    (rr/redirect (str redirect-uri separator query))))

(defn- -redirect-with-error
  [{:keys [redirect-uri issuer state]} error description]
  (-redirect-to-client redirect-uri issuer state {"error" error
                                                  "error_description" description}))

(defn- -fatal-error
  "Error that must not be sent to the redirect_uri because the URI itself
   is not trusted (RFC 6749 §4.1.2.1)."
  [error description]
  (response/json 400 {:error error :error_description description}))

;; ---------------------------------------------------------
;; Authorization Request Validation

(defn- -resolve-client
  "Identify the client and check its redirect_uri.
   Returns {:client ...} or {:fatal response}."
  [auth-config {:keys [client_id redirect_uri]}]
  (if (client-metadata/cimd-client-id? client_id)
    (try
      (let [client (client-metadata/resolve-client (:client-metadata-cache auth-config) client_id)]
        (if (some #{redirect_uri} (:redirect-uris client))
          {:client client}
          {:fatal (-fatal-error "invalid_request"
                                "redirect_uri is not registered in the client metadata document")}))
      (catch clojure.lang.ExceptionInfo e
        (mulog/log ::client-metadata-rejected
                   :o11ylite.oauth.client_id client_id
                   :exception.message (ex-message e))
        ;; Fetch failures stay generic so the endpoint cannot be used to
        ;; probe which hosts exist; document problems are actionable.
        {:fatal (-fatal-error "invalid_client"
                              (if (= ::client-metadata/fetch-failed (:type (ex-data e)))
                                "The client metadata document could not be fetched"
                                (ex-message e)))}))
    (if (and (string? redirect_uri) (re-matches legacy-redirect-pattern redirect_uri))
      {:client {:client-id client_id :legacy? true}}
      {:fatal (-fatal-error "invalid_request"
                            "redirect_uri must be http://localhost or http://127.0.0.1")})))

(defn- -validate-params
  "Validate the remaining parameters. Returns {:error :description} or
   a map of resolved values."
  [auth-config request {:keys [response_type code_challenge code_challenge_method scope resource]}]
  (let [granted-scope (scope/resolve-requested (if (str/blank? scope) default-scope scope))
        mcp-resource (public-url/mcp-resource auth-config request)
        canonical-resource (some-> resource public-url/canonical-uri)]
    (cond
      (not= response_type "code")
      {:error "invalid_request" :description "response_type must be 'code'"}

      (str/blank? code_challenge)
      {:error "invalid_request" :description "code_challenge is required"}

      (not= code_challenge_method "S256")
      {:error "invalid_request" :description "code_challenge_method must be 'S256'"}

      (nil? granted-scope)
      {:error "invalid_scope" :description (str "Invalid scope: " scope)}

      (and resource (not= canonical-resource mcp-resource))
      {:error "invalid_target" :description (str "Unknown resource. Use " mcp-resource)}

      :else
      {:scope granted-scope
       :resource canonical-resource})))

(defn- -resolve-authorization-request
  "Validate an authorization request (query params or consent POST body).
   Returns one of:
   {:fatal response}           — reply directly, never redirect
   {:redirect-error response}  — error sent to the client's redirect_uri
   {:authz {...}}              — a valid request"
  [auth-config request params]
  (let [{:keys [client fatal]} (-resolve-client auth-config params)]
    (if fatal
      {:fatal fatal}
      (let [base {:client client
                  :redirect-uri (:redirect_uri params)
                  :state (:state params)
                  :issuer (public-url/base-url auth-config request)}
            validated (-validate-params auth-config request params)]
        (if (:error validated)
          {:redirect-error (-redirect-with-error base (:error validated) (:description validated))}
          {:authz (merge base
                         validated
                         {:code-challenge (:code_challenge params)
                          :params (select-keys params authorization-param-keys)})})))))

;; ---------------------------------------------------------
;; Authorization Decisions

(defn- -current-sub
  "Subject approving the request: the OIDC user, or a fixed subject in
   open mode. nil when the user must log in first."
  [auth-config request]
  (if (:open-mode? auth-config)
    "_open_mode"
    (get-in request [:session :user :sub])))

(defn- -login-redirect
  [params]
  ;; form-encode, not url-encode, for return_to: url-encode leaves "+"
  ;; (an encoded space inside the inner query) unescaped, so "scope=read+write"
  ;; would come back as "read write" after the login round trip.
  (let [authorize-url (str "/oauth/authorize?"
                           (codec/form-encode (into {} (filter (comp string? val))
                                                    (select-keys params authorization-param-keys))))]
    (rr/redirect (str "/auth/login?" (codec/form-encode {"return_to" authorize-url})))))

(defn- -issue-code
  [auth-config authz sub]
  (let [code (oauth/sign-authorization-code
               (:jwt-signing-key auth-config)
               {:sub sub
                :scope (:scope authz)
                :code-challenge (:code-challenge authz)
                :redirect-uri (:redirect-uri authz)
                :client-id (get-in authz [:client :client-id])
                :resource (:resource authz)})]
    (mulog/log ::authorization-granted
               :o11ylite.oauth.client_id (get-in authz [:client :client-id])
               :o11ylite.oauth.scope (:scope authz)
               :o11ylite.oauth.resource (:resource authz))
    (-redirect-to-client (:redirect-uri authz) (:issuer authz) (:state authz) {"code" code})))

(defn- -host-of
  [url]
  (try (.getHost (URI. url)) (catch Exception _ url)))

(defn- -consent-page
  [authz]
  (let [{:keys [client redirect-uri]} authz]
    (response/inertia "OAuthConsent"
                      {:client {:name (:client-name client)
                                :id (:client-id client)
                                :host (-host-of (:client-id client))
                                :uri (:client-uri client)}
                       :redirect_uri redirect-uri
                       :redirect_host (-host-of redirect-uri)
                       :localhost_redirect (client-metadata/localhost-redirect? redirect-uri)
                       :scope (:scope authz)
                       :resource (:resource authz)
                       :authorization_params (:params authz)})))

;; ---------------------------------------------------------
;; Authorize Endpoint

(defn- -make-authorize-handler
  "GET /oauth/authorize — Authorization endpoint.
   Login comes first so anonymous callers cannot make the server fetch
   client metadata documents (open mode has no login)."
  [{:keys [auth-config]}]
  (fn [request]
    (if-let [sub (-current-sub auth-config request)]
      (let [{:keys [fatal redirect-error authz]}
            (-resolve-authorization-request auth-config request (:params request))]
        (cond
          fatal fatal
          redirect-error redirect-error
          ;; Local agents (the bundled skill's auth.py) keep the original
          ;; auto-approve flow: the code can only reach a localhost port.
          (get-in authz [:client :legacy?]) (-issue-code auth-config authz sub)
          :else (-consent-page authz)))
      (-login-redirect (:params request)))))

(defn- -make-decision-handler
  "POST /oauth/authorize — consent page decision. Body carries the
   original authorization params plus decision: approve | deny.
   Re-validates everything; nothing from the GET is trusted."
  [{:keys [auth-config]}]
  (fn [request]
    (let [body (:body request)
          params (select-keys body authorization-param-keys)]
      (if-let [sub (-current-sub auth-config request)]
        (let [{:keys [fatal redirect-error authz]}
              (-resolve-authorization-request auth-config request params)]
          (cond
            fatal fatal
            redirect-error redirect-error
            (= "approve" (:decision body)) (-issue-code auth-config authz sub)
            :else (do (mulog/log ::authorization-denied
                                 :o11ylite.oauth.client_id (get-in authz [:client :client-id]))
                      (-redirect-with-error authz "access_denied" "The user denied the request"))))
        (-login-redirect params)))))

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

(defn- -token-error
  [error description]
  (response/json 400 {:error error :error_description description}))

(defn- -token-response
  "Mint an access token for a grant and build the token response."
  [auth-config {:keys [sub scope client-id resource]} refresh-token]
  (response/json 200 {:access_token (oauth/sign-access-token
                                      (:jwt-signing-key auth-config)
                                      {:sub sub
                                       :scope scope
                                       :audience resource
                                       :client-id client-id})
                      :token_type "Bearer"
                      :expires_in oauth/access-token-ttl-seconds
                      :scope scope
                      :refresh_token refresh-token}))

(defn- -refresh-store
  [auth-config sqlite]
  {:sqlite sqlite :signing-key (:jwt-signing-key auth-config)})

(defn- -resource-mismatch?
  "True when the token request names a resource other than the one the
   grant was issued for."
  [requested granted]
  (and requested (not= (public-url/canonical-uri requested) granted)))

(defn- -authorization-code-grant
  [{:keys [auth-config sqlite]} {:keys [code code_verifier redirect_uri client_id resource]}]
  (if (or (nil? code) (nil? code_verifier) (nil? redirect_uri))
    (-token-error "invalid_request" "code, code_verifier, and redirect_uri are required")
    (let [claims (oauth/verify (:jwt-signing-key auth-config) code "code")]
      (cond
        (nil? claims)
        (-token-error "invalid_grant" "Invalid or expired authorization code")

        (not= (:redirect_uri claims) redirect_uri)
        (-token-error "invalid_grant" "redirect_uri mismatch")

        (not (oauth/verify-pkce code_verifier (:code_challenge claims)))
        (-token-error "invalid_grant" "PKCE verification failed")

        (and (:client_id claims) (not= (:client_id claims) client_id))
        (-token-error "invalid_grant" "client_id mismatch")

        (-resource-mismatch? resource (:resource claims))
        (-token-error "invalid_target" "resource does not match the authorization request")

        ;; Checked last so a request without the PKCE verifier cannot burn a code.
        (not (oauth/consume-code! (:used-auth-codes auth-config) claims))
        (-token-error "invalid_grant" "Authorization code has already been used")

        :else
        (let [grant {:sub (:sub claims)
                     :scope (:scope claims)
                     :client-id (:client_id claims)
                     :resource (:resource claims)}]
          (-token-response auth-config grant
                           (refresh-token/issue! (-refresh-store auth-config sqlite) grant)))))))

(defn- -refresh-check
  "Validate a refresh request against the stored grant. Returns nil or
   {:error :description} without consuming the token."
  [{:keys [client_id resource scope]} grant]
  (let [requested-scope (when scope (scope/resolve-requested scope))]
    (cond
      (and (:client-id grant) (not= (:client-id grant) client_id))
      {:error "invalid_grant" :description "client_id mismatch"}

      (-resource-mismatch? resource (:resource grant))
      {:error "invalid_target" :description "resource does not match the original grant"}

      (and scope (or (nil? requested-scope)
                     (not (scope/has-scope? (:scope grant) requested-scope))))
      {:error "invalid_scope" :description "scope exceeds the original grant"})))

(defn- -refresh-token-grant
  [{:keys [auth-config sqlite]} {:keys [refresh_token scope] :as params}]
  (if (str/blank? refresh_token)
    (-token-error "invalid_request" "refresh_token is required")
    (let [{:keys [token grant error]} (refresh-token/rotate! (-refresh-store auth-config sqlite)
                                                             refresh_token
                                                             (partial -refresh-check params))]
      (case error
        nil (-token-response auth-config
                             ;; A narrower scope applies to this access token only;
                             ;; the rotated refresh token keeps the original grant.
                             (cond-> grant scope (assoc :scope (scope/resolve-requested scope)))
                             token)
        :invalid (-token-error "invalid_grant" "Invalid or expired refresh token")
        :reused (do (mulog/log ::refresh-token-reuse-detected)
                    (-token-error "invalid_grant" "Refresh token was already used; the session was revoked"))
        (-token-error (:error error) (:description error))))))

(defn token-handler
  "POST /oauth/token — Token endpoint (public clients, no client auth)."
  [deps]
  (fn [request]
    (let [params (-parse-token-params request)]
      (-> (case (:grant_type params)
            "authorization_code" (-authorization-code-grant deps params)
            "refresh_token" (-refresh-token-grant deps params)
            (-token-error "unsupported_grant_type"
                          "grant_type must be 'authorization_code' or 'refresh_token'"))
          ;; RFC 6749 §5.1: token responses must not be cached.
          (assoc-in [:headers "Cache-Control"] "no-store")
          (assoc-in [:headers "Pragma"] "no-cache")))))

;; ---------------------------------------------------------
;; Authorization Server Metadata (RFC 8414)

(defn- -make-metadata-handler
  [{:keys [auth-config]}]
  (fn [request]
    (let [issuer (public-url/base-url auth-config request)]
      (response/json 200 {:issuer issuer
                          :authorization_endpoint (str issuer "/oauth/authorize")
                          :token_endpoint (str issuer "/oauth/token")
                          :response_types_supported ["code"]
                          :response_modes_supported ["query"]
                          :grant_types_supported ["authorization_code" "refresh_token"]
                          :code_challenge_methods_supported ["S256"]
                          :token_endpoint_auth_methods_supported ["none"]
                          :scopes_supported scopes-supported
                          :client_id_metadata_document_supported true
                          :authorization_response_iss_parameter_supported true}))))

;; ---------------------------------------------------------
;; Routes

(defn authorize-routes
  "OAuth authorize routes (GET + consent POST). Live in page-routes for
   session access, CSRF protection, and Inertia rendering."
  [opts]
  ["/oauth"
   ["/authorize" {:get {:handler (-make-authorize-handler opts)}
                  :post {:handler (-make-decision-handler opts)}}]])

(defn token-routes
  "OAuth token route (POST). Lives in its own route group with API defaults
   (no CSRF, accepts JSON and form-encoded bodies)."
  [opts]
  ["/oauth"
   ["/token" {:post {:handler (token-handler opts)}}]])

(defn metadata-routes
  "Authorization server metadata (public, no auth)."
  [opts]
  ["/.well-known/oauth-authorization-server" {:get {:handler (-make-metadata-handler opts)}}])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Local agent (auto-approve):
  ;; /oauth/authorize?response_type=code&redirect_uri=http://localhost:8123/cb
  ;;   &code_challenge=...&code_challenge_method=S256&scope=read

  ;; MCP client (consent page):
  ;; /oauth/authorize?response_type=code&client_id=https://client.example/meta.json
  ;;   &redirect_uri=https://client.example/cb&code_challenge=...
  ;;   &code_challenge_method=S256&scope=read%20write&resource=https://o11y.example/mcp

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
