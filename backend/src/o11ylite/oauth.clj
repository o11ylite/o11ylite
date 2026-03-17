;; ---------------------------------------------------------
;; o11ylite.oauth
;;
;; PKCE verification and JWT sign/verify for OAuth agent auth.
;; Authorization codes and access tokens are both stateless JWTs
;; signed with HMAC256. Signing key derived from the session secret.
;; ---------------------------------------------------------

(ns o11ylite.oauth
  (:import
    [com.auth0.jwt JWT]
    [com.auth0.jwt.algorithms Algorithm]
    [com.auth0.jwt.exceptions JWTVerificationException]
    [java.security MessageDigest]
    [java.time Instant]
    [java.util Base64]
    [javax.crypto Mac]
    [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------
;; Signing Key Derivation

(defn derive-signing-key
  "Derive a JWT signing key from session secret bytes via HMAC-SHA256
   with a fixed salt. Returns a byte array suitable for HMAC256."
  [^bytes session-key]
  (let [mac (Mac/getInstance "HmacSHA256")
        key-spec (SecretKeySpec. session-key "HmacSHA256")]
    (.init mac key-spec)
    (.doFinal mac (.getBytes "o11ylite-jwt-signing" "UTF-8"))))

;; ---------------------------------------------------------
;; PKCE Verification

(defn verify-pkce
  "Verify PKCE S256 challenge. Returns true if
   BASE64URL(SHA256(code_verifier)) == code_challenge."
  [code-verifier code-challenge]
  (let [digest (MessageDigest/getInstance "SHA-256")
        hash-bytes (.digest digest (.getBytes ^String code-verifier "ASCII"))
        encoder (.withoutPadding (Base64/getUrlEncoder))
        computed (.encodeToString encoder hash-bytes)]
    (= computed code-challenge)))

;; ---------------------------------------------------------
;; JWT Signing

(def ^:private issuer "o11ylite")
(def ^:private access-token-ttl-seconds 3600)     ; 1 hour
(def ^:private auth-code-ttl-seconds 300)          ; 5 minutes

(defn- -algorithm
  "Create HMAC256 algorithm from derived key bytes."
  [^bytes signing-key]
  (Algorithm/HMAC256 signing-key))

(defn sign-access-token
  "Sign an access token JWT with claims {sub, scope, type: \"access\"}.
   TTL: 1 hour."
  [signing-key {:keys [sub scope]}]
  (let [now (Instant/now)
        exp (.plusSeconds now access-token-ttl-seconds)]
    (-> (JWT/create)
        (.withIssuer issuer)
        (.withClaim "type" "access")
        (.withClaim "sub" ^String sub)
        (.withClaim "scope" ^String scope)
        (.withIssuedAt now)
        (.withExpiresAt exp)
        (.sign (-algorithm signing-key)))))

(defn sign-authorization-code
  "Sign an authorization code JWT with claims
   {sub, scope, code_challenge, redirect_uri, type: \"code\"}.
   TTL: 5 minutes."
  [signing-key {:keys [sub scope code-challenge redirect-uri]}]
  (let [now (Instant/now)
        exp (.plusSeconds now auth-code-ttl-seconds)]
    (-> (JWT/create)
        (.withIssuer issuer)
        (.withClaim "type" "code")
        (.withClaim "sub" ^String sub)
        (.withClaim "scope" ^String scope)
        (.withClaim "code_challenge" ^String code-challenge)
        (.withClaim "redirect_uri" ^String redirect-uri)
        (.withIssuedAt now)
        (.withExpiresAt exp)
        (.sign (-algorithm signing-key)))))

(defn verify
  "Verify and decode a JWT. Returns claims map or nil.
   Checks signature, expiry, issuer, and required type claim."
  [signing-key token expected-type]
  (try
    (let [verifier (-> (JWT/require (-algorithm signing-key))
                       (.withIssuer (into-array String [issuer]))
                       (.withClaim "type" ^String expected-type)
                       (.build))
          decoded (.verify verifier ^String token)]
      {:sub (.asString (.getClaim decoded "sub"))
       :scope (.asString (.getClaim decoded "scope"))
       :type (.asString (.getClaim decoded "type"))
       :code_challenge (.asString (.getClaim decoded "code_challenge"))
       :redirect_uri (.asString (.getClaim decoded "redirect_uri"))})
    (catch JWTVerificationException _
      nil)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: derive key and sign/verify a token
  (let [session-key (.getBytes "0123456789abcdef" "UTF-8")
        signing-key (derive-signing-key session-key)
        token (sign-access-token signing-key {:sub "test-user" :scope "write"})
        claims (verify signing-key token "access")]
    {:token token :claims claims})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
