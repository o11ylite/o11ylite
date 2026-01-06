;; ---------------------------------------------------------
;; o11ylite.store.metrics.metadata
;;
;; Metric metadata read/write operations for SQLite.
;; Provides access to metric definitions (description, unit, type, attributes).
;;
;; get-metric is TTL-cached (1 second) for use in hot paths.
;; ---------------------------------------------------------

(ns o11ylite.store.metrics.metadata
  (:require
   [clojure.core.memoize :as memo]
   [next.jdbc :as jdbc]
   [jsonista.core :as json]))

;; ---------------------------------------------------------
;; SQLite Metadata Queries

(defn- -get-metric
  "Get metadata for a specific metric (uncached).
   Returns {:name :description :unit :metric_type :attributes} or nil."
  [sqlite metric-name]
  (let [rows (jdbc/execute! sqlite
                            ["SELECT name, description, unit, metric_type, attributes
                              FROM metrics_metadata WHERE name = ?"
                             metric-name])]
    (when-let [row (first rows)]
      {:name (:metrics_metadata/name row)
       :description (:metrics_metadata/description row)
       :unit (:metrics_metadata/unit row)
       :metric_type (some-> (:metrics_metadata/metric_type row) keyword)
       :attributes (some-> (:metrics_metadata/attributes row)
                           json/read-value
                           set)})))

(def get-metric
  "Get metadata for a specific metric with 1-second TTL cache.
   Returns {:name :description :unit :metric_type :attributes} or nil.

   Cached to avoid IO in hot paths. Cache is keyed by [sqlite metric-name],
   so ensure sqlite datasource has stable identity (e.g., from Integrant)."
  (memo/ttl -get-metric :ttl/threshold 1000))

(defn get-all-metrics
  "Get metadata for all metrics.
   Returns a map of metric-name -> {:description :unit :metric_type :attributes}."
  [sqlite]
  (let [rows (jdbc/execute! sqlite
                            ["SELECT name, description, unit, metric_type, attributes
                              FROM metrics_metadata"])]
    (->> rows
         (map (fn [row]
                [(:metrics_metadata/name row)
                 {:description (:metrics_metadata/description row)
                  :unit (:metrics_metadata/unit row)
                  :metric_type (some-> (:metrics_metadata/metric_type row) keyword)
                  :attributes (some-> (:metrics_metadata/attributes row)
                                      json/read-value
                                      set)}]))
         (into {}))))

(defn list-metric-names
  "Get list of all metric names.
   Returns a vector of strings."
  [sqlite]
  (let [rows (jdbc/execute! sqlite
                            ["SELECT name FROM metrics_metadata ORDER BY name"])]
    (mapv :metrics_metadata/name rows)))

;; ---------------------------------------------------------
;; Writes

(defn- -upsert-metric!
  "Upsert a single metric metadata entry to SQLite.
   On INSERT: sets all fields.
   On UPDATE: only updates description and attributes (unit, metric_type are immutable)."
  [sqlite metric-name {:keys [description unit metric_type attributes]}]
  (let [attrs-json (json/write-value-as-string (vec (or attributes [])))]
    (jdbc/execute! sqlite
                   ["INSERT INTO metrics_metadata (name, description, unit, metric_type, attributes, updated_at)
                     VALUES (?, ?, ?, ?, ?, datetime('now'))
                     ON CONFLICT(name) DO UPDATE SET
                       description = COALESCE(excluded.description, metrics_metadata.description),
                       attributes = (
                         SELECT json_group_array(DISTINCT value)
                         FROM (
                           SELECT value FROM json_each(metrics_metadata.attributes)
                           UNION
                           SELECT value FROM json_each(excluded.attributes)
                         )
                       ),
                       updated_at = datetime('now')"
                    metric-name description unit (some-> metric_type name) attrs-json])))

(defn upsert-metrics!
  "Upsert metric metadata entries to SQLite.
   On INSERT: sets all fields.
   On UPDATE: only updates description and attributes (unit, metric_type are immutable)."
  [sqlite metrics-metadata]
  (doseq [[metric-name meta-entry] metrics-metadata]
    (-upsert-metric! sqlite metric-name meta-entry)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Get specific metric metadata
  ;; (get-metric sqlite "cpu.utilization")
  ;; => {:name "cpu.utilization"
  ;;     :description "CPU usage"
  ;;     :unit "%"
  ;;     :metric_type :gauge
  ;;     :attributes #{"host.name" "cpu.core"}}

  ;; List all metrics
  ;; (list-metric-names sqlite)
  ;; => ["cpu.utilization" "http.request.duration" ...]

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
