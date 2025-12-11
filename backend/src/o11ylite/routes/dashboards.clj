;; ---------------------------------------------------------
;; o11ylite.routes.dashboards
;;
;; Dashboards page routes - saved visualizations
;; ---------------------------------------------------------

(ns o11ylite.routes.dashboards
  (:require
   [o11ylite.util.response :as response]))

(defn handler
  "Dashboards page handler - renders Inertia Dashboards component."
  [_request]
  (response/inertia "Dashboards" {}))

(defn routes
  "Dashboards routes."
  [_opts]
  ["/dashboards" {:get {:handler handler}}])
