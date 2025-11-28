;; ---------------------------------------------------------
;; o11ylite.routes.home
;;
;; Home page routes
;; ---------------------------------------------------------

(ns o11ylite.routes.home
  (:require
   [o11ylite.util.response :as response]))

(defn handler
  "Home page handler - renders Inertia Home component."
  [_request]
  (response/inertia "Home" {:greeting "Welcome to O11yLite"}))

(defn routes
  "Home routes."
  [_opts]
  ["/" {:get {:handler handler}}])
