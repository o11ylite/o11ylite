;; ---------------------------------------------------------
;; o11ylite.util.response
;;
;; HTTP response helpers
;; ---------------------------------------------------------

(ns o11ylite.util.response
  (:require
   [jsonista.core :as j]))

(defn json
  "Create a JSON response with the given status and body."
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (j/write-value-as-string body)})

(defn not-found
  "Create a 404 not found response."
  []
  (json 404 {:error "Not found"}))
