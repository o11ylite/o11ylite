;; ---------------------------------------------------------
;; o11ylite.oauth.authorization-test
;;
;; Unit tests for authorization server building blocks: scope
;; resolution, canonical resource URIs, Client ID Metadata Document
;; validation, the SSRF guard, and single-use codes.
;; ---------------------------------------------------------

(ns o11ylite.oauth.authorization-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.auth.public-url :as public-url]
    [o11ylite.auth.scope :as scope]
    [o11ylite.oauth :as oauth]
    [o11ylite.oauth.client-metadata :as client-metadata])
  (:import
    [java.net InetAddress]))

;; ---------------------------------------------------------
;; Helpers

(def ^:private client-id "https://client.example.com/oauth/metadata.json")

(def ^:private valid-doc
  {"client_id" client-id
   "client_name" "Example Client"
   "redirect_uris" ["https://client.example.com/callback" "http://127.0.0.1:8080/cb"]
   "token_endpoint_auth_method" "none"})

(defn- -rejects?
  [doc]
  (try (client-metadata/validate-document client-id doc) false
       (catch clojure.lang.ExceptionInfo _ true)))

;; ---------------------------------------------------------
;; Tests

(deftest resolve-requested-scope-test
  (is (= "read" (scope/resolve-requested "read")))
  (is (= "write" (scope/resolve-requested "read write")))
  (is (= "write" (scope/resolve-requested "  write  ")))
  (is (= "write" (scope/resolve-requested "read ingest")))
  (is (= "admin" (scope/resolve-requested "admin read")))
  (is (nil? (scope/resolve-requested "read superadmin")))
  (is (nil? (scope/resolve-requested ""))))

(deftest canonical-uri-test
  (is (= "https://example.com/mcp" (public-url/canonical-uri "HTTPS://Example.COM/mcp/")))
  (is (= "http://localhost:3000" (public-url/canonical-uri "http://localhost:3000/")))
  (is (nil? (public-url/canonical-uri "example.com/mcp")))
  (is (nil? (public-url/canonical-uri "https://example.com/mcp#frag")))
  (is (nil? (public-url/canonical-uri "ftp://example.com"))))

(deftest client-metadata-document-test
  (testing "a valid document is normalized"
    (is (= {:client-id client-id
            :client-name "Example Client"
            :client-uri nil
            :redirect-uris ["https://client.example.com/callback" "http://127.0.0.1:8080/cb"]}
           (client-metadata/validate-document client-id valid-doc))))

  (testing "invalid documents are rejected"
    (is (-rejects? (assoc valid-doc "client_id" "https://other.example.com/meta.json")))
    (is (-rejects? (dissoc valid-doc "client_name")))
    (is (-rejects? (assoc valid-doc "redirect_uris" [])))
    (is (-rejects? (assoc valid-doc "redirect_uris" ["http://client.example.com/cb"])))
    (is (-rejects? (assoc valid-doc "token_endpoint_auth_method" "private_key_jwt")))
    (is (-rejects? ["not" "an" "object"])))

  (testing "client_id URL shape"
    (is (client-metadata/cimd-client-id? client-id))
    (is (not (client-metadata/cimd-client-id? "my-cli")))
    (doseq [bad ["https://client.example.com" "https://client.example.com/"
                 "https://user:pw@client.example.com/m.json" "https://client.example.com/a/../m.json"]]
      (is (thrown? clojure.lang.ExceptionInfo
            (client-metadata/resolve-client (atom {}) bad))
          bad)))

  (testing "localhost redirect detection"
    (is (client-metadata/localhost-redirect? "http://localhost:1234/cb"))
    (is (client-metadata/localhost-redirect? "http://127.0.0.1/cb"))
    (is (not (client-metadata/localhost-redirect? "https://localhost.evil.com/cb")))))

(deftest ssrf-guard-test
  (doseq [ip ["127.0.0.1" "10.1.2.3" "172.16.0.1" "192.168.1.1" "169.254.169.254"
              "100.64.0.1" "0.0.0.0" "::1" "fd00::1" "fe80::1" "224.0.0.1"]]
    (is (client-metadata/blocked-address? (InetAddress/getByName ip)) ip))
  (doseq [ip ["1.1.1.1" "100.128.0.1" "2606:4700:4700::1111"]]
    (is (not (client-metadata/blocked-address? (InetAddress/getByName ip))) ip)))

(deftest consume-code-test
  (let [used (atom {})
        future-exp (+ (System/currentTimeMillis) 60000)]
    (is (true? (oauth/consume-code! used {:jti "a" :exp-ms future-exp})))
    (is (false? (oauth/consume-code! used {:jti "a" :exp-ms future-exp})))
    (is (true? (oauth/consume-code! used {:jti "b" :exp-ms future-exp})))
    (testing "codes without jti are never accepted"
      (is (false? (oauth/consume-code! used {:jti nil :exp-ms future-exp}))))
    (testing "expired entries are pruned"
      (swap! used assoc "old" 1)
      (oauth/consume-code! used {:jti "c" :exp-ms future-exp})
      (is (not (contains? @used "old"))))))

(deftest access-token-audience-test
  (let [k (oauth/derive-signing-key (.getBytes "0123456789abcdef" "UTF-8"))
        mcp-token (oauth/sign-access-token k {:sub "u" :scope "read" :audience "https://x/mcp"})
        api-token (oauth/sign-access-token k {:sub "u" :scope "read"})]
    (is (some? (oauth/verify-access-token k mcp-token "https://x/mcp")))
    (is (nil? (oauth/verify-access-token k mcp-token "https://y/mcp")))
    (is (nil? (oauth/verify-access-token k mcp-token nil)))
    (is (some? (oauth/verify-access-token k api-token nil)))
    (is (nil? (oauth/verify-access-token k api-token "https://x/mcp")))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.oauth.authorization-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
