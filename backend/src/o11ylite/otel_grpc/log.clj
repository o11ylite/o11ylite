;; ---------------------------------------------------------
;; o11ylite.otel-grpc.log
;;
;; OTLP Log service gRPC implementation
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.log
  (:require
    [com.brunobonacci.mulog :as mulog]
    [o11ylite.store.events.ingest :as events.ingest]
    [o11ylite.otel-grpc.log-events :as log-events])
  (:import
    [io.grpc.stub StreamObserver]
    [io.opentelemetry.proto.collector.logs.v1
     LogsServiceGrpc$LogsServiceImplBase
     ExportLogsServiceRequest]))

;; ---------------------------------------------------------
;; Handler

(defn- -log-handler
  "Handle incoming log export request.
   Converts log records to unified events and persists them."
  [event-metadata blocked-fields batcher id-generator ^ExportLogsServiceRequest request]
  (let [events (log-events/log-request->events request)
        log-count (count events)]
    (mulog/log ::logs-received :log-count log-count)
    (when (seq events)
      (events.ingest/ingest-events! event-metadata blocked-fields batcher id-generator events))
    {:rejected-log-count 0}))

;; ---------------------------------------------------------
;; Service factory

(defn create-service
  "Create a LogsService gRPC implementation.

   Arguments:
     event-metadata - Event metadata cache component
     blocked-fields - Blocked-fields cache component
     batcher        - Ingest batcher component
     id-generator   - ID generator component"
  [event-metadata blocked-fields batcher id-generator]
  (proxy [LogsServiceGrpc$LogsServiceImplBase] []
    (export
      [^ExportLogsServiceRequest request ^StreamObserver response-observer]
      (try
        (let [response-map (-log-handler event-metadata blocked-fields batcher id-generator request)
              response (log-events/log-response->proto (or response-map {}))]
          (.onNext response-observer response)
          (.onCompleted response-observer))
        (catch Exception e
          (mulog/log ::log-export-error :error (.getMessage e))
          (.onError response-observer e))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test with otel-cli or SDK
  ;; The LogsService accepts ExportLogsServiceRequest and returns ExportLogsServiceResponse

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
