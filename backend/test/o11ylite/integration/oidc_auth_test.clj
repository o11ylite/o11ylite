;; ---------------------------------------------------------
;; o11ylite.integration.oidc-auth-test
;;
;; Integration tests for OIDC authentication flow.
;; Uses a fake OIDC IdP server to test login, callback,
;; session persistence, and logout.
;; ---------------------------------------------------------

(ns o11ylite.integration.oidc-auth-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [o11ylite.test-helpers :as h]
    [o11ylite.test-helpers.fake-oidc :as fake-oidc]))

(use-fixtures :each h/with-oidc-system)

;; ---------------------------------------------------------
;; Helpers

(defn- -extract-query-param
  "Extract a query parameter value from a URL string."
  [url param-name]
  (when-let [query (.getQuery (java.net.URI. url))]
    (some (fn [pair]
            (let [[k v] (clojure.string/split pair #"=" 2)]
              (when (= k param-name)
                (java.net.URLDecoder/decode (or v "") "UTF-8"))))
          (clojure.string/split query #"&"))))

(defn- -session-cookie-header
  "Build a Cookie header from a ring-session value."
  [session-value]
  (str "ring-session=" session-value))

(defn- -extract-csrf-token
  "Extract XSRF-TOKEN from Set-Cookie header."
  [response]
  (let [cookies (get-in response [:headers "set-cookie"])]
    (when cookies
      (let [cookie-str (if (coll? cookies)
                         (first (filter #(.contains ^String % "XSRF-TOKEN") cookies))
                         cookies)]
        (when (and cookie-str (.contains ^String cookie-str "XSRF-TOKEN"))
          (java.net.URLDecoder/decode
            (second (re-find #"XSRF-TOKEN=([^;]+)" cookie-str)) "UTF-8"))))))

(defn- -login!
  "Execute the full OIDC login flow. Returns the session cookie
   from the successful callback, or nil on failure.

   Optionally accepts a login-url to initiate from (e.g., with return_to param).

   Steps:
   1. GET /auth/login → redirect to IdP with state in session
   2. GET /auth/callback?code=test-code&state=<state> → redirect to /
   3. Return {:session <cookie>, :callback-location <redirect-target>}"
  ([] (-login! "/auth/login"))
  ([login-url]
   (let [;; Step 1: initiate login
         login-resp (h/no-redirect-get login-url)
         login-session (h/extract-session-cookie login-resp)
         redirect-url (h/header login-resp "location")
         state (-extract-query-param redirect-url "state")

         ;; Step 2: simulate IdP redirect back with auth code
         callback-resp (h/no-redirect-get
                         (str "/auth/callback?code=test-code&state=" state)
                         {:headers {"Cookie" (-session-cookie-header login-session)}})]
     {:session (h/extract-session-cookie callback-resp)
      :callback-location (h/header callback-resp "location")})))

;; ---------------------------------------------------------
;; Tests

(deftest oidc-login-flow-test
  (testing "Unauthenticated page request redirects to /auth/login"
    (let [response (h/no-redirect-get "/")]
      (is (= 302 (h/status response)))
      (is (.contains ^String (h/header response "location") "/auth/login"))))

  (testing "Unauthenticated API request returns 401"
    (let [response (h/get-json "/api/services")]
      (is (= 401 (h/status response)))))

  (testing "Health endpoint remains accessible without auth"
    (let [response (h/get-json "/api/status")]
      (is (= 200 (h/status response)))))

  (testing "Login redirects to IdP authorization endpoint"
    (let [response (h/no-redirect-get "/auth/login")]
      (is (= 302 (h/status response)))
      (let [location (h/header response "location")]
        (is (.contains ^String location "/authorize"))
        (is (.contains ^String location "response_type=code"))
        (is (.contains ^String location "client_id=test-client"))
        (is (.contains ^String location "code_challenge_method=S256"))
        (is (= (str "http://localhost:" h/test-http-port "/auth/callback")
               (-extract-query-param location "redirect_uri"))
            "redirect_uri should be derived from Host header")
        (is (some? (h/extract-session-cookie response))))))

  (testing "Login redirect_uri uses X-Forwarded-Host when present"
    (let [response (h/no-redirect-get "/auth/login"
                                      {:headers {"X-Forwarded-Host" "app.example.com"
                                                 "X-Forwarded-Proto" "https"}})
          location (h/header response "location")]
      (is (= "https://app.example.com/auth/callback"
             (-extract-query-param location "redirect_uri"))
          "redirect_uri should prefer X-Forwarded-Host over Host")))

  (testing "Callback with valid state completes login"
    (let [{:keys [session callback-location]} (-login!)]
      (is (some? session))
      (is (= "/" callback-location))

      ;; Authenticated page access should not redirect to login
      (let [response (h/no-redirect-get "/"
                                        {:headers {"Cookie" (-session-cookie-header session)}})]
        (is (not (.contains ^String (or (h/header response "location") "")
                            "/auth/login"))))))

  (testing "Callback with wrong state returns 400"
    (let [login-resp (h/no-redirect-get "/auth/login")
          login-session (h/extract-session-cookie login-resp)
          callback-resp (h/no-redirect-get
                          "/auth/callback?code=test-code&state=wrong-state"
                          {:headers {"Cookie" (-session-cookie-header login-session)}})]
      (is (= 400 (h/status callback-resp))))))

(deftest oidc-return-to-test
  (testing "Unauthenticated page request includes return_to in login redirect"
    (let [response (h/no-redirect-get "/explore?query=foo")]
      (is (= 302 (h/status response)))
      (let [location (h/header response "location")]
        (is (.contains ^String location "/auth/login"))
        (is (.contains ^String location "return_to=")))))

  (testing "Login flow preserves return_to through to callback redirect"
    (let [{:keys [callback-location]} (-login! "/auth/login?return_to=/explore?query=foo")]
      (is (= "/explore?query=foo" callback-location))))

  (testing "Login without return_to redirects to /"
    (let [{:keys [callback-location]} (-login!)]
      (is (= "/" callback-location)))))

(deftest oidc-session-test
  (let [{:keys [session]} (-login!)]
    (testing "Authenticated API request succeeds with session cookie"
      (let [response (h/get-json "/api/services"
                                 {:headers {"Cookie" (-session-cookie-header session)}})]
        ;; Should not be 401 — the identity middleware resolves the session
        (is (not= 401 (h/status response)))))

    (testing "Logout clears session"
      ;; Need CSRF token from an authenticated page request
      (let [page-resp (h/no-redirect-get "/"
                                         {:headers {"Cookie" (-session-cookie-header session)}})
            csrf-token (-extract-csrf-token page-resp)
            ;; Also need the updated session cookie (may have changed)
            updated-session (or (h/extract-session-cookie page-resp) session)
            cookie-header (str "ring-session=" updated-session "; XSRF-TOKEN=" csrf-token)
            logout-resp (h/no-redirect-post "/auth/logout"
                                            {:headers {"Cookie" cookie-header
                                                       "X-XSRF-TOKEN" csrf-token}})]
        (is (= 302 (h/status logout-resp)))
        ;; After logout, page requests should redirect to login
        (let [post-logout-resp (h/no-redirect-get "/")]
          (is (= 302 (h/status post-logout-resp)))
          (is (.contains ^String (h/header post-logout-resp "location") "/auth/login")))))))
