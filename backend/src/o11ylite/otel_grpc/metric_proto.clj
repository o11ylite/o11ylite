;; ---------------------------------------------------------
;; o11ylite.otel-grpc.metric-proto
;;
;; Converts OTLP metrics protobuf to internal format.
;; Returns {:data-points [...] :metrics-metadata [...]}
;;
;; Key design decisions:
;; - All attributes are strings (no type suffix)
;; - Metric metadata extracted once per metric definition
;; - Data points are flat maps ready for DuckDB insertion
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.metric-proto
  (:require
   [clojure.string :as str]
   [o11ylite.otel-grpc.proto :as proto])
  (:import
   [io.opentelemetry.proto.resource.v1 Resource]
   [io.opentelemetry.proto.metrics.v1
    ResourceMetrics ScopeMetrics Metric Metric$DataCase
    NumberDataPoint NumberDataPoint$ValueCase]
   [io.opentelemetry.proto.collector.metrics.v1
    ExportMetricsServiceRequest
    ExportMetricsServiceResponse
    ExportMetricsPartialSuccess]
   [java.time Instant]))

;; ---------------------------------------------------------
;; Attribute helpers

(defn- -stringify-value
  "Convert any value to string for metric attributes."
  [v]
  (cond
    (nil? v) ""
    (string? v) v
    (keyword? v) (name v)
    :else (str v)))

(defn- -extract-string-attributes
  "Extract attributes as string key-value pairs.
   Returns map of string keys to string values."
  [kvs]
  (->> (proto/extract-attributes kvs)
       (map (fn [[k v]] [k (-stringify-value v)]))
       (into {})))

(defn- -prefix-string-attributes
  "Add 'attr.' prefix to attribute keys and convert to keywords.
   All values are coerced to strings."
  [& attr-maps]
  (reduce-kv (fn [acc k v]
               (let [safe-key (str/replace (str k) "/" ".")]
                 (assoc acc (keyword (str "attr." safe-key)) (-stringify-value v))))
             {}
             (apply merge attr-maps)))

;; ---------------------------------------------------------
;; Data point extraction

(defn- -number-data-point-value
  "Extract numeric value from NumberDataPoint."
  [^NumberDataPoint dp]
  (condp = (.getValueCase dp)
    NumberDataPoint$ValueCase/AS_DOUBLE (.getAsDouble dp)
    NumberDataPoint$ValueCase/AS_INT (double (.getAsInt dp))
    0.0))

