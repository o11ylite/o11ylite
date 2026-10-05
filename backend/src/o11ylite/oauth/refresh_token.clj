;; ---------------------------------------------------------
;; o11ylite.oauth.refresh-token
;;
;; SQLite store for rotating OAuth refresh tokens.
;;
;; Tokens are opaque random strings; only an HMAC of each token, keyed
;; by the JWT signing key, is stored. Rotating O11YLITE_SESSION_SECRET
;; therefore invalidates refresh tokens along with access tokens.
;; Each refresh marks the presented token used and issues a new one in
;; the same family. Presenting an already-used token means it leaked
;; (either the client or an attacker holds a stale copy), so the whole
;; family is revoked (OAuth 2.1 §4.3.1).
;; ---------------------------------------------------------

(ns o11ylite.oauth.refresh-token
  (:require
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    [java.security SecureRandom]
    [java.util HexFormat UUID]
    [javax.crypto Mac]
    [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------
;; Configuration

(def ttl-ms
  "Refresh token lifetime. Each rotation starts a new window, so an
   agent that keeps working stays signed in; one idle for longer than
   this must re-authorize."
  (* 30 24 60 60 1000))

(def ^:private token-prefix "o11yrt_")

(def ^:private ^SecureRandom secure-random (SecureRandom.))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -generate-token
  []
  (let [bytes (byte-array 32)]
    (.nextBytes secure-random bytes)
    (str token-prefix (.formatHex (HexFormat/of) bytes))))

(defn- -token-hash
  "HMAC-SHA256 of the token keyed by the signing key, hex-encoded."
  [^bytes signing-key ^String token]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. signing-key "HmacSHA256"))
    (.formatHex (HexFormat/of) (.doFinal mac (.getBytes token "UTF-8")))))

(defn- -insert!
  "Insert a new token row and return the plaintext token."
  [tx signing-key {:keys [family-id sub scope client-id resource]} now]
  (let [token (-generate-token)]
    (jdbc/execute! tx ["INSERT INTO oauth_refresh_tokens
                        (token_hash, family_id, sub, scope, client_id, resource,
                         created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                       (-token-hash signing-key token) family-id sub scope client-id resource
                       now (+ now ttl-ms)])
    token))

;; ---------------------------------------------------------
;; Public API

(defn issue!
  "Start a new token family and return the plaintext refresh token.
   store: {:sqlite :signing-key}. grant: {:sub :scope :client-id :resource}.
   Expired rows are pruned opportunistically so the table does not grow
   without bound."
  [{:keys [sqlite signing-key]} grant]
  (let [now (System/currentTimeMillis)]
    (jdbc/with-transaction [tx sqlite]
      (jdbc/execute! tx ["DELETE FROM oauth_refresh_tokens WHERE expires_at < ?" now])
      (-insert! tx signing-key (assoc grant :family-id (str (UUID/randomUUID))) now))))

(defn rotate!
  "Exchange a refresh token for a new one.

   check-fn receives the stored grant {:sub :scope :client-id :resource}
   and returns nil to proceed or an error value to refuse the exchange
   without consuming the token (e.g. client_id mismatch), so a bad
   request cannot burn the legitimate client's token.

   Returns {:token <new plaintext> :grant <grant>} on success,
   {:error :reused} when the token was already used (its family is
   revoked), {:error :invalid} when unknown or expired, or
   {:error <check-fn result>}."
  [{:keys [sqlite signing-key]} token check-fn]
  (let [now (System/currentTimeMillis)
        token-hash (-token-hash signing-key (str token))]
    (jdbc/with-transaction [tx sqlite]
      (let [row (jdbc/execute-one! tx ["SELECT * FROM oauth_refresh_tokens WHERE token_hash = ?"
                                       token-hash]
                                   {:builder-fn rs/as-unqualified-lower-maps})]
        (cond
          (or (nil? row) (<= (:expires_at row) now))
          {:error :invalid}

          (some? (:used_at row))
          (do (jdbc/execute! tx ["DELETE FROM oauth_refresh_tokens WHERE family_id = ?"
                                 (:family_id row)])
              {:error :reused})

          :else
          (let [grant {:sub (:sub row)
                       :scope (:scope row)
                       :client-id (:client_id row)
                       :resource (:resource row)}]
            (if-let [error (check-fn grant)]
              {:error error}
              ;; Conditional update: of two concurrent refreshes, only one
              ;; claims the token; the other is treated as reuse.
              (let [[{::jdbc/keys [update-count]}]
                    (jdbc/execute! tx ["UPDATE oauth_refresh_tokens SET used_at = ?
                                        WHERE token_hash = ? AND used_at IS NULL"
                                       now token-hash])]
                (if (= 1 update-count)
                  {:token (-insert! tx signing-key (assoc grant :family-id (:family_id row)) now)
                   :grant grant}
                  (do (jdbc/execute! tx ["DELETE FROM oauth_refresh_tokens WHERE family_id = ?"
                                         (:family_id row)])
                      {:error :reused}))))))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[integrant.repl.state :refer [system]])
  (def store {:sqlite (:db/sqlite system)
              :signing-key (get-in system [:auth/config :jwt-signing-key])})

  (def t (issue! store {:sub "u" :scope "read" :client-id nil :resource nil}))
  (rotate! store t (constantly nil)) ;; => {:token "o11yrt_..." :grant {...}}
  (rotate! store t (constantly nil)) ;; => {:error :reused} — family revoked

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
