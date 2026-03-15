;; ---------------------------------------------------------
;; o11ylite.integration.oauth-test
;;
;; Integration tests for OAuth 2.0 Authorization Code + PKCE flow.
;; Open-mode tests use with-system fixture.
;; OIDC-mode tests wrap individually with with-oidc-system.
;; ---------------------------------------------------------

(ns o11ylite.integration.oauth-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [jsonista.core :as json]
    [o11ylite.test-helpers :as h]
    [o11ylite.test-helpers.fake-oidc :as fake-oidc]
    [oidc-client.core :as oidc]))

;; No use-fixtures — each deftest wraps explicitly to avoid
;; port conflicts. Tests are grouped into two deftest blocks
;; (open-mode + OIDC) to amortize system startup cost.

;; ---------------------------------------------------------
;; Helpers

(defn- -parse-redirect-params
  "Extract query params from a redirect Location header URL."
  [location]
  (when-let [query (.getQuery (java.net.URI. location))]
    (into {}
          (map (fn [pair]
                 (let [[k v] (str/split pair #"=" 2)]
                   [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))
          (str/split query #"&"))))

(defn- -extract-query-param
  "Extract a single query parameter value from a URL string."
  [url param-name]
  (get (-parse-redirect-params url) param-name))

(defn- -parse-json-body
  "Parse response body as JSON with keyword keys."
  [response]
  (json/read-value (:body response) json/keyword-keys-object-mapper))

(defn- -authorize!
  "Execute the authorize step: GET /oauth/authorize with PKCE params.
   Returns map with :response, :params, :verifier, :state, :redirect-uri."
  ([] (-authorize! {}))
  ([{:keys [scope verifier state redirect-port cookie]
     :or {scope "write"
          redirect-port 18899}}]
   (let [verifier (or verifier (oidc/random-pkce-code-verifier))
         challenge (oidc/pkce-code-challenge verifier)
         state (or state (oidc/random-state))
         redirect-uri (str "http://localhost:" redirect-port "/callback")
         path (str "/oauth/authorize?"
                   "response_type=code"
                   "&redirect_uri=" (java.net.URLEncoder/encode redirect-uri "UTF-8")
                   "&code_challenge=" challenge
                   "&code_challenge_method=S256"
                   "&scope=" scope
                   "&state=" state)
         response (h/no-redirect-get path
                                     (when cookie
                                       {:headers {"Cookie" cookie}}))]
     {:response response
      :location (h/header response "location")
      :params (-parse-redirect-params (or (h/header response "location") "http://x?"))
      :verifier verifier
      :challenge challenge
      :state state
      :redirect-uri redirect-uri})))

(defn- -exchange!
  "Exchange authorization code for access token via POST /oauth/token."
  [{:keys [code verifier redirect-uri]}]
  (h/post "/oauth/token"
          {:headers {"Content-Type" "application/json"}
           :body (json/write-value-as-string
                   {:grant_type "authorization_code"
                    :code code
                    :code_verifier verifier
                    :redirect_uri redirect-uri})}))

(defn- -session-cookie-header
  [session-value]
  (str "ring-session=" session-value))

(defn- -oidc-login!
  "Execute the full OIDC login flow starting from a login URL.
   Returns {:session <cookie>, :callback-location <redirect-target>}."
  [login-url]
  (let [login-resp (h/no-redirect-get login-url)
        login-session (h/extract-session-cookie login-resp)
        redirect-url (h/header login-resp "location")
        state (-extract-query-param redirect-url "state")
        callback-resp (h/no-redirect-get
                        (str "/auth/callback?code=test-code&state=" state)
                        {:headers {"Cookie" (-session-cookie-header login-session)}})]
    {:session (h/extract-session-cookie callback-resp)
     :callback-location (h/header callback-resp "location")}))

;; ==========================================================
;; Open Mode Tests
;; ==========================================================

(deftest open-mode-oauth-test
  (h/with-system
    (fn []
      (testing "authorize returns 302 redirect with code and state"
        (let [{:keys [response params state]} (-authorize!)]
          (is (= 302 (h/status response)))
          (is (some? (get params "code")))
          (is (= state (get params "state")))))

      (testing "full flow: authorize → exchange → use access token on API"
        (let [{:keys [params verifier redirect-uri]} (-authorize!)
              code (get params "code")
              token-resp (-exchange! {:code code
                                      :verifier verifier
                                      :redirect-uri redirect-uri})
              token-body (-parse-json-body token-resp)]
          (is (= 200 (:status token-resp)))
          (is (some? (:access_token token-body)))
          (is (= "Bearer" (:token_type token-body)))
          (is (= 3600 (:expires_in token-body)))
          (is (= "write" (:scope token-body)))

          ;; Use the access token to call an API endpoint
          (let [api-resp (h/get-json "/api/services"
                                     {:headers {"Authorization" (str "Bearer " (:access_token token-body))}})]
            (is (not= 401 (h/status api-resp))))))

      (testing "wrong code_verifier on exchange fails"
        (let [{:keys [params redirect-uri]} (-authorize!)
              code (get params "code")
              token-resp (-exchange! {:code code
                                      :verifier "wrong-verifier-that-wont-match"
                                      :redirect-uri redirect-uri})
              token-body (-parse-json-body token-resp)]
          (is (= 400 (:status token-resp)))
          (is (= "invalid_grant" (:error token-body)))))

      (testing "non-localhost redirect_uri is rejected with 400"
        (let [response (h/no-redirect-get
                         (str "/oauth/authorize?"
                              "response_type=code"
                              "&redirect_uri=" (java.net.URLEncoder/encode "https://evil.com/callback" "UTF-8")
                              "&code_challenge=abc123"
                              "&code_challenge_method=S256"
                              "&scope=write"))]
          (is (= 400 (h/status response)))))

      (testing "localhost redirect_uri is accepted"
        (let [{:keys [response]} (-authorize!)]
          (is (= 302 (h/status response)))))

      (testing "different redirect_uri on exchange is rejected"
        (let [{:keys [params verifier]} (-authorize! {:redirect-port 18899})
              code (get params "code")
              token-resp (-exchange! {:code code
                                      :verifier verifier
                                      :redirect-uri "http://localhost:19999/callback"})
              token-body (-parse-json-body token-resp)]
          (is (= 400 (:status token-resp)))
          (is (= "invalid_grant" (:error token-body)))
          (is (str/includes? (:error_description token-body) "redirect_uri"))))

      (testing "missing response_type returns error redirect"
        (let [response (h/no-redirect-get
                         (str "/oauth/authorize?"
                              "redirect_uri=" (java.net.URLEncoder/encode "http://localhost:18899/callback" "UTF-8")
                              "&code_challenge=abc123"
                              "&code_challenge_method=S256"))]
          (is (= 302 (h/status response)))
          (let [params (-parse-redirect-params (h/header response "location"))]
            (is (= "invalid_request" (get params "error"))))))

      (testing "invalid grant_type on token endpoint"
        (let [token-resp (h/post "/oauth/token"
                                 {:headers {"Content-Type" "application/json"}
                                  :body (json/write-value-as-string
                                          {:grant_type "client_credentials"
                                           :code "fake"
                                           :code_verifier "fake"
                                           :redirect_uri "http://localhost:18899/callback"})})
              token-body (-parse-json-body token-resp)]
          (is (= 400 (:status token-resp)))
          (is (= "unsupported_grant_type" (:error token-body)))))

      (testing "requested scope is reflected in access token response"
        (let [{:keys [params verifier redirect-uri]} (-authorize! {:scope "read"})
              code (get params "code")
              token-resp (-exchange! {:code code
                                      :verifier verifier
                                      :redirect-uri redirect-uri})
              token-body (-parse-json-body token-resp)]
          (is (= 200 (:status token-resp)))
          (is (= "read" (:scope token-body)))))

      (testing "invalid scope returns error redirect"
        (let [response (h/no-redirect-get
                         (str "/oauth/authorize?"
                              "response_type=code"
                              "&redirect_uri=" (java.net.URLEncoder/encode "http://localhost:18899/callback" "UTF-8")
                              "&code_challenge=abc123"
                              "&code_challenge_method=S256"
                              "&scope=superadmin"))]
          (is (= 302 (h/status response)))
          (let [params (-parse-redirect-params (h/header response "location"))]
            (is (= "invalid_request" (get params "error"))))))

      (testing "token endpoint accepts application/x-www-form-urlencoded"
        (let [{:keys [params verifier redirect-uri]} (-authorize!)
              code (get params "code")
              form-body (str "grant_type=authorization_code"
                             "&code=" (java.net.URLEncoder/encode code "UTF-8")
                             "&code_verifier=" (java.net.URLEncoder/encode verifier "UTF-8")
                             "&redirect_uri=" (java.net.URLEncoder/encode redirect-uri "UTF-8"))
              token-resp (h/post "/oauth/token"
                                 {:headers {"Content-Type" "application/x-www-form-urlencoded"}
                                  :body form-body})
              token-body (-parse-json-body token-resp)]
          (is (= 200 (:status token-resp)))
          (is (some? (:access_token token-body))))))))

;; ==========================================================
;; OIDC Mode Tests
;; ==========================================================

(deftest oidc-oauth-test
  (h/with-oidc-system
    (fn []
      (testing "unauthenticated authorize redirects to /auth/login with return_to"
        (let [{:keys [response]} (-authorize!)]
          (is (= 302 (h/status response)))
          (let [location (h/header response "location")]
            (is (str/includes? location "/auth/login"))
            (is (str/includes? location "return_to=")))))

      (testing "full OIDC flow: authorize → login → resume → code → exchange → API"
        (let [verifier (oidc/random-pkce-code-verifier)
              challenge (oidc/pkce-code-challenge verifier)
              state (oidc/random-state)
              redirect-uri "http://localhost:18899/callback"

              ;; Step 1: Hit authorize — redirected to login
              {:keys [response]} (-authorize! {:verifier verifier :state state})
              login-url (h/header response "location")

              ;; Step 2: Complete OIDC login
              {:keys [session callback-location]} (-oidc-login! login-url)

              ;; Step 3: Resume authorize with session
              resume-resp (h/no-redirect-get callback-location
                                             {:headers {"Cookie" (-session-cookie-header session)}})
              code-params (-parse-redirect-params (or (h/header resume-resp "location") "http://x?"))]

          (is (= 302 (h/status resume-resp)))
          (is (some? (get code-params "code")))
          (is (= state (get code-params "state")))

          ;; Step 4: Exchange code for access token
          (let [token-resp (-exchange! {:code (get code-params "code")
                                        :verifier verifier
                                        :redirect-uri redirect-uri})
                token-body (-parse-json-body token-resp)]
            (is (= 200 (:status token-resp)))
            (is (some? (:access_token token-body)))
            (is (= "write" (:scope token-body)))

            ;; Step 5: Use token on API
            (let [api-resp (h/get-json "/api/services"
                                       {:headers {"Authorization" (str "Bearer " (:access_token token-body))}})]
              (is (not= 401 (h/status api-resp)))))))

      (testing "authorization code JWT cannot be used as Bearer access token"
        (let [{:keys [response]} (-authorize!)
              login-url (h/header response "location")
              {:keys [session callback-location]} (-oidc-login! login-url)
              resume-resp (h/no-redirect-get callback-location
                                             {:headers {"Cookie" (-session-cookie-header session)}})
              code-params (-parse-redirect-params (or (h/header resume-resp "location") "http://x?"))
              code (get code-params "code")]
          (let [api-resp (h/get-json "/api/services"
                                     {:headers {"Authorization" (str "Bearer " code)}})]
            (is (= 401 (h/status api-resp)))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.integration.oauth-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
