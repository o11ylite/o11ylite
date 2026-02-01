;; ---------------------------------------------------------
;; o11ylite.routes.trace
;;
;; Trace detail page route - waterfall view of spans
;; ---------------------------------------------------------

(ns o11ylite.routes.trace
  (:require
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-handler
  "Create trace page handler. Trace ID comes from URL path."
  [_opts]
  (fn [request]
    (let [trace-id (get-in request [:path-params :id])]
      (response/inertia "Trace" {:trace_id trace-id}))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Trace routes."
  [opts]
  ["/trace/:id" {:get {:handler (-make-handler opts)}}])