(defn- -extract-attribute-names
  "Extract attribute names (without prefix) from a NumberDataPoint."
  [^NumberDataPoint dp]
  (->> (.getAttributesList dp)
       (map #(.getKey ^io.opentelemetry.proto.common.v1.KeyValue %))
       set))

(defn- -gauge-data-point->map
  "Convert a Gauge NumberDataPoint to a data point map."
  [^NumberDataPoint dp metric-name resource-attrs scope-name scope-version service-name observed-time]
  (let [dp-attrs (-extract-string-attributes (.getAttributesList dp))
        prefixed-attrs (-prefix-string-attributes resource-attrs dp-attrs)]
    (merge
     {:name metric-name
      :service service-name
      :timestamp (or (proto/nanos->instant (.getTimeUnixNano dp))
                     observed-time)
      :value (-number-data-point-value dp)
      :scope.name scope-name
      :scope.version scope-version
      :meta.observed_time observed-time}
     prefixed-attrs)))

(defn- -gauge->data-points
  "Convert Gauge metric to sequence of data point maps."
  [^Metric metric resource-attrs scope-name scope-version service-name observed-time]
  (let [metric-name (.getName metric)
        gauge (.getGauge metric)]
    (for [^NumberDataPoint dp (.getDataPointsList gauge)]
      (-gauge-data-point->map dp metric-name resource-attrs scope-name scope-version service-name observed-time))))

(defn- -gauge->metadata
  "Extract metadata from a Gauge metric."
  [^Metric metric]
  (let [gauge (.getGauge metric)
        ;; Collect all attribute names across all data points
        all-attr-names (->> (.getDataPointsList gauge)
                            (mapcat -extract-attribute-names)
                            set)]
    {:name (.getName metric)
     :description (.getDescription metric)
     :unit (.getUnit metric)
     :metric_type :gauge
     :attributes all-attr-names}))

;; ---------------------------------------------------------
;; Metric type dispatch

(defn- -metric->data-points
  "Convert Metric protobuf to sequence of data point maps."
  [^Metric metric resource-attrs scope-name scope-version service-name observed-time]
  (condp = (.getDataCase metric)
    Metric$DataCase/GAUGE
    (-gauge->data-points metric resource-attrs scope-name scope-version service-name observed-time)

    ;; TODO: Implement sum and histogram
    Metric$DataCase/SUM nil
    Metric$DataCase/HISTOGRAM nil
    Metric$DataCase/EXPONENTIAL_HISTOGRAM nil
    Metric$DataCase/SUMMARY nil

    nil))

(defn- -metric->metadata
  "Extract metadata from Metric protobuf."
  [^Metric metric]
  (condp = (.getDataCase metric)
    Metric$DataCase/GAUGE (-gauge->metadata metric)

    ;; TODO: Implement sum and histogram
    Metric$DataCase/SUM nil
    Metric$DataCase/HISTOGRAM nil
    Metric$DataCase/EXPONENTIAL_HISTOGRAM nil
    Metric$DataCase/SUMMARY nil

    nil))

;; ---------------------------------------------------------
;; Metadata merging

(defn- -merge-metadata
  "Merge multiple metadata entries for the same metric name.
   Attributes are unioned, other fields use last-write-wins."
  [metadata-seq]
  (->> metadata-seq
       (group-by :name)
       (map (fn [[_name entries]]
              (reduce (fn [acc entry]
                        (-> acc
                            (merge (dissoc entry :attributes))
                            (update :attributes into (:attributes entry))))
                      (first entries)
                      (rest entries))))
       vec))

;; ---------------------------------------------------------
;; Public API

(defn parse-metrics-request
  "Parse ExportMetricsServiceRequest protobuf into data points and metadata.

   Returns:
     {:data-points      [...] - Flat maps ready for DuckDB insertion
      :metrics-metadata [...] - Metric definitions for metadata table}

   Rejects (skips) resource metrics without service.name."
  [^ExportMetricsServiceRequest request]
  (let [observed-time (Instant/now)
        ;; Collect data points and metadata in parallel
        results (for [^ResourceMetrics resource-metrics (.getResourceMetricsList request)
                      :let [^Resource resource (.getResource resource-metrics)
                            service-name (proto/extract-service-name resource)]
                      :when service-name
                      :let [resource-attrs (-extract-string-attributes (.getAttributesList resource))]
                      ^ScopeMetrics scope-metrics (.getScopeMetricsList resource-metrics)
                      :let [scope (proto/extract-scope (.getScope scope-metrics))
                            scope-name (:name scope)
                            scope-version (:version scope)]
                      ^Metric metric (.getMetricsList scope-metrics)
                      :let [data-points (-metric->data-points metric resource-attrs scope-name scope-version service-name observed-time)
                            metadata (-metric->metadata metric)]
                      :when (and data-points metadata)]
                  {:data-points data-points
                   :metadata metadata})
        all-data-points (mapcat :data-points results)
        all-metadata (map :metadata results)]
    {:data-points (vec all-data-points)
     :metrics-metadata (-merge-metadata all-metadata)}))

(defn count-rejected-data-points
  "Count data points that would be rejected (no service.name)."
  [^ExportMetricsServiceRequest request]
  (->> (.getResourceMetricsList request)
       (filter #(nil? (proto/extract-service-name (.getResource ^ResourceMetrics %))))
       (mapcat #(.getScopeMetricsList ^ResourceMetrics %))
       (mapcat #(.getMetricsList ^ScopeMetrics %))
       (mapcat (fn [^Metric m]
                 (condp = (.getDataCase m)
                   Metric$DataCase/GAUGE (.getDataPointsList (.getGauge m))
                   ;; TODO: Handle other metric types
                   [])))
       count))

(defn metric-response->proto
  "Convert Clojure response map to ExportMetricsServiceResponse.

   Accepts:
   {:rejected-data-point-count 0
    :error-message \"\"} or nil for success"
  [{:keys [rejected-data-point-count error-message]
    :or {rejected-data-point-count 0 error-message ""}}]
  (let [partial-success (-> (ExportMetricsPartialSuccess/newBuilder)
                            (.setRejectedDataPoints rejected-data-point-count)
                            (.setErrorMessage error-message)
                            (.build))]
    (-> (ExportMetricsServiceResponse/newBuilder)
        (.setPartialSuccess partial-success)
        (.build))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example output from parse-metrics-request:
  {:data-points
   [{:name "cpu.utilization"
     :service "my-service"
     :timestamp #inst "2024-01-15T10:30:00Z"
     :value 42.5
     :scope.name "system-metrics"
     :scope.version "1.0.0"
     :meta.observed_time #inst "2024-01-15T10:30:01Z"
     ;; All attributes are strings with attr. prefix
     :attr.service.name "my-service"
     :attr.host.name "localhost"
     :attr.cpu.core "0"}]

   :metrics-metadata
   [{:name "cpu.utilization"
     :description "CPU utilization percentage"
     :unit "%"
     :metric_type :gauge
     :attributes #{"host.name" "cpu.core"}}]}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
