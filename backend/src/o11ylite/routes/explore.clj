;; ---------------------------------------------------------
;; o11ylite.routes.explore
;;
;; Explore page routes - ad-hoc querying of traces/logs/metrics
;; ---------------------------------------------------------

(ns o11ylite.routes.explore
  (:require
   [o11ylite.components.event-metadata :as event-metadata]
   [o11ylite.store.services :as services]
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

(defn -make-handler
  "Create explore page handler with dependencies."
  [{:keys [sqlite event-metadata]}]
  (fn [_request]
    (let [services (services/get-services sqlite)
          fields (-fields-map->vec (event-metadata/get-fields event-metadata))]
      (response/inertia "Explore" {:services services
                                   :fields fields}))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Explore routes."
  [opts]
  ["/explore" {:get {:handler (-make-handler opts)}}])
