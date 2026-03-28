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
    [o11ylite.components.blocked-fields :as blocked-fields]
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.util.response :as response]))

;; ---------------------------------------------------------
;; Private Helpers

(defn- -fields-map->vec
  "Convert fields map to sorted vector of {:name :type} maps,
   excluding any fields in the blocked set."
  [fields-map blocked-set]
  (->> fields-map
       (remove (fn [[k _]] (contains? blocked-set (name k))))
       (map (fn [[k v]] {:name (name k) :type (:type v)}))
       (sort-by :name)
       vec))

;; ---------------------------------------------------------
;; Handlers

(defn- -list-fields-handler
  "List all event fields with their types, excluding blocked fields.
   Returns [{:name :type} ...]."
  [event-metadata-component blocked-fields-component]
  (fn [_request]
    (let [blocked-set (blocked-fields/get-blocked-event-fields blocked-fields-component)]
      (response/json 200 (-fields-map->vec
                           (event-metadata/get-fields event-metadata-component)
                           blocked-set)))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Events metadata API routes.

   Arguments:
     opts - Map with :event-metadata and :blocked-fields components"
  [{:keys [event-metadata blocked-fields]}]
  [["/events"
    ["/fields" {:get {:handler (-list-fields-handler event-metadata blocked-fields)}}]]])

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
