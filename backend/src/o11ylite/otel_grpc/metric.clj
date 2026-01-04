;; ---------------------------------------------------------
;; o11ylite.otel-grpc.metric
;;
;; OTLP Metrics service gRPC implementation
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.metric
  (:require
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.otel-grpc.metric-proto :as metric-proto])
  (:import
   [io.grpc.stub StreamObserver]
   [io.opentelemetry.proto.collector.metrics.v1
    MetricsServiceGrpc$MetricsServiceImplBase
    ExportMetricsServiceRequest]))

;; ---------------------------------------------------------
;; Handler

(defn- -metric-handler
  "Handle incoming metrics export request.
   Parses metrics to internal format and returns rejection count.
   
   TODO: Implement actual metric ingestion"
  [^ExportMetricsServiceRequest request]
  (let [{:keys [data-points metrics-metadata]} (metric-proto/parse-metrics-request request)
        rejected-count (metric-proto/count-rejected-data-points request)]
    (mulog/log ::metrics-received
               :data-point-count (count data-points)
               :metrics-metadata-count (count metrics-metadata)
               :rejected-count rejected-count)
    ;; TODO: Ingest data-points to DuckDB, upsert metrics-metadata to SQLite
    {:rejected-data-point-count rejected-count}))

;; ---------------------------------------------------------
;; Service factory

(defn create-service
  "Create a MetricsService gRPC implementation."
  []
  (proxy [MetricsServiceGrpc$MetricsServiceImplBase] []
    (export [^ExportMetricsServiceRequest request ^StreamObserver response-observer]
      (try
        (let [response-map (-metric-handler request)
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
