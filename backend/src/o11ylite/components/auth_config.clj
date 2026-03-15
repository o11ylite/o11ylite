;; ---------------------------------------------------------
;; o11ylite.components.auth-config
;;
;; Integrant component for authentication configuration.
;; Handles OIDC discovery and session secret management.
;; ---------------------------------------------------------

(ns o11ylite.components.auth-config
  (:require
    [com.brunobonacci.mulog :as mulog]
    [integrant.core :as ig]
    [o11ylite.kv :as kv]
    [o11ylite.oauth :as oauth]
    [oidc-client.core :as oidc])
  (:import
    [java.security SecureRandom]
    [java.util HexFormat]))

;; ---------------------------------------------------------
;; Session Secret Management

(def ^:private kv-session-secret-key "auth/session-secret")

(defn- -generate-session-secret
  "Generate a random 16-byte hex string for session encryption."
  []
  (let [bytes (byte-array 16)
        _ (.nextBytes (SecureRandom.) bytes)]
    (.formatHex (HexFormat/of) bytes)))

(defn- -resolve-session-secret
  "Resolve the session secret: use env var, or load/generate from KV store."
  [core-config sqlite]
  (if-let [explicit-secret (:session-secret core-config)]
    (do
      (mulog/log ::session-secret-source :source :env-var)
      explicit-secret)
    (if-let [stored-secret (kv/get-value sqlite kv-session-secret-key)]
      (do
        (mulog/log ::session-secret-source :source :kv-store)
        stored-secret)
      (let [new-secret (-generate-session-secret)]
        (kv/set-value! sqlite kv-session-secret-key new-secret)
        (mulog/log ::session-secret-source :source :generated)
        new-secret))))

(defn- -hex-to-bytes
  "Convert a hex string to a byte array."
  [^String hex-str]
  (.parseHex (HexFormat/of) hex-str))

;; ---------------------------------------------------------
;; OIDC Discovery

(defn- -discover-oidc
  "Discover and configure OIDC client. Returns config map or nil."
  [{:keys [oidc-issuer-url oidc-client-id oidc-client-secret]}]
  (when oidc-issuer-url
    (when-not oidc-client-id
      (throw (ex-info "O11YLITE_OIDC_CLIENT_ID is required when OIDC is enabled" {})))
    (mulog/log ::oidc-discovering :issuer oidc-issuer-url)
    (let [server-meta (oidc/discover oidc-issuer-url)
          config (oidc/configuration server-meta oidc-client-id
                                     (cond-> {}
                                       oidc-client-secret (assoc :client-secret oidc-client-secret)))]
      (mulog/log ::oidc-discovered :issuer oidc-issuer-url)
      config)))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :auth/config
  [_ {:keys [core-config sqlite]}]
  (mulog/log ::auth-config-starting)
  (let [session-secret (-resolve-session-secret core-config sqlite)
        session-key (-hex-to-bytes session-secret)
        oidc-config (-discover-oidc core-config)
        open-mode? (nil? oidc-config)]
    (mulog/log ::auth-config-started
               :open-mode? open-mode?)
    {:oidc-config oidc-config
     :session-key session-key
     :jwt-signing-key (oauth/derive-signing-key session-key)
     :open-mode? open-mode?}))

(defmethod ig/halt-key! :auth/config
  [_ _]
  (mulog/log ::auth-config-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (-generate-session-secret)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
