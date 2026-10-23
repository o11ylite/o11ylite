;; ---------------------------------------------------------
;; o11ylite.oauth.registration
;;
;; Validation for Dynamic Client Registration requests (RFC 7591).
;;
;; Registration is open: anyone who can reach the endpoint can create a
;; public client. That is safe only because redirect URIs are limited to
;; https or loopback http, every authorization still shows the consent
;; page, and the route rate-limits registrations per source address.
;;
;; Deliberately unsupported:
;; - client secrets and private_key_jwt (MCP clients are public clients)
;; - registration management (RFC 7592): clients that lose their id
;;   simply register again
;; ---------------------------------------------------------

(ns o11ylite.oauth.registration
  (:require
    [clojure.string :as str]
    [o11ylite.auth.scope :as scope]
    [o11ylite.oauth.client-metadata :as client-metadata])
  (:import
    [java.net URI URISyntaxException]
    [java.security SecureRandom]
    [java.util HexFormat]))

;; ---------------------------------------------------------
;; Limits

(def ^:private max-redirect-uris 10)
(def ^:private max-uri-length 2048)
(def ^:private max-client-name-length 100)

;; ---------------------------------------------------------
;; Validation Helpers

(defn- -uri
  "Parse an absolute URI, or nil when it is malformed."
  [s]
  (when (and (string? s) (<= (count s) max-uri-length))
    (try (URI. s) (catch URISyntaxException _ nil))))

(defn- -https-uri?
  "True for an absolute https URI without userinfo or fragment."
  [s]
  (when-let [^URI uri (-uri s)]
    (and (= "https" (some-> (.getScheme uri) str/lower-case))
         (not (str/blank? (.getHost uri)))
         (nil? (.getRawUserInfo uri))
         (nil? (.getRawFragment uri)))))

(defn- -redirect-uri?
  "Redirect URIs must be https or http on a loopback host (the MCP
   authorization spec's rule for native clients)."
  [s]
  (if (client-metadata/localhost-redirect? s)
    (when-let [^URI uri (-uri s)]
      (and (nil? (.getRawUserInfo uri))
           (nil? (.getRawFragment uri))))
    (-https-uri? s)))

(defn- -client-uri?
  "client_uri is display metadata; only absolute http(s) URIs are useful."
  [s]
  (when-let [^URI uri (-uri s)]
    (and (#{"http" "https"} (some-> (.getScheme uri) str/lower-case))
         (not (str/blank? (.getHost uri)))
         (nil? (.getRawUserInfo uri))
         (nil? (.getRawFragment uri)))))

(defn- -normalize-name
  "Trim, strip control characters, and cap the display name."
  [name]
  (let [clean (some-> name
                      str
                      (str/replace #"\p{Cntrl}" "")
                      str/trim)]
    (cond
      (str/blank? clean) "Unnamed application"
      (> (count clean) max-client-name-length) (str (subs clean 0 max-client-name-length) "…")
      :else clean)))

(defn- -valid-grant-types?
  [grant-types]
  (and (sequential? grant-types)
       (seq grant-types)
       (every? #{"authorization_code" "refresh_token"} grant-types)
       (some #{"authorization_code"} grant-types)))

(defn- -valid-response-types?
  [response-types]
  (and (sequential? response-types) (= ["code"] (vec response-types))))

;; ---------------------------------------------------------
;; Client IDs

(defn new-client-id
  "Generate an opaque, unguessable client id. The prefix keeps dynamic
   registrations distinguishable from CIMD client ids (which are URLs)
   and from API keys (o11y_)."
  []
  (let [bytes (byte-array 16)]
    (.nextBytes (SecureRandom.) bytes)
    (str "mcp_client_" (.formatHex (HexFormat/of) bytes))))

;; ---------------------------------------------------------
;; Public API

(defn validate-request
  "Validate an RFC 7591 registration request body (parsed JSON with
   keyword keys). Returns {:client {...}} or {:error code :description}."
  [body]
  (if-not (map? body)
    {:error "invalid_client_metadata"
     :description "Request body must be a JSON object"}
    (let [{:keys [redirect_uris client_name client_uri scope
                  grant_types response_types token_endpoint_auth_method]} body
          client-name (when (or (nil? client_name) (string? client_name))
                        (-normalize-name client_name))]
      (cond
        (or (not (sequential? redirect_uris)) (empty? redirect_uris))
        {:error "invalid_client_metadata"
         :description "redirect_uris is required and must be a non-empty array"}

        (not (every? string? redirect_uris))
        {:error "invalid_client_metadata"
         :description "redirect_uris must contain strings"}

        (> (count redirect_uris) max-redirect-uris)
        {:error "invalid_client_metadata"
         :description (str "At most " max-redirect-uris " redirect_uris are allowed")}

        (not (every? -redirect-uri? redirect_uris))
        {:error "invalid_redirect_uri"
         :description "redirect_uris must be https or http on localhost, without a fragment"}

        (and client_name (not (string? client_name)))
        {:error "invalid_client_metadata"
         :description "client_name must be a string"}

        (and client_uri (not (-client-uri? client_uri)))
        {:error "invalid_client_metadata"
         :description "client_uri must be an absolute http(s) URI without a fragment"}

        (and scope (not (string? scope)))
        {:error "invalid_client_metadata"
         :description "scope must be a string"}

        (and grant_types (not (-valid-grant-types? grant_types)))
        {:error "invalid_client_metadata"
         :description "grant_types must include authorization_code and may include refresh_token"}

        (and response_types (not (-valid-response-types? response_types)))
        {:error "invalid_client_metadata"
         :description "response_types must be [\"code\"]"}

        (and token_endpoint_auth_method (not= "none" token_endpoint_auth_method))
        {:error "invalid_client_metadata"
         :description "Only token_endpoint_auth_method \"none\" is supported"}

        (and (string? scope)
             (not (str/blank? scope))
             (nil? (scope/resolve-requested scope)))
        {:error "invalid_client_metadata"
         :description (str "Invalid scope: " scope)}

        :else
        {:client {:client-name client-name
                  :client-uri (when (string? client_uri) client_uri)
                  ;; Self-registered clients have no verified identity; the
                  ;; consent page shows the client_uri host when there is one.
                  :client-host (when (string? client_uri) (.getHost (URI. client_uri)))
                  :redirect-uris (vec redirect_uris)
                  :scope (when (and (string? scope) (not (str/blank? scope)))
                           (scope/resolve-requested scope))}}))))

(defn response-body
  "Build the RFC 7591 registration response for a stored client."
  [client]
  (cond-> {:client_id (:client-id client)
           :client_id_issued_at (quot (System/currentTimeMillis) 1000)
           :client_name (:client-name client)
           :redirect_uris (:redirect-uris client)
           :grant_types ["authorization_code" "refresh_token"]
           :response_types ["code"]
           :token_endpoint_auth_method "none"}
    (:client-uri client) (assoc :client_uri (:client-uri client))
    (:scope client) (assoc :scope (:scope client))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (validate-request {:redirect_uris ["https://client.example.com/cb"]
                     :client_name "Example Client"
                     :token_endpoint_auth_method "none"})

  (validate-request {:redirect_uris ["http://localhost:1234/cb"]})

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
