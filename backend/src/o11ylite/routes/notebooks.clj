;; ---------------------------------------------------------
;; o11ylite.routes.notebooks
;;
;; Notebooks page routes - multi-query documentation
;; ---------------------------------------------------------

(ns o11ylite.routes.notebooks
  (:require
    [o11ylite.util.response :as response]))

(defn notebooks-handler
  "Notebooks page handler - renders Inertia Notebooks component."
  [_request]
  (response/inertia "Notebooks" {}))

(defn routes
  "Notebooks routes."
  [_opts]
  ["/notebooks" {:get {:handler notebooks-handler}}])
