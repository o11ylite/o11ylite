;; ---------------------------------------------------------
;; o11ylite.oauth-test
;;
;; Unit tests for OAuth JWT sign/verify and PKCE verification.
;; No system needed — tests pure functions in isolation.
;; ---------------------------------------------------------

(ns o11ylite.oauth-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [o11ylite.oauth :as oauth]
    [oidc-client.core :as oidc]))

;; ---------------------------------------------------------
;; Helpers

(defn- -test-signing-key
  "Derive a signing key from a test secret."
  []
  (oauth/derive-signing-key (.getBytes "0123456789abcdef" "UTF-8")))

(defn- -other-signing-key
  "Derive a signing key from a different secret."
  []
  (oauth/derive-signing-key (.getBytes "fedcba9876543210" "UTF-8")))

;; ---------------------------------------------------------
;; Access Token Tests

(deftest access-token-round-trip-test
  (let [key (-test-signing-key)
        token (oauth/sign-access-token key {:sub "user-123" :scope "write"})
        claims (oauth/verify key token "access")]
    (testing "sign and verify produces valid claims"
      (is (some? claims))
      (is (= "user-123" (:sub claims)))
      (is (= "write" (:scope claims)))
      (is (= "access" (:type claims))))))

(deftest access-token-different-key-test
  (let [key1 (-test-signing-key)
        key2 (-other-signing-key)
        token (oauth/sign-access-token key1 {:sub "user" :scope "write"})]
    (testing "different signing key rejects token"
      (is (nil? (oauth/verify key2 token "access"))))))

(deftest access-token-type-enforcement-test
  (let [key (-test-signing-key)
        access-token (oauth/sign-access-token key {:sub "user" :scope "write"})
        code-token (oauth/sign-authorization-code key {:sub "user"
                                                       :scope "write"
                                                       :code-challenge "test-challenge"
                                                       :redirect-uri "http://localhost:8899/callback"})]
    (testing "access token cannot be verified as code"
      (is (nil? (oauth/verify key access-token "code"))))

    (testing "authorization code cannot be verified as access token"
      (is (nil? (oauth/verify key code-token "access"))))))

(deftest access-token-tampered-test
  (let [key (-test-signing-key)
        token (oauth/sign-access-token key {:sub "user" :scope "write"})
        ;; Tamper with the token by changing a character in the signature
        tampered (str (subs token 0 (dec (count token))) "X")]
    (testing "tampered token is rejected"
      (is (nil? (oauth/verify key tampered "access"))))))

(deftest access-token-garbage-test
  (let [key (-test-signing-key)]
    (testing "garbage string is rejected"
      (is (nil? (oauth/verify key "not-a-jwt" "access"))))

    (testing "empty string is rejected"
      (is (nil? (oauth/verify key "" "access"))))))

;; ---------------------------------------------------------
;; Authorization Code Tests

(deftest authorization-code-round-trip-test
  (let [key (-test-signing-key)
        token (oauth/sign-authorization-code key {:sub "user-456"
                                                  :scope "read"
                                                  :code-challenge "abc123"
                                                  :redirect-uri "http://localhost:9999/cb"})
        claims (oauth/verify key token "code")]
    (testing "sign and verify produces valid claims"
      (is (some? claims))
      (is (= "user-456" (:sub claims)))
      (is (= "read" (:scope claims)))
      (is (= "code" (:type claims)))
      (is (= "abc123" (:code_challenge claims)))
      (is (= "http://localhost:9999/cb" (:redirect_uri claims))))))

;; ---------------------------------------------------------
;; PKCE Verification Tests

(deftest verify-pkce-correct-verifier-test
  (testing "correct verifier passes PKCE check"
    ;; Known test vector: verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    ;; SHA256 -> base64url = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    (is (oauth/verify-pkce
          "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
          "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"))))

(deftest verify-pkce-wrong-verifier-test
  (testing "wrong verifier fails PKCE check"
    (is (not (oauth/verify-pkce
               "wrong-verifier"
               "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")))))

(deftest verify-pkce-with-oidc-client-test
  (testing "PKCE round-trip using oidc-client.core utilities"
    (let [verifier (oidc/random-pkce-code-verifier)
          challenge (oidc/pkce-code-challenge verifier)]
      (is (oauth/verify-pkce verifier challenge)))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[clojure.test :refer [run-tests]])
  (run-tests 'o11ylite.oauth-test)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
