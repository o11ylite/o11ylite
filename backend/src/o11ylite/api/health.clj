;; ---------------------------------------------------------
;; o11ylite.api.health
;;
;; Health check API endpoints
;; ---------------------------------------------------------

(ns o11ylite.api.health
  (:require
    [o11ylite.util.response :as response]))

(defn handler
  "Health check endpoint handler."
  [_request]
  (response/json 200 {:status "ok"}))

(defn routes
  "Health check API routes."
  [_opts]
  [["/status" {:get {:handler handler}}]
   ["/health" {:get {:handler handler}}]])
