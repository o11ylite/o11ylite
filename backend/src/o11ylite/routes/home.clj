;; ---------------------------------------------------------
;; o11ylite.routes.home
;;
;; Home page routes
;; ---------------------------------------------------------

(ns o11ylite.routes.home
  (:require
   [o11ylite.util.response :as response]))

(defn handler
  "Home page handler."
  [_request]
  (response/json 200 {:message "Welcome to o11ylite"}))

(defn routes
  "Home routes."
  [_opts]
  ["/" {:get {:handler handler}}])
