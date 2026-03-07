;; ---------------------------------------------------------
;; o11ylite.util.response
;;
;; HTTP response helpers
;; ---------------------------------------------------------

(ns o11ylite.util.response
  (:require
    [o11ylite.util.json :as json]
    [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; JSON Responses

(defn json
  "Create a JSON response with the given status and body.
   Large integers (like Snowflake IDs) are automatically serialized as strings
   to preserve precision in JavaScript."
  [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str body)})

(defn not-found
  "Create a 404 JSON response for API routes."
  []
  (json 404 {:error "Not found"}))

;; ---------------------------------------------------------
;; Inertia Responses

(defn inertia
  "Create an Inertia response for a component with optional props.
   This is used by route handlers - the middleware converts it to HTML/JSON."
  ([component]
   (inertia component {}))
  ([component props]
   (rr/response {:component component
                 :props props})))

(defn inertia-error
  "Create an Inertia error response that renders the Error page.
   Sets both the HTTP status and passes it as a prop to the component."
  [status]
  (-> (inertia "Error" {:status status})
      (rr/status status)))
