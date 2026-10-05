;; ---------------------------------------------------------
;; o11ylite.oauth.client-metadata
;;
;; OAuth Client ID Metadata Documents (CIMD,
;; draft-ietf-oauth-client-id-metadata-document). A client uses an
;; HTTPS URL as its client_id; the authorization server fetches the
;; JSON document at that URL to learn the client's name and allowed
;; redirect URIs. This replaces Dynamic Client Registration in the
;; MCP 2026-07-28 authorization spec.
;;
;; Fetching an attacker-chosen URL is an SSRF vector, so the fetch:
;; - requires https and refuses loopback / private / link-local hosts
;; - does not follow redirects
;; - caps response size and time
;; Known limitation: DNS is resolved for the check and again by the
;; HTTP client, so a DNS-rebinding host can race the check.
;; ---------------------------------------------------------

(ns o11ylite.oauth.client-metadata
  (:require
    [babashka.http-client :as http]
    [clojure.string :as str]
    [jsonista.core :as json])
  (:import
    [java.io InputStream]
    [java.net Inet4Address Inet6Address InetAddress URI URISyntaxException]
    [java.time Duration]))

;; ---------------------------------------------------------
;; Configuration

(def ^:private cache-ttl-ms
  "How long a fetched document is reused. Keeps the consent page and
   the approval POST from fetching twice, without pinning stale
   redirect URIs for long."
  (* 5 60 1000))

(def ^:private max-document-bytes (* 64 1024))
(def ^:private fetch-timeout (Duration/ofSeconds 5))

;; ---------------------------------------------------------
;; client_id URL validation

(defn cimd-client-id?
  "True when client_id looks like a Client ID Metadata Document URL
   (https scheme). Other client_id values are treated as opaque."
  [client-id]
  (and (string? client-id) (str/starts-with? client-id "https://")))

