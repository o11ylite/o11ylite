;; ---------------------------------------------------------
;; o11ylite.routes.health
;;
;; Health check routes
;; ---------------------------------------------------------

(ns o11ylite.routes.health
  (:require
   [o11ylite.util.response :as response]))

(defn handler
  "Health check endpoint handler."
  [_request]
  (response/json 200 {:status "ok"}))

(defn routes
  "Health check routes."
  [_opts]
  ["/health" {:get {:handler handler}}])
