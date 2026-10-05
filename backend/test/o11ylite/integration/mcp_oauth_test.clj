;; ---------------------------------------------------------
;; o11ylite.integration.mcp-oauth-test
;;
;; End-to-end MCP authorization in OIDC mode: 401 challenge →
;; Client ID Metadata Document client → consent page → code (+ iss) →
;; audience-bound access token → MCP calls → refresh rotation.
;; Also covers scopes, API keys, and token audience separation.
;; ---------------------------------------------------------

(ns o11ylite.integration.mcp-oauth-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [jsonista.core :as json]
    [o11ylite.api-key :as api-key]
    [o11ylite.api-key.crypto :as crypto]
    [o11ylite.components.api-key-cache :as api-key-cache]
    [o11ylite.oauth :as oauth]
    [o11ylite.oauth.client-metadata :as client-metadata]
    [o11ylite.test-helpers :as h]
    [o11ylite.test-helpers.mcp :as mcp]
    [oidc-client.core :as oidc]
    [ring.util.codec :as codec]))

;; ---------------------------------------------------------
;; Helpers

(def ^:private base "http://localhost:3333")
(def ^:private resource (str base "/mcp"))
(def ^:private client-id "https://client.example.com/oauth/metadata.json")
(def ^:private redirect-uri "https://client.example.com/callback")

(def ^:private client-document
  {"client_id" client-id
   "client_name" "Example MCP Client"
   "redirect_uris" [redirect-uri]
   "token_endpoint_auth_method" "none"})

(defn- -fake-fetch
  "Stand-in for the network fetch of the client metadata document."
  [uri]
  (if (= client-id (str uri))
    client-document
    (throw (ex-info "unexpected fetch" {:type ::client-metadata/fetch-failed}))))

(defn- -query-params
  [url]
  (some-> (java.net.URI. url) .getRawQuery codec/form-decode))

