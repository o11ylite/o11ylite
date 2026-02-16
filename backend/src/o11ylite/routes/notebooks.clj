;; ---------------------------------------------------------
;; o11ylite.routes.notebooks
;;
;; Notebooks Inertia page routes.
;; Handles page rendering (GET) and form mutations
;; (POST/PUT/DELETE) following the Inertia.js pattern.
;; Cell mutations redirect back to the notebook show page.
;; ---------------------------------------------------------

(ns o11ylite.routes.notebooks
  (:require
   [jsonista.core :as json]
   [o11ylite.notebook :as notebook]
   [o11ylite.notebook.schema :as notebook-schema]
   [o11ylite.util.response :as response]
   [o11ylite.util.validation :as validation]
   [ring.util.response :as rr])
  (:import
   [com.github.f4b6a3.uuid UuidCreator]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -parse-json-string
  "Parse a JSON-encoded string into Clojure data with keyword keys."
  [s]
  (json/read-value s json/keyword-keys-object-mapper))

(defn- -parse-notebook-params
  "Extract and normalize notebook form params from request body."
  [body]
  {:name (:name body)
   :description (:description body)
   :global_from (or (:global_from body) "now-1h")
   :global_to (or (:global_to body) "now")})

(defn- -parse-cell-params
  "Extract and normalize cell form params from request body.
   Query arrives as a JSON-encoded string (Inertia limitation on nested objects)."
  [body]
  {:title (:title body)
   :query_mode (or (:query_mode body) "events")
   :query (-parse-json-string (:query body))
   :pinned_from (:pinned_from body)
   :pinned_to (:pinned_to body)})

(defn- -notebook-url
  [id]
  (str "/notebooks/" id))

;; ---------------------------------------------------------
;; Notebook Handlers

(defn- -make-list-handler
  "GET /notebooks - List all notebooks."
  [{:keys [sqlite]}]
  (fn [_request]
    (let [notebooks (notebook/list-notebooks sqlite)]
      (response/inertia "Notebooks" {:notebooks notebooks}))))

(defn- -make-new-handler
  "GET /notebooks/new - Render create form."
  [_opts]
  (fn [request]
    (response/inertia "NotebookEdit"
                      {:notebook nil
                       :errors (get-in request [:flash :errors] {})})))

(defn- -make-show-handler
  "GET /notebooks/:id - Render notebook view with cells."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          nb (notebook/get-notebook-by-id sqlite id)]
      (if nb
        (response/inertia "NotebookShow" {:notebook nb})
        (rr/not-found "Notebook not found")))))

(defn- -make-edit-handler
  "GET /notebooks/:id/edit - Render edit form."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          nb (notebook/get-notebook-by-id sqlite id)]
      (if nb
        (response/inertia "NotebookEdit"
                          {:notebook nb
                           :errors (get-in request [:flash :errors] {})})
        (rr/not-found "Notebook not found")))))

(defn- -make-create-handler
  "POST /notebooks - Create a new notebook."
  [{:keys [sqlite]}]
  (fn [request]
    (let [params (-parse-notebook-params (:body request))]
      (if-let [validation-error (notebook-schema/validate-notebook params)]
        (-> (rr/redirect "/notebooks/new" :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (let [id (str (UuidCreator/getTimeOrderedEpoch))]
          (notebook/create-notebook! sqlite id params)
          (rr/redirect (-notebook-url id) :see-other))))))

(defn- -make-update-handler
  "PUT /notebooks/:id - Update notebook metadata."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])
          params (-parse-notebook-params (:body request))]
      (if-let [validation-error (notebook-schema/validate-notebook params)]
        (-> (rr/redirect (str (-notebook-url id) "/edit") :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (do
          (notebook/update-notebook! sqlite id params)
          (rr/redirect (-notebook-url id) :see-other))))))

(defn- -make-delete-handler
  "DELETE /notebooks/:id - Delete a notebook."
  [{:keys [sqlite]}]
  (fn [request]
    (let [id (get-in request [:path-params :id])]
      (notebook/delete-notebook! sqlite id)
      (rr/redirect "/notebooks" :see-other))))

;; ---------------------------------------------------------
;; Cell Handlers

(defn- -make-create-cell-handler
  "POST /notebooks/:id/cells - Add a cell to the notebook."
  [{:keys [sqlite]}]
  (fn [request]
    (let [notebook-id (get-in request [:path-params :id])
          params (-parse-cell-params (:body request))]
      (if-let [validation-error (notebook-schema/validate-cell params)]
        (-> (rr/redirect (-notebook-url notebook-id) :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (let [cell-id (str (UuidCreator/getTimeOrderedEpoch))]
          (notebook/create-cell! sqlite cell-id (assoc params :notebook_id notebook-id))
          (notebook/touch-notebook! sqlite notebook-id)
          (rr/redirect (-notebook-url notebook-id) :see-other))))))

(defn- -make-update-cell-handler
  "PUT /notebooks/:id/cells/:cell-id - Update a cell."
  [{:keys [sqlite]}]
  (fn [request]
    (let [notebook-id (get-in request [:path-params :id])
          cell-id (get-in request [:path-params :cell-id])
          params (-parse-cell-params (:body request))]
      (if-let [validation-error (notebook-schema/validate-cell params)]
        (-> (rr/redirect (-notebook-url notebook-id) :see-other)
            (assoc :flash {:errors (validation/flatten-for-inertia (:error validation-error))}))
        (do
          (notebook/update-cell! sqlite cell-id params)
          (notebook/touch-notebook! sqlite notebook-id)
          (rr/redirect (-notebook-url notebook-id) :see-other))))))

(defn- -make-delete-cell-handler
  "DELETE /notebooks/:id/cells/:cell-id - Delete a cell."
  [{:keys [sqlite]}]
  (fn [request]
    (let [notebook-id (get-in request [:path-params :id])
          cell-id (get-in request [:path-params :cell-id])]
      (notebook/delete-cell! sqlite cell-id)
      (notebook/touch-notebook! sqlite notebook-id)
      (rr/redirect (-notebook-url notebook-id) :see-other))))

(defn- -make-move-cell-handler
  "POST /notebooks/:id/cells/:cell-id/move - Move a cell up or down."
  [{:keys [sqlite]}]
  (fn [request]
    (let [notebook-id (get-in request [:path-params :id])
          cell-id (get-in request [:path-params :cell-id])
          direction (keyword (get-in request [:body :direction]))]
      (when (#{:up :down} direction)
        (notebook/move-cell! sqlite cell-id direction)
        (notebook/touch-notebook! sqlite notebook-id))
      (rr/redirect (-notebook-url notebook-id) :see-other))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Notebook routes."
  [opts]
  ["/notebooks"
   ["" {:get {:handler (-make-list-handler opts)}
        :post {:handler (-make-create-handler opts)}}]
   ["/new" {:get {:handler (-make-new-handler opts)}}]
   ["/:id"
    ["" {:get {:handler (-make-show-handler opts)}
         :put {:handler (-make-update-handler opts)}
         :delete {:handler (-make-delete-handler opts)}}]
    ["/edit" {:get {:handler (-make-edit-handler opts)}}]
    ["/cells"
     ["" {:post {:handler (-make-create-cell-handler opts)}}]
     ["/:cell-id"
      ["" {:put {:handler (-make-update-cell-handler opts)}
           :delete {:handler (-make-delete-cell-handler opts)}}]
      ["/move" {:post {:handler (-make-move-cell-handler opts)}}]]]]])
