;; ---------------------------------------------------------
;; o11ylite.test-helpers
;;
;; Shared test utilities for integration tests.
;; Provides system lifecycle management and HTTP client helpers.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers
  (:require
   [babashka.http-client :as http]
   [integrant.core :as ig]
   [jsonista.core :as json]
   [o11ylite.system :as system]))

;; ---------------------------------------------------------
;; Test Configuration

(def test-port
  "Port for test server (different from dev port 3000)"
  3333)

(def base-url
  "Base URL for test requests"
  (str "http://localhost:" test-port))

;; ---------------------------------------------------------
;; System Lifecycle

(def ^:dynamic *system*
  "Dynamic var holding the running test system"
  nil)

(defn start-test-system!
  "Start the system with test configuration."
  []
  (let [config (-> (system/read-config :dev)
                   (assoc-in [:server/web :port] test-port)
                   (assoc-in [:server/web :host] "127.0.0.1"))]
    (ig/init config)))

(defn stop-test-system!
  "Stop the test system."
  [system]
  (ig/halt! system))

(defn with-system
  "Test fixture that starts/stops the system for each test.

   Usage:
   (use-fixtures :each test-helpers/with-system)"
  [f]
  (let [sys (start-test-system!)]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (stop-test-system! sys)))))

;; ---------------------------------------------------------
;; HTTP Client Helpers

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

(defn- parse-json-body
  "Parse body as JSON if non-empty, otherwise return nil."
  [body]
  (when (and body (not (empty? body)))
    (json/read-value body json/keyword-keys-object-mapper)))

(defn get-json
  "Make a GET request expecting JSON response. Parses body as JSON."
  ([path] (get-json path {}))
  ([path opts]
   (let [response (get-request path opts)]
     (assoc response :body (parse-json-body (:body response))))))

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
