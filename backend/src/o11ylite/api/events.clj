;; ---------------------------------------------------------
;; o11ylite.api.events
;;
;; Events metadata API endpoints.
;; Provides access to event field definitions for the frontend query builder.
;;
;; Endpoints:
;;   GET /api/events/fields - List all event fields with types
;; ---------------------------------------------------------

(ns o11ylite.api.events
  (:require
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -fields-map->vec
  "Convert fields map to sorted vector of {:name :type} maps."
  [fields-map]
  (->> fields-map
       (map (fn [[k v]] {:name (name k) :type (:type v)}))
       (sort-by :name)
       vec))

;; ---------------------------------------------------------
;; Handlers

(defn- -list-fields-handler
  "List all event fields with their types.
   Returns [{:name :type} ...]."
  [event-metadata-component]
  (fn [_request]
    (response/json 200 (-fields-map->vec
                         (event-metadata/get-fields event-metadata-component)))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Events metadata API routes.

   Arguments:
     opts - Map with :event-metadata component"
  [{:keys [event-metadata]}]
  [["/events"
    ["/fields" {:get {:handler (-list-fields-handler event-metadata)}}]]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example: List all event fields
  ;; GET /api/events/fields
  ;; => [{:name "service" :type "string"}
  ;;     {:name "span.duration_ms" :type "float"}
  ;;     {:name "timestamp" :type "instant"}
  ;;     ...]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
