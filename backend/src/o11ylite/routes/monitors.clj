;; ---------------------------------------------------------
;; o11ylite.routes.monitors
;;
;; Monitor routes - rules and notifications
;; ---------------------------------------------------------

(ns o11ylite.routes.monitors
  (:require
   [o11ylite.util.response :as response]))

(defn rules-handler
  "Rules page handler - renders Inertia MonitorRules component."
  [_request]
  (response/inertia "MonitorRules" {}))

(defn notifications-handler
  "Notifications page handler - renders Inertia MonitorNotifications component."
  [_request]
  (response/inertia "MonitorNotifications" {}))

(defn routes
  "Monitor routes."
  [_opts]
  ["/monitors"
   ["/rules" {:get {:handler rules-handler}}]
   ["/notifications" {:get {:handler notifications-handler}}]])
