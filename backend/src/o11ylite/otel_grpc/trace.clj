;; ---------------------------------------------------------
;; o11ylite.otel-grpc.trace
;;
;; OTLP Trace service gRPC implementation
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.trace
  (:require
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.store.events.ingest :as events.ingest]
   [o11ylite.otel-grpc.trace-events :as trace-events])
  (:import
   [io.grpc.stub StreamObserver]
   [io.opentelemetry.proto.collector.trace.v1
    TraceServiceGrpc$TraceServiceImplBase
    ExportTraceServiceRequest]))

;; ---------------------------------------------------------
;; Handler

(defn- -trace-handler
  "Handle incoming trace export request.
   Converts spans to unified events and persists them."
  [event-metadata batcher id-generator ^ExportTraceServiceRequest request]
  (let [events (trace-events/trace-request->events request)
        span-count (count (filter #(= :span (:meta.signal_type %)) events))
        span-event-count (count (filter #(= :span_event (:meta.signal_type %)) events))]
    (mulog/log ::traces-received
               :span-count span-count
               :span-event-count span-event-count)
    (when (seq events)
      (events.ingest/ingest-events! event-metadata batcher id-generator events))
    {:rejected-span-count 0}))

;; ---------------------------------------------------------
;; Service factory

(defn create-service
  "Create a TraceService gRPC implementation.

   Arguments:
     event-metadata - Event metadata cache component
     batcher        - Ingest batcher component
     id-generator   - ID generator component"
  [event-metadata batcher id-generator]
  (proxy [TraceServiceGrpc$TraceServiceImplBase] []
    (export [^ExportTraceServiceRequest request ^StreamObserver response-observer]
      (try
        (let [response-map (-trace-handler event-metadata batcher id-generator request)
              response (trace-events/trace-response->proto (or response-map {}))]
          (.onNext response-observer response)
          (.onCompleted response-observer))
        (catch Exception e
          (mulog/log ::trace-export-error :error (.getMessage e))
          (.onError response-observer e))))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test with otel-cli (server must be running):
    ;; docker run --rm -it ghcr.io/equinix-labs/otel-cli:v0.4.5 span \
    ;;       --insecure --tls-no-verify \
    ;;       --endpoint "host.docker.internal:4317" \
    ;;       --service "my-application" \
    ;;       --name "send data to the server" \
    ;;       --start 2021-03-24T07:28:05.12345Z \
    ;;       --end $(date +%s.%N) \
    ;;       --attrs "os.kernel=$(uname -r)" \
    ;;       --tp-print
  #_()) ; End of rich comment block
;; ---------------------------------------------------------
