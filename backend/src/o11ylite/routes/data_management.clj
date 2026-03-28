;; ---------------------------------------------------------
;; o11ylite.routes.data-management
;;
;; Data Management Inertia page routes.
;; Lets users view and manage event fields, metrics, and
;; metric attributes — including blocking and deleting.
;; ---------------------------------------------------------

(ns o11ylite.routes.data-management
  (:require
    [o11ylite.auth.middleware :as auth-mw]
    [o11ylite.components.event-metadata :as event-metadata]
    [o11ylite.components.blocked-fields :as blocked-fields]
    [o11ylite.store.metrics.metadata :as metrics-metadata]
    [o11ylite.store.schema :as schema]
    [o11ylite.util.response :as response]
    [ring.util.response :as rr]))

;; ---------------------------------------------------------
;; Helpers

(defn- -field-category
  "Categorize a field name as :system or :attribute."
  [field-name]
  (if (.startsWith ^String field-name "attr.")
    "attribute"
    "system"))

(defn- -build-event-fields
  "Build the event_fields prop by joining the metadata cache with blocked set.
   Returns a sorted vector of {:name :type :category :status} maps."
  [event-metadata blocked-fields]
  (let [fields (event-metadata/get-fields event-metadata)
        blocked (blocked-fields/get-blocked-event-fields blocked-fields)]
    (->> fields
         (map (fn [[field-key {:keys [type]}]]
                (let [n (name field-key)]
                  {:name n
                   :type (name type)
                   :category (-field-category n)
                   :status (if (contains? blocked n)
                             "blocked"
                             "active")})))
         (sort-by :name)
         vec)))

(defn- -build-metrics
  "Build the metrics prop from SQLite metadata.
   Returns a sorted vector of {:name :metric_type :unit :description :attributes} maps."
  [sqlite]
  (let [all-metrics (metrics-metadata/get-all-metrics sqlite)]
    (->> all-metrics
         (map (fn [[metric-name {:keys [metric_type unit description attributes]}]]
                {:name metric-name
                 :metric_type (some-> metric_type name)
                 :unit (or unit "")
                 :description (or description "")
                 :attributes (vec (sort (or attributes [])))}))
         (sort-by :name)
         vec)))

(defn- -build-metric-attributes
  "Build the metric_attributes prop from DuckDB DESCRIBE joined with blocked set.
   Only includes attr.* columns. Returns a sorted vector of {:name :status} maps."
  [duckdb blocked-fields]
  (let [attr-fields (schema/fetch-metric-attr-fields duckdb)
        blocked (blocked-fields/get-blocked-metric-fields blocked-fields)]
    (mapv (fn [field-name]
            {:name field-name
             :status (if (contains? blocked field-name)
                       "blocked"
                       "active")})
          attr-fields)))

;; ---------------------------------------------------------
;; Flash helpers

(defn- -flash-message
  "Build a redirect to the data management page with a flash message."
  [message]
  (-> (rr/redirect "/system/data-management" :see-other)
      (assoc :flash {:message message})))

(defn- -pluralize
  [n word]
  (if (= n 1)
    (str "1 " word)
    (str n " " word "s")))

;; ---------------------------------------------------------
;; Handlers

(defn- -make-page-handler
  "GET /system/data-management — render the Data Management page with all props."
  [{:keys [sqlite event-metadata duckdb blocked-fields]}]
  (fn [_request]
    (response/inertia "DataManagement"
                      {:event_fields (-build-event-fields event-metadata blocked-fields)
                       :metrics (-build-metrics sqlite)
                       :metric_attributes (-build-metric-attributes duckdb blocked-fields)})))

(defn- -make-event-fields-status-handler
  "PUT /system/data-management/event-fields/status — block or unblock event fields."
  [{:keys [sqlite blocked-fields]}]
  (fn [request]
    (let [fields (get-in request [:body :fields])
          status (get-in request [:body :status])
          n (count fields)]
      (case status
        "blocked" (blocked-fields/block-event-fields! blocked-fields sqlite fields)
        "active" (blocked-fields/unblock-event-fields! blocked-fields sqlite fields))
      (-flash-message (str (if (= status "blocked") "Blocked " "Activated ")
                           (-pluralize n "event field"))))))

(defn- -make-event-fields-delete-handler
  "DELETE /system/data-management/event-fields — drop columns + auto-block."
  [{:keys [sqlite duckdb event-metadata blocked-fields]}]
  (fn [request]
    (let [fields (get-in request [:body :fields])
          n (count fields)]
      ;; 1. Block first to prevent schema evolution from re-adding
      (blocked-fields/block-event-fields! blocked-fields sqlite fields)
      ;; 2. DROP COLUMN from DuckDB
      (schema/drop-event-fields! duckdb fields)
      ;; 3. Refresh event-metadata cache so dropped columns disappear (sync)
      @(event-metadata/refresh! event-metadata)
      (-flash-message (str "Deleted " (-pluralize n "event field"))))))

(defn- -make-metric-attrs-status-handler
  "PUT /system/data-management/metric-attributes/status — block or unblock metric attributes."
  [{:keys [sqlite blocked-fields]}]
  (fn [request]
    (let [fields (get-in request [:body :fields])
          status (get-in request [:body :status])
          n (count fields)]
      (case status
        "blocked" (blocked-fields/block-metric-fields! blocked-fields sqlite fields)
        "active" (blocked-fields/unblock-metric-fields! blocked-fields sqlite fields))
      (-flash-message (str (if (= status "blocked") "Blocked " "Activated ")
                           (-pluralize n "metric attribute"))))))

(defn- -make-metric-attrs-delete-handler
  "DELETE /system/data-management/metric-attributes — drop columns + auto-block."
  [{:keys [sqlite duckdb blocked-fields]}]
  (fn [request]
    (let [fields (get-in request [:body :fields])
          n (count fields)]
      ;; 1. Block first to prevent schema evolution from re-adding
      (blocked-fields/block-metric-fields! blocked-fields sqlite fields)
      ;; 2. DROP COLUMN from DuckDB
      (schema/drop-metric-fields! duckdb fields)
      (-flash-message (str "Deleted " (-pluralize n "metric attribute"))))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "Data management routes. Requires admin scope when auth is active."
  [{:keys [auth-config] :as opts}]
  (let [middleware (when-not (:open-mode? auth-config)
                     [(auth-mw/make-wrap-require-scope "admin")])]
    ["/system/data-management" {:middleware middleware}
     ["" {:get {:handler (-make-page-handler opts)}}]
     ["/event-fields/status" {:put {:handler (-make-event-fields-status-handler opts)}}]
     ["/event-fields" {:delete {:handler (-make-event-fields-delete-handler opts)}}]
     ["/metric-attributes/status" {:put {:handler (-make-metric-attrs-status-handler opts)}}]
     ["/metric-attributes" {:delete {:handler (-make-metric-attrs-delete-handler opts)}}]]))
