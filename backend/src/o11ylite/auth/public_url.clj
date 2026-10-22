;; ---------------------------------------------------------
;; o11ylite.auth.public-url
;;
;; Resolves the public base URL of this instance. Used as the
;; OAuth issuer, the MCP resource identifier (RFC 8707 / RFC 9728),
;; and the OIDC redirect base.
;;
;; Prefers the configured O11YLITE_PUBLIC_URL. Falls back to the
;; request's X-Forwarded-Proto / X-Forwarded-Host / Host headers.
;; ---------------------------------------------------------

(ns o11ylite.auth.public-url
  (:require
    [clojure.string :as str])
  (:import
    [java.net URI URISyntaxException]))

;; ---------------------------------------------------------
;; Canonical URIs

(defn canonical-uri
  "Normalize an absolute http(s) URI for comparison: lowercase scheme
   and host, drop a trailing slash. Returns nil when the URI is not an
   absolute http(s) URI or carries a fragment."
  [s]
  (when (string? s)
    (try
      (let [uri (URI. s)
            scheme (some-> (.getScheme uri) str/lower-case)
            host (some-> (.getHost uri) str/lower-case)]
        (when (and (#{"http" "https"} scheme)
                   (not (str/blank? host))
                   (nil? (.getRawFragment uri))
                   (nil? (.getRawUserInfo uri)))
          (let [port (when (pos? (.getPort uri)) (str ":" (.getPort uri)))
                path (str/replace (or (.getRawPath uri) "") #"/+$" "")
                query (some->> (.getRawQuery uri) (str "?"))]
            (str scheme "://" host port path query))))
      (catch URISyntaxException _
        nil))))

;; ---------------------------------------------------------
;; Base URL

(defn- -first-header-value
  "Proxies may send comma-separated lists; the first entry is the client-facing one."
  [request header-name]
  (some-> (get-in request [:headers header-name])
          (str/split #",")
          first
          str/trim
          not-empty))

(defn- -request-base-url
  [request]
  (let [scheme (or (-first-header-value request "x-forwarded-proto")
                   (some-> (:scheme request) name)
                   "http")
        host (or (-first-header-value request "x-forwarded-host")
                 (get-in request [:headers "host"]))]
    (canonical-uri (str scheme "://" host))))

(defn base-url
  "Public base URL without a trailing slash, e.g. https://o11ylite.example.com.
   Uses :public-url from auth-config when configured."
  [auth-config request]
  (or (:public-url auth-config)
      (-request-base-url request)))

(defn origin
  "Origin (scheme://host[:port]) of an absolute URL."
  [url]
  (let [uri (URI. url)]
    (str (.getScheme uri) "://" (.getRawAuthority uri))))

;; ---------------------------------------------------------
;; MCP Resource

(def mcp-path
  "Path of the MCP endpoint."
  "/mcp")

(defn mcp-resource
  "Canonical resource identifier of the MCP endpoint (RFC 8707)."
  [auth-config request]
  (str (base-url auth-config request) mcp-path))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (canonical-uri "HTTPS://Example.COM:8443/mcp/")
  ;; => "https://example.com:8443/mcp"

  (base-url {} {:scheme :http :headers {"host" "localhost:3000"}})
  ;; => "http://localhost:3000"

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
