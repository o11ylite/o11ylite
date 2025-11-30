;; ---------------------------------------------------------
;; o11ylite.components.otel-grpc-server
;;
;; OpenTelemetry gRPC server component using grpc-java
;; Receives OTLP telemetry data over gRPC
;; ---------------------------------------------------------

(ns o11ylite.components.otel-grpc-server
  (:require
   [integrant.core :as ig]
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.otel-grpc.trace :as trace])
  (:import
   [io.grpc Server ServerBuilder]
   [java.util.concurrent Executors TimeUnit]))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :server/otel-grpc
  [_ {:keys [port]}]
  (mulog/log ::otel-grpc-server-starting :port port)
  (let [executor (Executors/newVirtualThreadPerTaskExecutor)
        server (-> (ServerBuilder/forPort port)
                   (.executor executor)
                   (.addService (trace/create-service))
                   (.build)
                   (.start))]
    (mulog/log ::otel-grpc-server-started :port port)
    {:server server
     :executor executor}))

(defmethod ig/halt-key! :server/otel-grpc
  [_ {:keys [^Server server executor]}]
  (mulog/log ::otel-grpc-server-stopping)
  (.shutdown server)
  (.awaitTermination server 5 TimeUnit/SECONDS)
  (.shutdown executor)
  (mulog/log ::otel-grpc-server-stopped))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test gRPC server manually
  (require '[integrant.core :as ig])

  (def server
    (ig/init-key :server/otel-grpc {:port 4317}))

  (ig/halt-key! :server/otel-grpc server)

  ;; Test with grpcurl:
  ;; grpcurl -plaintext localhost:4317 list
  ;; grpcurl -plaintext -d '{}' localhost:4317 opentelemetry.proto.collector.trace.v1.TraceService/Export

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
