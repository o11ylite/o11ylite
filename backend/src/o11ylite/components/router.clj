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
   [jsonista.core :as json]
   [o11ylite.util.response :as response]
   [o11ylite.inertia.middleware :as inertia]
   [o11ylite.api.health :as api.health]
   [o11ylite.api.query :as api.query]
   [o11ylite.otel-http :as otel-http]
   [o11ylite.routes.home :as home]
   [o11ylite.routes.explore :as explore]
   [o11ylite.routes.dashboards :as dashboards]
   [o11ylite.routes.monitors :as monitors]))

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
;; Routes

(defn api-routes
  "API routes - no CSRF, no sessions."
  [{:keys [duckdb]}]
  ["/api" {:middleware [wrap-api-defaults]}
   (api.health/routes {})
   (api.query/routes {:duckdb duckdb})])

(defn otlp-routes
  "OTLP HTTP routes - raw body handling, no JSON parsing middleware.
   These routes handle their own protobuf/JSON parsing."
  [{:keys [event-metadata event-batcher]}]
  (otel-http/routes {:event-metadata event-metadata
                     :event-batcher event-batcher}))

(defn page-routes
  "Page routes - site defaults + Inertia middleware."
  [{:keys [inertia sqlite event-metadata]}]
  ["" {:middleware [wrap-site-defaults
                    inertia/wrap-csrf-cookie
                    (make-wrap-inertia inertia)]}
   (home/routes {})
   (explore/routes {:sqlite sqlite :event-metadata event-metadata})
   (dashboards/routes {})
   (monitors/routes {})])

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
    {:data {:middleware [exception/exception-middleware]}})
   (ring/routes
    (ring/create-default-handler
     {:not-found (constantly (response/not-found))}))))

(defmethod ig/init-key :router/routes
  [_ opts]
  (mulog/log ::router-init)
  (create-router opts))
