;; ---------------------------------------------------------
;; o11ylite.test-helpers.fake-oidc
;;
;; Ephemeral fake OIDC Identity Provider for integration tests.
;; Uses JDK's built-in HttpServer (zero dependencies).
;; Serves discovery, token exchange, and userinfo endpoints.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers.fake-oidc
  (:require
    [jsonista.core :as json])
  (:import
    [com.sun.net.httpserver HttpHandler HttpServer]
    [java.net InetSocketAddress]
    [java.util.concurrent Executors]))

;; ---------------------------------------------------------
;; Canned Responses

(def test-user
  {:sub "test-sub-123"
   :email "testuser@example.com"
   :name "Test User"})

(def ^:private token-response
  {:access_token "fake-access-token"
   :token_type "Bearer"
   :expires_in 3600
   :id_token "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ0ZXN0LXN1Yi0xMjMifQ."})

;; ---------------------------------------------------------
;; Discovery Document

(defn- -discovery-doc
  [base-url]
  {:issuer base-url
   :authorization_endpoint (str base-url "/authorize")
   :token_endpoint (str base-url "/token")
   :userinfo_endpoint (str base-url "/userinfo")
   :end_session_endpoint (str base-url "/logout")
   :jwks_uri (str base-url "/jwks")
   :response_types_supported ["code"]
   :grant_types_supported ["authorization_code"]
   :token_endpoint_auth_methods_supported ["client_secret_post" "none"]
   :code_challenge_methods_supported ["S256"]
   :scopes_supported ["openid" "email" "profile"]})

;; ---------------------------------------------------------
;; HTTP Handler

(defn- -form-decode
  "Minimal x-www-form-urlencoded decoder."
  [s]
  (when (seq s)
    (into {}
          (map (fn [pair]
                 (let [[k v] (clojure.string/split pair #"=" 2)]
                   [(java.net.URLDecoder/decode (or k "") "UTF-8")
                    (java.net.URLDecoder/decode (or v "") "UTF-8")])))
          (clojure.string/split s #"&"))))

(defn- -create-handler
  [base-url]
  (reify HttpHandler
    (handle
      [_ exchange]
      (let [path (.getPath (.getRequestURI exchange))
            send-json (fn [status body]
                        (let [bs (.getBytes ^String (json/write-value-as-string body) "UTF-8")]
                          (.add (.getResponseHeaders exchange) "Content-Type" "application/json")
                          (.sendResponseHeaders exchange status (alength bs))
                          (with-open [os (.getResponseBody exchange)]
                            (.write os bs))))
            send-empty (fn [status]
                         (.sendResponseHeaders exchange status -1))]
        (case path
          "/.well-known/openid-configuration"
          (send-json 200 (-discovery-doc base-url))

          "/token"
          (let [body-str (slurp (.getRequestBody exchange))
                params (-form-decode body-str)
                grant-type (get params "grant_type")]
            (if (= "authorization_code" grant-type)
              (send-json 200 token-response)
              (send-json 400 {:error "unsupported_grant_type"})))

          "/userinfo"
          (let [auth (-> exchange .getRequestHeaders (.getFirst "Authorization"))]
            (if (and auth (.startsWith ^String auth "Bearer "))
              (send-json 200 test-user)
              (send-empty 401)))

          "/logout"
          (send-empty 200)

          (send-empty 404))))))

;; ---------------------------------------------------------
;; Server Lifecycle

(defn start-server
  "Start a fake OIDC IdP on an ephemeral port.
   Returns {:server :port :base-url}."
  []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server (Executors/newFixedThreadPool 2))
    (let [port (.getPort (.getAddress server))
          base-url (str "http://127.0.0.1:" port)]
      (.createContext server "/" (-create-handler base-url))
      (.start server)
      {:server server :port port :base-url base-url})))

(defn stop-server
  "Stop the fake OIDC IdP."
  [{:keys [^HttpServer server]}]
  (.stop server 0))
