;; ---------------------------------------------------------
;; o11ylite.routes.explore
;;
;; Explore page routes - ad-hoc querying of traces/logs/metrics
;; ---------------------------------------------------------

(ns o11ylite.routes.explore
  (:require
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Handlers

(defn -make-handler
  "Create explore page handler."
  [_opts]
  (fn [_request]
    (response/inertia "Explore" {})))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Explore routes."
  [opts]
  ["/explore" {:get {:handler (-make-handler opts)}}])
