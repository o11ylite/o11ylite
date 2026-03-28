;; ---------------------------------------------------------
;; o11ylite.otel-grpc.metric
;;
;; OTLP Metrics service gRPC implementation
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.metric
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.otel-grpc.metric-proto :as metric-proto]
    [o11ylite.store.metrics.ingest :as metrics.ingest])
  (:import
    [io.grpc.stub StreamObserver]
    [io.opentelemetry.proto.collector.metrics.v1
     MetricsServiceGrpc$MetricsServiceImplBase
     ExportMetricsServiceRequest]))

;; ---------------------------------------------------------
;; Handler

(defn- -metric-handler
  "Handle incoming metrics export request.
   Parses metrics to internal format, ingests data, and returns rejection count."
  [metric-batcher blocked-fields sqlite normalizer ^ExportMetricsServiceRequest request]
  (let [{:keys [data-points metrics-metadata]} (metric-proto/parse-metrics-request request)]
    (mulog/log ::metrics-received
               :data-point-count (count data-points)
               :metadata-count (count metrics-metadata))
    (if (or (seq data-points) (seq metrics-metadata))
      (let [{:keys [rejected-count error-message]} (metrics.ingest/ingest-metrics! metric-batcher blocked-fields sqlite normalizer data-points metrics-metadata)]
        {:rejected-data-point-count (or rejected-count 0)
         :error-message (or error-message "")})
      {})))

;; ---------------------------------------------------------
;; Service factory

(defn create-service
  "Create a MetricsService gRPC implementation.

   Arguments:
     metric-batcher    - The metric batcher component for ingestion
     blocked-fields    - Blocked-fields cache component
     sqlite            - SQLite datasource for cached metadata lookups
     metric-normalizer - Temporality normalizer for cumulative→delta conversion"
  [metric-batcher blocked-fields sqlite metric-normalizer]
  (proxy [MetricsServiceGrpc$MetricsServiceImplBase] []
    (export
      [^ExportMetricsServiceRequest request ^StreamObserver response-observer]
      (try
        (let [response-map (-metric-handler metric-batcher blocked-fields sqlite metric-normalizer request)
              response (metric-proto/metric-response->proto (or response-map {}))]
          (.onNext response-observer response)
          (.onCompleted response-observer))
        (catch Exception e
          (mulog/log ::metric-export-error :error (.getMessage e))
          (.onError response-observer e))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test with otel-cli or SDK
  ;; The MetricsService accepts ExportMetricsServiceRequest and returns ExportMetricsServiceResponse

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
