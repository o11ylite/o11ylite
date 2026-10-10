;; ---------------------------------------------------------
;; o11ylite.oauth
;;
;; PKCE verification and JWT sign/verify for OAuth agent auth.
;; Authorization codes and access tokens are both stateless JWTs
;; signed with HMAC256. Signing key derived from the session secret.
;; ---------------------------------------------------------

(ns o11ylite.oauth
  (:import
    [com.auth0.jwt JWT JWTCreator$Builder JWTVerifier]
    [com.auth0.jwt.algorithms Algorithm]
    [com.auth0.jwt.exceptions JWTVerificationException]
    [com.auth0.jwt.interfaces DecodedJWT]
    [java.security MessageDigest]
    [java.time Instant]
    [java.util Base64 UUID]
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
(def access-token-ttl-seconds
  "Access token lifetime."
  3600)
(def ^:private auth-code-ttl-seconds 300)          ; 5 minutes

(defn- -algorithm
  "Create HMAC256 algorithm from derived key bytes."
  [^bytes signing-key]
  (Algorithm/HMAC256 signing-key))

(defn- -with-optional-claim
  ^JWTCreator$Builder [^JWTCreator$Builder builder ^String claim-name ^String value]
  (if value
    (.withClaim builder claim-name value)
    builder))

(defn- -with-optional-audience
  ^JWTCreator$Builder [^JWTCreator$Builder builder ^String audience]
  (if audience
    (.withAudience builder (into-array String [audience]))
    builder))

(defn sign-access-token
  "Sign an access token JWT with claims {sub, scope, type: \"access\"}.
   :audience binds the token to a resource (RFC 8707), e.g. the MCP
   endpoint; tokens without an audience are only accepted by /api.
   :client-id records the OAuth client the token was issued to.
   TTL: 1 hour."
  [signing-key {:keys [sub scope audience client-id]}]
  (let [now (Instant/now)
        exp (.plusSeconds now access-token-ttl-seconds)]
    (-> (JWT/create)
        (.withIssuer issuer)
        (.withClaim "type" "access")
        (.withClaim "sub" ^String sub)
        (.withClaim "scope" ^String scope)
        (-with-optional-claim "client_id" client-id)
        (-with-optional-audience audience)
        (.withIssuedAt now)
        (.withExpiresAt exp)
        (.sign (-algorithm signing-key)))))

(defn sign-authorization-code
  "Sign an authorization code JWT with claims
   {jti, sub, scope, code_challenge, redirect_uri, client_id, resource,
   type: \"code\"}. The jti lets the token endpoint enforce single use.
   TTL: 5 minutes."
  [signing-key {:keys [sub scope code-challenge redirect-uri client-id resource]}]
  (let [now (Instant/now)
        exp (.plusSeconds now auth-code-ttl-seconds)]
    (-> (JWT/create)
        (.withIssuer issuer)
        (.withJWTId (str (UUID/randomUUID)))
        (.withClaim "type" "code")
        (.withClaim "sub" ^String sub)
        (.withClaim "scope" ^String scope)
        (.withClaim "code_challenge" ^String code-challenge)
        (.withClaim "redirect_uri" ^String redirect-uri)
        (-with-optional-claim "client_id" client-id)
        (-with-optional-claim "resource" resource)
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
          ^DecodedJWT decoded (.verify ^JWTVerifier verifier ^String token)]
      {:sub (.asString (.getClaim decoded "sub"))
       :scope (.asString (.getClaim decoded "scope"))
       :type (.asString (.getClaim decoded "type"))
       :jti (.getId decoded)
       :exp-ms (some-> (.getExpiresAt decoded) .getTime)
       :aud (first (.getAudience decoded))
       :client_id (.asString (.getClaim decoded "client_id"))
       :resource (.asString (.getClaim decoded "resource"))
       :code_challenge (.asString (.getClaim decoded "code_challenge"))
       :redirect_uri (.asString (.getClaim decoded "redirect_uri"))})
    (catch JWTVerificationException _
      nil)))

(defn verify-access-token
  "Verify an access token and its audience. Returns claims or nil.
   expected-audience nil accepts only tokens without an audience
   (legacy agent tokens used against /api); a string requires an exact
   match, so tokens minted for the MCP endpoint are not accepted
   elsewhere and vice versa."
  [signing-key token expected-audience]
  (when-let [claims (verify signing-key token "access")]
    (when (= expected-audience (:aud claims))
      claims)))

;; ---------------------------------------------------------
;; Single-use Authorization Codes

(defn consume-code!
  "Record an authorization code's jti as used. Returns true on first use,
   false on replay. Expired entries are pruned on every call.
   used-codes is an atom of jti -> expiry epoch ms."
  [used-codes {:keys [jti exp-ms]}]
  (let [now (System/currentTimeMillis)
        [before _] (swap-vals! used-codes
                               (fn [m]
                                 (-> (into {} (filter (fn [[_ exp]] (> exp now))) m)
                                     (assoc jti exp-ms))))]
    (and (some? jti) (not (contains? before jti)))))

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