(defn- -validate-client-id-url
  "Validate the client_id URL shape per CIMD §3. Returns a URI or throws."
  ^URI [client-id]
  (let [^URI uri (try (URI. client-id)
                      (catch URISyntaxException _
                        (throw (ex-info "client_id is not a valid URL" {:type ::invalid-client}))))
        path (.getRawPath uri)]
    (when-not (and (= "https" (.getScheme uri))
                   (not (str/blank? (.getHost uri)))
                   (nil? (.getRawUserInfo uri))
                   (nil? (.getRawFragment uri))
                   (not (str/blank? path))
                   (not= "/" path)
                   (not-any? #{"." ".."} (str/split path #"/")))
      (throw (ex-info "client_id URL must be https with a path, no fragment or credentials"
                      {:type ::invalid-client})))
    uri))

;; ---------------------------------------------------------
;; SSRF guard

(defn blocked-address?
  "True for addresses an outbound metadata fetch must not reach:
   loopback, unspecified, link-local, private (RFC 1918), CGNAT
   (100.64/10), IPv6 unique-local (fc00::/7), and multicast."
  [^InetAddress addr]
  (let [b (.getAddress addr)
        u (fn [i] (bit-and (aget b i) 0xff))]
    (or (.isLoopbackAddress addr)
        (.isAnyLocalAddress addr)
        (.isLinkLocalAddress addr)
        (.isSiteLocalAddress addr)
        (.isMulticastAddress addr)
        (and (instance? Inet4Address addr)
             (= 100 (u 0)) (= 64 (bit-and (u 1) 0xc0)))
        (and (instance? Inet6Address addr)
             (= 0xfc (bit-and (u 0) 0xfe))))))

(defn- -assert-public-host!
  [^URI uri]
  (let [addrs (try (InetAddress/getAllByName (.getHost uri))
                   (catch Exception _
                     (throw (ex-info "client_id host does not resolve" {:type ::fetch-failed}))))]
    (when (some blocked-address? addrs)
      (throw (ex-info "client_id host resolves to a non-public address" {:type ::fetch-failed})))))

;; ---------------------------------------------------------
;; Fetch

(def ^:private http-client
  (delay (http/client {:follow-redirects :never
                       :connect-timeout fetch-timeout})))

(defn- -read-capped
  "Read at most max-document-bytes from the stream within fetch-timeout.
   The HTTP client's timeout only covers the response headers, so a slow
   body is bounded here; closing the stream unblocks the reader."
  [^InputStream in]
  (with-open [in in]
    (let [reader (future (.readNBytes in (inc max-document-bytes)))
          ^bytes buf (deref reader (.toMillis fetch-timeout) ::timeout)]
      (when (= ::timeout buf)
        (future-cancel reader)
        (throw (ex-info "client metadata document took too long" {:type ::fetch-failed})))
      (when (> (alength buf) max-document-bytes)
        (throw (ex-info "client metadata document is too large" {:type ::invalid-client})))
      (String. buf "UTF-8"))))

(defn- -fetch-document
  "GET the metadata document. Returns the parsed JSON (string keys).
   Redefined in tests to avoid real network access."
  [^URI uri]
  (-assert-public-host! uri)
  (let [response (try
                   (http/get (str uri) {:client @http-client
                                        :timeout fetch-timeout
                                        :headers {"Accept" "application/json"}
                                        :as :stream
                                        :throw false})
                   (catch Exception e
                     (throw (ex-info "could not fetch client metadata document"
                                     {:type ::fetch-failed} e))))]
    (when-not (= 200 (:status response))
      (some-> ^InputStream (:body response) .close)
      (throw (ex-info (str "client metadata document returned HTTP " (:status response))
                      {:type ::fetch-failed})))
    (try
      (json/read-value (-read-capped (:body response)))
      (catch com.fasterxml.jackson.core.JacksonException _
        (throw (ex-info "client metadata document is not valid JSON" {:type ::invalid-client}))))))

;; ---------------------------------------------------------
;; Document validation

(def ^:private localhost-redirect-pattern
  #"^http://(localhost|127\.0\.0\.1|\[::1\])(:\d+)?(/.*)?$")

(defn localhost-redirect?
  "True when the redirect URI targets the user's own machine."
  [uri]
  (boolean (and (string? uri) (re-matches localhost-redirect-pattern uri))))

(defn- -allowed-redirect-uri?
  "Redirect URIs must be https or loopback http (MCP authorization spec)."
  [uri]
  (and (string? uri)
       (or (localhost-redirect? uri)
           (and (str/starts-with? uri "https://")
                (try (nil? (.getRawFragment (URI. uri)))
                     (catch URISyntaxException _ false))))))

(defn validate-document
  "Validate a parsed metadata document against the client_id it was
   fetched from. Returns a normalized client map or throws ex-info."
  [client-id doc]
  (when-not (map? doc)
    (throw (ex-info "client metadata document must be a JSON object" {:type ::invalid-client})))
  (let [{:strs [client_name redirect_uris token_endpoint_auth_method client_uri]} doc]
    (cond
      (not= client-id (get doc "client_id"))
      (throw (ex-info "client metadata client_id does not match the document URL"
                      {:type ::invalid-client}))

      (or (not (string? client_name)) (str/blank? client_name))
      (throw (ex-info "client metadata must include client_name" {:type ::invalid-client}))

      (or (not (sequential? redirect_uris)) (empty? redirect_uris)
          (not (every? -allowed-redirect-uri? redirect_uris)))
      (throw (ex-info "client metadata redirect_uris must be https or localhost URLs"
                      {:type ::invalid-client}))

      ;; Only public clients are supported: the token endpoint does not
      ;; authenticate clients, so a client expecting private_key_jwt or a
      ;; secret would get weaker protection than it asked for.
      (not (contains? #{nil "none"} token_endpoint_auth_method))
      (throw (ex-info "only token_endpoint_auth_method \"none\" is supported"
                      {:type ::invalid-client}))

      :else
      {:client-id client-id
       :client-name (let [n (str/trim client_name)]
                      (if (> (count n) 100) (str (subs n 0 100) "…") n))
       :client-uri (when (string? client_uri) client_uri)
       :redirect-uris (vec redirect_uris)})))

;; ---------------------------------------------------------
;; Public API

(defn resolve-client
  "Fetch (or reuse from cache) and validate the metadata document for a
   CIMD client_id. cache is an atom of client-id -> {:client :expires-at}.
   Returns the client map; throws ex-info with :type ::invalid-client or
   ::fetch-failed. Failures are not cached."
  [cache client-id]
  (let [now (System/currentTimeMillis)
        cached (get @cache client-id)]
    (if (and cached (> (:expires-at cached) now))
      (:client cached)
      (let [uri (-validate-client-id-url client-id)
            client (validate-document client-id (-fetch-document uri))]
        (swap! cache (fn [m]
                       (-> (into {} (filter (fn [[_ v]] (> (:expires-at v) now))) m)
                           (assoc client-id {:client client
                                             :expires-at (+ now cache-ttl-ms)}))))
        client))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (blocked-address? (InetAddress/getByName "10.0.0.1"))   ;; => true
  (blocked-address? (InetAddress/getByName "100.64.1.1")) ;; => true
  (blocked-address? (InetAddress/getByName "1.1.1.1"))    ;; => false

  (resolve-client (atom {}) "https://example.com/client.json")

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
