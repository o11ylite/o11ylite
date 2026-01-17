;; ---------------------------------------------------------
;; o11ylite.test-helpers.metric-ingest
;;
;; Metric ingestion helpers for integration tests.
;; Provides random metric generation and direct ingestion via batcher.
;; ---------------------------------------------------------

(ns o11ylite.test-helpers.metric-ingest
  (:require
   [o11ylite.store.metrics.ingest :as metrics.ingest])
  (:import
   [java.time Instant]))

;; ---------------------------------------------------------
;; Random Data Generators

(def ^:private metric-names
  ["cpu.utilization" "memory.usage" "http.request.duration" "db.query.count" "cache.hit.ratio"])

(def ^:private service-names
  ["api-gateway" "user-service" "order-service" "payment-service" "inventory-service"])

(def ^:private units
  ["%" "bytes" "ms" "1" "1"])

(defn- -random-timestamp
  "Generate a random timestamp within the last hour."
  []
  (let [now (System/currentTimeMillis)
        one-hour-ms (* 60 60 1000)
        offset (rand-int one-hour-ms)]
    (Instant/ofEpochMilli (- now offset))))

;; ---------------------------------------------------------
;; Public API

(defn make-random-metric-data-point
  "Generate a random metric data point with realistic field values.

   Optional overrides can be provided to set specific fields.

   Example:
     (make-random-metric-data-point)
     (make-random-metric-data-point {:name \"my.metric\" :value 100.0})"
  ([] (make-random-metric-data-point {}))
  ([overrides]
   (let [idx (rand-int (count metric-names))]
     (merge {:name (nth metric-names idx)
             :service (rand-nth service-names)
             :timestamp (-random-timestamp)
             :value (+ 0.1 (rand 100.0))
             :scope.name "test-metrics"
             :scope.version "1.0.0"
             :meta.observed_time (Instant/now)
             :attr.host.name (str "server-" (rand-int 10))}
            overrides))))

(defn make-random-metric-data-points
  "Generate n random metric data points.

   Optional overrides are applied to all data points.

   Example:
     (make-random-metric-data-points 10)
     (make-random-metric-data-points 5 {:service \"test-service\"})"
  ([n] (make-random-metric-data-points n {}))
  ([n overrides]
   (repeatedly n #(make-random-metric-data-point overrides))))

(defn make-metrics-metadata
  "Generate metadata map for the given metric data points.

   Returns a map of metric-name -> {:description :unit :metric_type :attributes}"
  [data-points]
  (let [names (distinct (map :name data-points))]
    (into {}
          (map-indexed
           (fn [idx metric-name]
             [metric-name
              {:description (str "Test metric " metric-name)
               :unit (nth units (mod idx (count units)))
               :metric_type :gauge
               :attributes #{"host.name"}}])
           names))))

(defn ingest-metrics!
  "Ingest metrics directly via the batcher, bypassing gRPC.
   Blocks until metrics are persisted to storage.

   Arguments:
     metric-batcher - The metric batcher component
     sqlite         - SQLite datasource
     norm           - Temporality normalizer component
     data-points    - Collection of metric data point maps
     metadata       - Map of metric-name -> metadata

   Returns:
     Result map from metrics.ingest/ingest-metrics!

   Example:
     (let [dps (make-random-metric-data-points 10)
           meta (make-metrics-metadata dps)]
       (ingest-metrics! batcher sqlite norm dps meta))"
  [metric-batcher sqlite norm data-points metadata]
  (metrics.ingest/ingest-metrics! metric-batcher sqlite norm data-points metadata))

(defn ingest-sample-metrics!
  "Generate and ingest n random metrics. Returns the ingested data points.

   Arguments:
     metric-batcher - The metric batcher component
     sqlite         - SQLite datasource
     norm           - Temporality normalizer component
     n              - Number of data points to generate and ingest

   Optional:
     overrides      - Map of field overrides for all data points

   Returns:
     Vector of the ingested data point maps (for verification)

   Example:
     (ingest-sample-metrics! batcher sqlite norm 10)
     (ingest-sample-metrics! batcher sqlite norm 5 {:service \"test-svc\"})"
  ([metric-batcher sqlite norm n]
   (ingest-sample-metrics! metric-batcher sqlite norm n {}))
  ([metric-batcher sqlite norm n overrides]
   (let [data-points (vec (make-random-metric-data-points n overrides))
         metadata (make-metrics-metadata data-points)]
     (ingest-metrics! metric-batcher sqlite norm data-points metadata)
     data-points)))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (require '[o11ylite.test-helpers :as h])

  ;; Generate random metric data points
  (make-random-metric-data-point)
  (make-random-metric-data-points 3)
  (make-random-metric-data-points 2 {:service "my-service"})

  ;; Generate metadata for data points
  (let [dps (make-random-metric-data-points 3)]
    (make-metrics-metadata dps))

  ;; In a test with h/*system* bound:
  ;; (ingest-sample-metrics! (:ingest/metric-batcher h/*system*)
  ;;                         (:db/sqlite h/*system*)
  ;;                         (:norm/metric-temporality h/*system*)
  ;;                         10)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
