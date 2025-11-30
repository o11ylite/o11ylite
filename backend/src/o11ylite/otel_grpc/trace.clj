;; ---------------------------------------------------------
;; o11ylite.otel-grpc.trace
;;
;; OTLP Trace service gRPC implementation
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.trace
  (:require
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.otel-grpc.otel-proto :as otel-proto])
  (:import
   [io.grpc.stub StreamObserver]
   [io.opentelemetry.proto.collector.trace.v1
    TraceServiceGrpc$TraceServiceImplBase
    ExportTraceServiceRequest]))

;; ---------------------------------------------------------
;; Handler

(defn- -trace-handler
  "Handle incoming trace export request.
   Logs received spans and accepts all."
  [request]
  (let [span-count (->> (:resource-spans request)
                        (mapcat :scope-spans)
                        (mapcat :spans)
                        count)]
    (mulog/log ::traces-received :span-count span-count)
    {:rejected-spans 0}))

;; ---------------------------------------------------------
;; Service factory

(defn create-service
  "Create a TraceService gRPC implementation."
  []
  (proxy [TraceServiceGrpc$TraceServiceImplBase] []
    (export [^ExportTraceServiceRequest request ^StreamObserver response-observer]
      (try
        (let [request-map (otel-proto/trace-request->clj request)
              response-map (-trace-handler request-map)
              response (otel-proto/trace-response->proto (or response-map {}))]
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
