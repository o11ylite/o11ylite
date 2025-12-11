;; ---------------------------------------------------------
;; o11ylite.routes.explore
;;
;; Explore page routes - ad-hoc querying of traces/logs/metrics
;; ---------------------------------------------------------

(ns o11ylite.routes.explore
  (:require
   [o11ylite.util.response :as response]))

(defn handler
  "Explore page handler - renders Inertia Explore component."
  [_request]
  (response/inertia "Explore" {}))

(defn routes
  "Explore routes."
  [_opts]
  ["/explore" {:get {:handler handler}}])
