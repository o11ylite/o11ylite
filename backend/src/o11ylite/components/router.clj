;; ---------------------------------------------------------
;; o11ylite.components.router
;;
;; Reitit router component - assembles all routes
;; ---------------------------------------------------------

(ns o11ylite.components.router
  (:require
    [integrant.core :as ig]
    [com.brunobonacci.mulog :as mulog]
    [ring.middleware.defaults :refer [wrap-defaults site-defaults api-defaults]]
    [reitit.ring :as ring]
    [reitit.ring.middleware.exception :as exception]
    [steffan-westcott.clj-otel.api.trace.span :as span]
    [jsonista.core :as json]
    [o11ylite.util.response :as response]
    [o11ylite.inertia.middleware :as inertia]
    [o11ylite.api.events :as api.events]
    [o11ylite.api.health :as api.health]
    [o11ylite.api.metrics :as api.metrics]
    [o11ylite.api.query :as api.query]
    [o11ylite.api.services :as api.services]
    [o11ylite.otel-http :as otel-http]
    [o11ylite.routes.home :as home]
    [o11ylite.routes.explore :as explore]
    [o11ylite.routes.trace :as trace]
    [o11ylite.routes.notebooks :as notebooks]
    [o11ylite.routes.alert-rules :as alert-rules]))

;; ---------------------------------------------------------
;; Middleware Factories

(defn- -json-content-type?
  "Check if request has JSON content type."
  [request]
  (when-let [content-type (get-in request [:headers "content-type"])]
    (.contains content-type "application/json")))

(defn wrap-json-body
  "Parse JSON request body into :body as Clojure data with keyword keys."
  [handler]
  (fn [request]
    (if (and (-json-content-type? request) (:body request))
      (let [body-str (slurp (:body request))
            parsed (when (seq body-str)
                     (json/read-value body-str json/keyword-keys-object-mapper))]
        (handler (assoc request :body parsed)))
      (handler request))))

(defn wrap-api-defaults
  "Wrap handler with api-defaults middleware."
  [handler]
  (-> handler
      wrap-json-body
      (wrap-defaults api-defaults)))

(defn wrap-site-defaults
  "Wrap handler with site-defaults middleware."
  [handler]
  (wrap-defaults handler site-defaults))

(defn make-wrap-inertia
  "Create Inertia middleware with config."
  [inertia-config]
  (fn [handler]
    (inertia/wrap-inertia handler inertia-config)))

;; ---------------------------------------------------------
;; Exception Handling

(defn- -api-exception-handler
  "Record exception on current span and return 500 with JSON body."
  [exception _request]
  (span/add-exception! exception)
  (response/json 500 {:error "Internal server error"}))

(defn- -page-exception-handler
  "Record exception on current span and return Inertia error response."
  [exception _request]
  (span/add-exception! exception)
  (response/inertia "Error" {:status 500}))

(def ^:private -api-exception-middleware
  "Exception middleware for API routes - returns JSON responses."
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {::exception/default -api-exception-handler})))

(def ^:private -page-exception-middleware
  "Exception middleware for page routes - returns Inertia error page."
  (exception/create-exception-middleware
    (merge
      exception/default-handlers
      {::exception/default -page-exception-handler})))

;; ---------------------------------------------------------
;; Routes

(defn api-routes
  "API routes - no CSRF, no sessions."
  [{:keys [duckdb sqlite event-metadata]}]
  ["/api" {:middleware [wrap-api-defaults
                        -api-exception-middleware]}
   (api.events/routes {:event-metadata event-metadata})
   (api.health/routes {})
   (api.metrics/routes {:sqlite sqlite})
   (api.query/routes {:duckdb duckdb :sqlite sqlite :event-metadata event-metadata})
   (api.services/routes {:sqlite sqlite})])

(defn otlp-routes
  "OTLP HTTP routes - raw body handling, no JSON parsing middleware.
   These routes handle their own protobuf/JSON parsing."
  [{:keys [event-metadata event-batcher id-generator metric-batcher metric-normalizer sqlite]}]
  ["" {:middleware [-api-exception-middleware]}
   (otel-http/routes {:event-metadata event-metadata
                      :event-batcher event-batcher
                      :id-generator id-generator
                      :metric-batcher metric-batcher
                      :metric-normalizer metric-normalizer
                      :sqlite sqlite})])

(defn page-routes
  "Page routes - site defaults + Inertia middleware."
  [{:keys [inertia sqlite id-generator]}]
  ["" {:middleware [wrap-site-defaults
                    inertia/wrap-csrf-cookie
                    (make-wrap-inertia inertia)
                    ;; Inertia sends POST/PUT/DELETE as JSON; site-defaults
                    ;; only parses form-encoded bodies, so we need this.
                    wrap-json-body
                    -page-exception-middleware]}
   (home/routes {})
   (explore/routes {})
   (trace/routes {})
   (notebooks/routes {})
   (alert-rules/routes {:sqlite sqlite
                        :id-generator id-generator})])

;; ---------------------------------------------------------
;; Router Component

(defn create-router
  "Create the Reitit ring handler with routes and middleware."
  [opts]
  (ring/ring-handler
    (ring/router
      [(api-routes opts)
       (otlp-routes opts)
       (page-routes opts)]
      {;; Reitit's default conflict detection treats literal and parameterized
       ;; sibling paths (e.g. /alert-rules/new vs /alert-rules/:id) as ambiguous.
       ;; Suppressing the check is safe because reitit still matches literal
       ;; segments before falling back to parameter capture.
       :conflicts nil})
    (ring/routes
      (ring/create-default-handler
        {:not-found (constantly (response/not-found))}))))

(defmethod ig/init-key :router/routes
  [_ opts]
  (mulog/log ::router-init)
  (create-router opts))