(defn- -json
  [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

(defn- -cookie
  [response cookie-name]
  (let [cookies (get-in response [:headers "set-cookie"])
        cookies (if (string? cookies) [cookies] cookies)]
    (some #(second (re-find (re-pattern (str cookie-name "=([^;]+)")) %)) cookies)))

(defn- -login!
  "Complete the fake OIDC login started by a redirect to /auth/login.
   Returns {:session cookie :return-to url}."
  [login-url]
  (let [login-resp (h/no-redirect-get login-url)
        state (get (-query-params (h/header login-resp "location")) "state")
        callback (h/no-redirect-get (str "/auth/callback?code=test-code&state=" state)
                                    {:headers {"Cookie" (str "ring-session=" (h/extract-session-cookie login-resp))}})]
    {:session (h/extract-session-cookie callback)
     :return-to (h/header callback "location")}))

(defn- -authorize-path
  [{:keys [challenge scope state] :as opts}]
  (str "/oauth/authorize?"
       (codec/form-encode (cond-> {"response_type" "code"
                                   "client_id" (get opts :client-id client-id)
                                   "redirect_uri" (get opts :redirect-uri redirect-uri)
                                   "code_challenge" challenge
                                   "code_challenge_method" "S256"
                                   "scope" scope
                                   "state" state}
                            (get opts :resource resource) (assoc "resource" (get opts :resource resource))))))

(defn- -consent!
  "Open the consent page (Inertia JSON) with a session.
   Returns {:page page-data :session cookie :csrf token}."
  [path session]
  (let [resp (h/no-redirect-get path {:headers (merge (h/inertia-headers {})
                                                      {"Cookie" (str "ring-session=" session)})})]
    {:status (h/status resp)
     :page (when (= 200 (h/status resp)) (-json resp))
     :session (or (h/extract-session-cookie resp) session)
     :csrf (some-> (-cookie resp "XSRF-TOKEN") (java.net.URLDecoder/decode "UTF-8"))}))

(defn- -decide!
  "Submit the consent decision the way the Inertia page does.
   Returns the external redirect target (X-Inertia-Location)."
  [{:keys [page session csrf]} decision]
  (let [resp (h/no-redirect-post "/oauth/authorize"
                                 {:headers (merge (h/inertia-headers {})
                                                  {"Content-Type" "application/json"
                                                   "X-XSRF-TOKEN" csrf
                                                   "Cookie" (str "ring-session=" session)})
                                  :body (json/write-value-as-string
                                          (assoc (get-in page [:props :authorization_params])
                                                 :decision decision))})]
    (is (= 409 (h/status resp)) "Inertia external redirect")
    (h/header resp "x-inertia-location")))

(defn- -token!
  [params]
  (let [resp (h/post "/oauth/token" {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                                     :body (codec/form-encode params)})]
    (assoc resp :json (-json resp))))

(defn- -authorize-and-exchange!
  "Run the full CIMD flow for a scope. Returns the token response JSON."
  [session scope]
  (let [verifier (oidc/random-pkce-code-verifier)
        consent (-consent! (-authorize-path {:challenge (oidc/pkce-code-challenge verifier)
                                             :scope scope :state "s"})
                           session)
        code (get (-query-params (-decide! consent "approve")) "code")]
    (:json (-token! {"grant_type" "authorization_code" "code" code "code_verifier" verifier
                     "redirect_uri" redirect-uri "client_id" client-id "resource" resource}))))

(defn- -create-key!
  [scope]
  (let [key-data (crypto/generate-key)]
    (api-key/create! (:db/sqlite h/*system*) {:id (str "k-" scope) :name scope
                                              :prefix (:prefix key-data)
                                              :key-hash (:key-hash key-data)
                                              :scope scope})
    (api-key-cache/refresh! (:auth/api-key-cache h/*system*))
    (:key key-data)))

;; ---------------------------------------------------------
;; Tests

(deftest mcp-oauth-flow-test
  (h/with-oidc-system
    (fn []
      (with-redefs [client-metadata/-fetch-document -fake-fetch]
        (testing "unauthenticated MCP requests get a 401 pointing at resource metadata"
          (let [resp (mcp/request "tools/list" {})
                challenge (h/header resp "www-authenticate")]
            (is (= 401 (h/status resp)))
            (is (str/starts-with? challenge "Bearer "))
            (is (str/includes? challenge
                               (str "resource_metadata=\"" base "/.well-known/oauth-protected-resource/mcp\"")))
            (is (str/includes? challenge "scope=\"read write\""))))

        (let [verifier (oidc/random-pkce-code-verifier)
              challenge (oidc/pkce-code-challenge verifier)
              path (-authorize-path {:challenge challenge :scope "read write" :state "xyz"})
              ;; Not logged in yet: authorize redirects to login, then back.
              login-redirect (h/no-redirect-get path)
              _ (is (str/includes? (h/header login-redirect "location") "/auth/login?return_to="))
              {:keys [session return-to]} (-login! (h/header login-redirect "location"))
              consent (-consent! return-to session)]

          (testing "the consent page shows who is asking and where the code goes"
            (is (= 200 (:status consent)))
            (is (= "OAuthConsent" (get-in consent [:page :component])))
            (is (= {:name "Example MCP Client" :id client-id :host "client.example.com" :uri nil}
                   (get-in consent [:page :props :client])))
            (is (= "client.example.com" (get-in consent [:page :props :redirect_host])))
            (is (false? (get-in consent [:page :props :localhost_redirect])))
            (is (= "write" (get-in consent [:page :props :scope]))))

          (let [location (-decide! consent "approve")
                params (-query-params location)
                code (get params "code")
                exchange {"grant_type" "authorization_code" "code" code "code_verifier" verifier
                          "redirect_uri" redirect-uri "client_id" client-id "resource" resource}]
            (testing "approval redirects to the client with code, state, and iss"
              (is (str/starts-with? location redirect-uri))
              (is (= "xyz" (get params "state")))
              (is (= base (get params "iss"))))

            (testing "token exchange checks client_id and resource"
              (is (= "invalid_grant" (get-in (-token! (assoc exchange "client_id" "https://other.example/m.json"))
                                             [:json :error])))
              (is (= "invalid_target" (get-in (-token! (assoc exchange "resource" "http://localhost:3333/other"))
                                              [:json :error]))))

            (let [{:keys [json] :as token-resp} (-token! exchange)
                  access-token (:access_token json)
                  refresh-token (:refresh_token json)]
              (testing "token response"
                (is (= 200 (h/status token-resp)))
                (is (= "no-store" (h/header token-resp "cache-control")))
                (is (= "write" (:scope json)))
                (is (str/starts-with? refresh-token "o11yrt_")))

              (testing "authorization codes are single-use"
                (is (= "invalid_grant" (get-in (-token! exchange) [:json :error]))))

              (testing "the token works for MCP but not for /api (audience binding)"
                (is (= 200 (h/status (mcp/request "tools/list" {} {:token access-token}))))
                (is (= 200 (h/status (mcp/call-tool "list_services" {} {:token access-token}))))
                (is (= 401 (h/status (h/get-json "/api/services"
                                                 {:headers {"Authorization" (str "Bearer " access-token)}})))))

              (testing "refresh tokens rotate; reusing an old one revokes the session"
                (let [refreshed (:json (-token! {"grant_type" "refresh_token"
                                                 "refresh_token" refresh-token
                                                 "client_id" client-id}))]
                  (is (some? (:access_token refreshed)))
                  (is (not= refresh-token (:refresh_token refreshed)))
                  (is (= 200 (h/status (mcp/request "tools/list" {} {:token (:access_token refreshed)}))))

                  (testing "a mismatched client_id does not consume the token"
                    (is (= "invalid_grant" (get-in (-token! {"grant_type" "refresh_token"
                                                             "refresh_token" (:refresh_token refreshed)
                                                             "client_id" "https://evil.example/m.json"})
                                                   [:json :error]))))

                  (testing "narrowing scope on refresh"
                    (let [narrowed (:json (-token! {"grant_type" "refresh_token"
                                                    "refresh_token" (:refresh_token refreshed)
                                                    "client_id" client-id
                                                    "scope" "read"}))]
                      (is (= "read" (:scope narrowed)))
                      (is (= "invalid_grant" (get-in (-token! {"grant_type" "refresh_token"
                                                               "refresh_token" refresh-token
                                                               "client_id" client-id})
                                                     [:json :error])))
                      (testing "the whole family is revoked after reuse"
                        (is (= "invalid_grant" (get-in (-token! {"grant_type" "refresh_token"
                                                                 "refresh_token" (:refresh_token narrowed)
                                                                 "client_id" client-id})
                                                       [:json :error]))))))))))

          (testing "a read-only token cannot call write tools"
            (let [{:keys [access_token]} (-authorize-and-exchange! session "read")
                  resp (mcp/call-tool "create_notebook" {:name "x"} {:token access_token})]
              (is (= 200 (h/status (mcp/call-tool "list_alert_rules" {} {:token access_token}))))
              (is (= 403 (h/status resp)))
              (is (str/includes? (h/header resp "www-authenticate") "error=\"insufficient_scope\""))
              (is (str/includes? (h/header resp "www-authenticate") "scope=\"write\""))))

          (testing "deny redirects with access_denied"
            (let [consent (-consent! (-authorize-path {:challenge challenge :scope "read" :state "d"}) session)
                  params (-query-params (-decide! consent "deny"))]
              (is (= "access_denied" (get params "error")))
              (is (= base (get params "iss")))))

          (testing "requests that cannot be trusted are refused without redirecting"
            (let [resp (h/no-redirect-get (-authorize-path {:challenge challenge :scope "read"
                                                            :redirect-uri "https://evil.example/cb"})
                                          {:headers {"Cookie" (str "ring-session=" session)}})]
              (is (= 400 (h/status resp)))
              (is (= "invalid_request" (:error (-json resp)))))
            (let [resp (h/no-redirect-get (-authorize-path {:challenge challenge :scope "read"
                                                            :client-id "https://unknown.example/m.json"})
                                          {:headers {"Cookie" (str "ring-session=" session)}})]
              (is (= 400 (h/status resp)))
              (is (= "invalid_client" (:error (-json resp))))))

          (testing "an unknown resource is redirected back as invalid_target"
            (let [resp (h/no-redirect-get (-authorize-path {:challenge challenge :scope "read"
                                                            :resource "https://elsewhere.example/mcp"})
                                          {:headers {"Cookie" (str "ring-session=" session)}})]
              (is (= "invalid_target" (get (-query-params (h/header resp "location")) "error")))))

          (testing "consent POST requires the CSRF token"
            (let [consent (-consent! (-authorize-path {:challenge challenge :scope "read"}) session)
                  resp (h/no-redirect-post "/oauth/authorize"
                                           {:headers {"Content-Type" "application/json"
                                                      "Cookie" (str "ring-session=" (:session consent))}
                                            :body (json/write-value-as-string
                                                    (assoc (get-in consent [:page :props :authorization_params])
                                                           :decision "approve"))})]
              (is (= 403 (h/status resp))))))

        (testing "login only returns to same-site paths (no open redirect)"
          (is (= "/" (:return-to (-login! "/auth/login?return_to=https%3A%2F%2Fevil.example%2F"))))
          (is (= "/" (:return-to (-login! "/auth/login?return_to=%2F%2Fevil.example")))))

        (testing "legacy agent tokens (no resource) are rejected by MCP"
          (let [token (oauth/sign-access-token (get-in h/*system* [:auth/config :jwt-signing-key])
                                               {:sub "agent" :scope "write"})
                resp (mcp/request "tools/list" {} {:token token})]
            (is (= 401 (h/status resp)))
            (is (str/includes? (h/header resp "www-authenticate") "error=\"invalid_token\""))))

        (testing "API keys authenticate MCP requests by scope"
          (let [read-key (-create-key! "read")
                ingest-key (-create-key! "ingest")]
            (is (= 200 (h/status (mcp/call-tool "list_services" {} {:token read-key}))))
            (is (= 403 (h/status (mcp/call-tool "save_alert_rule" {} {:token read-key}))))
            (is (= 403 (h/status (mcp/request "tools/list" {} {:token ingest-key}))))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.mcp-oauth-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
