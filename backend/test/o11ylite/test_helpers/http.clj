;; ---------------------------------------------------------
;; o11ylite.test-helpers.http
;;
;; HTTP client helpers for integration tests.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers.http
  (:require
    [babashka.http-client :as http]
    [jsonista.core :as json])
  (:import
    [java.net URLDecoder]))

;; ---------------------------------------------------------
;; Configuration

(def test-port
  "Port for test HTTP server (different from dev port 3000)"
  3333)

(def base-url
  "Base URL for test requests"
  (str "http://localhost:" test-port))

;; ---------------------------------------------------------
;; Request Helpers

(defn url
  "Build a full URL from a path."
  [path]
  (str base-url path))

(defn get-request
  "Make a GET request. Returns response map with :status, :headers, :body.

   Options:
   - :headers - map of request headers
   - :throw   - whether to throw on error status (default false)"
  ([path] (get-request path {}))
  ([path opts]
   (http/get (url path)
             (merge {:throw false} opts))))

(defn- -parse-json-body
  "Parse body as JSON if non-empty, otherwise return nil."
  [body]
  (when (and body (not (empty? body)))
    (json/read-value body json/keyword-keys-object-mapper)))

(defn get-json
  "Make a GET request expecting JSON response. Parses body as JSON."
  ([path] (get-json path {}))
  ([path opts]
   (let [response (get-request path opts)]
     (assoc response :body (-parse-json-body (:body response))))))

(defn inertia-headers
  "Build Inertia request headers."
  [{:keys [version] :or {version "dev"}}]
  {"X-Inertia" "true"
   "X-Inertia-Version" version})

(defn inertia-request
  "Make an Inertia XHR request (with X-Inertia headers).
   Returns raw response (body not parsed as JSON)."
  ([path] (inertia-request path {}))
  ([path {:keys [version] :or {version "dev"} :as opts}]
   (get-request path
                (-> opts
                    (dissoc :version)
                    (assoc :headers (merge (inertia-headers {:version version})
                                           (:headers opts)))))))

(defn inertia-json-request
  "Make an Inertia XHR request and parse response body as JSON."
  ([path] (inertia-json-request path {}))
  ([path {:keys [version] :or {version "dev"} :as opts}]
   (get-json path
             (-> opts
                 (dissoc :version)
                 (assoc :headers (merge (inertia-headers {:version version})
                                        (:headers opts)))))))

(defn post
  "Make a POST request with raw body. Returns response map.
   
   Options:
   - :headers - map of request headers
   - :body    - request body (string or bytes)
   - :throw   - whether to throw on error status (default false)"
  ([path] (post path {}))
  ([path opts]
   (http/post (url path)
              (merge {:throw false} opts))))

(defn post-json
  "Make a POST request with JSON body, expecting JSON response.
   Body is automatically serialized to JSON."
  ([path body] (post-json path body {}))
  ([path body opts]
   (let [response (http/post (url path)
                             (merge {:throw false
                                     :headers {"Content-Type" "application/json"}
                                     :body (json/write-value-as-string body)}
                                    opts))]
     (assoc response :body (-parse-json-body (:body response))))))

(defn put-json
  "Make a PUT request with JSON body, expecting JSON response."
  ([path body] (put-json path body {}))
  ([path body opts]
   (let [response (http/put (url path)
                            (merge {:throw false
                                    :headers {"Content-Type" "application/json"}
                                    :body (json/write-value-as-string body)}
                                   opts))]
     (assoc response :body (-parse-json-body (:body response))))))

(defn delete-request
  "Make a DELETE request. Returns response map."
  ([path] (delete-request path {}))
  ([path opts]
   (http/delete (url path)
                (merge {:throw false} opts))))

;; ---------------------------------------------------------
;; CSRF Session Helpers

(defn- -extract-csrf-token
  "Extract XSRF-TOKEN value from Set-Cookie header (URL-decoded)."
  [response]
  (let [cookies (get-in response [:headers "set-cookie"])]
    (when cookies
      (let [cookie-str (if (coll? cookies) (first (filter #(.contains ^String % "XSRF-TOKEN") cookies)) cookies)]
        (when (and cookie-str (.contains ^String cookie-str "XSRF-TOKEN"))
          (URLDecoder/decode (second (re-find #"XSRF-TOKEN=([^;]+)" cookie-str)) "UTF-8"))))))

(defn extract-session-cookie
  "Extract ring-session cookie value from Set-Cookie header (raw, URL-encoded)."
  [response]
  (let [cookies (get-in response [:headers "set-cookie"])]
    (when cookies
      (let [cookie-str (if (coll? cookies) (first (filter #(.contains ^String % "ring-session") cookies)) cookies)]
        (when (and cookie-str (.contains ^String cookie-str "ring-session"))
          (second (re-find #"ring-session=([^;]+)" cookie-str)))))))

(def no-redirect-client
  "HTTP client that does not follow redirects."
  (delay (http/client {:follow-redirects :never})))

(defn no-redirect-get
  "GET without following redirects. Returns raw response."
  ([path] (no-redirect-get path {}))
  ([path opts]
   (http/get (url path)
             (merge {:client @no-redirect-client :throw false} opts))))

(defn no-redirect-post
  "POST without following redirects. Returns raw response."
  ([path] (no-redirect-post path {}))
  ([path opts]
   (http/post (url path)
              (merge {:client @no-redirect-client :throw false} opts))))

(defn csrf-session
  "Establish a session by hitting a page route and extracting CSRF + session cookies.
   Returns a map with :csrf-token and :cookie-header."
  ([] (csrf-session "/alert-rules"))
  ([path]
   (let [response (get-request path)
         csrf (-extract-csrf-token response)
         session (extract-session-cookie response)]
     {:csrf-token csrf
      :cookie-header (str "ring-session=" session "; XSRF-TOKEN=" csrf)})))

(defn csrf-headers
  "Build headers map with CSRF token and session cookie from a csrf-session."
  [session]
  {"X-XSRF-TOKEN" (:csrf-token session)
   "Cookie" (:cookie-header session)
   "Content-Type" "application/json"})

(defn post-mutation
  "POST with CSRF session, no redirect following. Returns raw 303 response."
  [path session body-map]
  (http/post (url path)
             {:client @no-redirect-client
              :throw false
              :headers (csrf-headers session)
              :body (json/write-value-as-string body-map)}))

(defn put-mutation
  "PUT with CSRF session, no redirect following. Returns raw 303 response."
  [path session body-map]
  (http/put (url path)
            {:client @no-redirect-client
             :throw false
             :headers (csrf-headers session)
             :body (json/write-value-as-string body-map)}))

(defn delete-mutation
  "DELETE with CSRF session, no redirect following. Returns raw 303 response."
  [path session]
  (http/delete (url path)
               {:client @no-redirect-client
                :throw false
                :headers (csrf-headers session)}))

;; ---------------------------------------------------------
;; Response Helpers

(defn status
  "Get status code from response."
  [response]
  (:status response))

(defn header
  "Get a header value from response (case-insensitive)."
  [response header-name]
  (get-in response [:headers header-name]))

(defn body
  "Get body from response."
  [response]
  (:body response))

(defn content-type
  "Get content-type header from response."
  [response]
  (header response "content-type"))

(defn json-response?
  "Check if response has JSON content type."
  [response]
  (some-> (content-type response)
          (.contains "application/json")))

(defn html-response?
  "Check if response has HTML content type."
  [response]
  (some-> (content-type response)
          (.contains "text/html")))
