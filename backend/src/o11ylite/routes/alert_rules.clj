;; ---------------------------------------------------------
;; o11ylite.routes.alert-rules
;;
;; Alert rules routes
;; ---------------------------------------------------------

(ns o11ylite.routes.alert-rules
  (:require
    [o11ylite.util.response :as response]))

(defn alert-rules-handler
  "Alert rules page handler - renders Inertia AlertRules component."
  [_request]
  (response/inertia "AlertRules" {}))

(defn routes
  "Alert rules routes."
  [_opts]
  ["/alert-rules" {:get {:handler alert-rules-handler}}])
