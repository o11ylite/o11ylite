;; ---------------------------------------------------------
;; o11ylite.otel-grpc.trace
;;
;; OTLP Trace service gRPC implementation
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.trace
  (:require
    [o11ylite.store.events.ingest :as events.ingest]
    [o11ylite.otel-grpc.trace-events :as trace-events]
    [o11ylite.util.telemetry :as telemetry]
    [steffan-westcott.clj-otel.api.trace.span :as span])
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
  [duckdb blocked-fields batcher id-generator ^ExportTraceServiceRequest request]
  (let [events (trace-events/trace-request->events request)
        span-count (count (filter #(= :span (:meta.signal_type %)) events))
        span-event-count (count (filter #(= :span_event (:meta.signal_type %)) events))]
    (span/add-span-data!
      {:attributes {:o11ylite.otlp_receiver.span_count span-count
                    :o11ylite.otlp_receiver.span_event_count span-event-count}})
    (when (seq events)
      (events.ingest/ingest-events! duckdb blocked-fields batcher id-generator events))
    {:rejected-span-count 0}))

;; ---------------------------------------------------------
;; Service factory

(defn create-service
  "Create a TraceService gRPC implementation.

   Arguments:
     duckdb         - DuckDB datasource (for events-table schema reads)
     blocked-fields - Blocked-fields cache component
     batcher        - Ingest batcher component
     id-generator   - ID generator component"
  [duckdb blocked-fields batcher id-generator]
  (proxy [TraceServiceGrpc$TraceServiceImplBase] []
    (export
      [^ExportTraceServiceRequest request ^StreamObserver response-observer]
      (try
        (let [response-map (-trace-handler duckdb blocked-fields batcher id-generator request)
              response (trace-events/trace-response->proto (or response-map {}))]
          (.onNext response-observer response)
          (.onCompleted response-observer))
        (catch Exception e
          (telemetry/report-error! ::trace-export-error e)
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
