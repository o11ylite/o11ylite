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
   [o11ylite.util.response :as response]
   [o11ylite.inertia.middleware :as inertia]
   [o11ylite.routes.home :as home]
   [o11ylite.routes.health :as health]
   [o11ylite.routes.explore :as explore]
   [o11ylite.routes.dashboards :as dashboards]
   [o11ylite.routes.monitors :as monitors]))

;; ---------------------------------------------------------
;; Middleware Factories

(defn wrap-api-defaults
  "Wrap handler with api-defaults middleware."
  [handler]
  (wrap-defaults handler api-defaults))

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
  [_opts]
  ["/api" {:middleware [wrap-api-defaults]}
   ["/status" {:get {:handler health/handler}}]])

(defn page-routes
  "Page routes - site defaults + Inertia middleware."
  [{:keys [inertia]}]
  ["" {:middleware [wrap-site-defaults
                    inertia/wrap-csrf-cookie
                    (make-wrap-inertia inertia)]}
   (home/routes {})
   (health/routes {})
   (explore/routes {})
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
     (page-routes opts)]
    {:data {:middleware [exception/exception-middleware]}})
   (ring/routes
    (ring/create-default-handler
     {:not-found (constantly (response/not-found))}))))

(defmethod ig/init-key :router/routes
  [_ opts]
  (mulog/log ::router-init)
  (create-router opts))
