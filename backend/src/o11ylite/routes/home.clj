;; ---------------------------------------------------------
;; o11ylite.routes.home
;;
;; Home page routes - redirects to explore
;; ---------------------------------------------------------

(ns o11ylite.routes.home
  (:require
    [ring.util.response :as rr]))

(defn handler
  "Home page handler - redirects to explore."
  [_request]
  (rr/redirect "/explore"))

(defn routes
  "Home routes."
  [_opts]
  ["/" {:get {:handler handler}}])
