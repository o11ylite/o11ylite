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
    [o11ylite.auth.scope :as scope]
    [o11ylite.components.api-key-cache :as api-key-cache]
    [o11ylite.otel-grpc.trace :as trace]
    [o11ylite.otel-grpc.log :as log]
    [o11ylite.otel-grpc.metric :as metric])
  (:import
    [io.grpc Metadata Metadata$Key Server ServerBuilder ServerCall ServerCall$Listener ServerCallHandler ServerInterceptor Status]
    [java.util.concurrent Executors TimeUnit]))

;; ---------------------------------------------------------
;; Auth Interceptor

(def ^:private auth-metadata-key
  (Metadata$Key/of "authorization" Metadata/ASCII_STRING_MARSHALLER))

(defn- -extract-bearer-token
  "Extract the token from a 'Bearer <token>' value."
  [^String value]
  (when (and value (.startsWith value "Bearer "))
    (subs value 7)))

(defn- -reject-call
  "Close a gRPC call with the given status and return a no-op listener."
  [call status]
  (.close call status (Metadata.))
  (proxy [ServerCall$Listener] []))

(defn- -create-auth-interceptor
  "Create a gRPC ServerInterceptor that validates API keys.
   If no keys exist in cache → allow all (open mode).
   Otherwise require a valid key with ingest scope."
  [api-key-cache]
  (reify ServerInterceptor
    (interceptCall
      [_ call headers next-handler]
      (let [open-mode? (not (api-key-cache/any-keys? api-key-cache))
            token (when-not open-mode?
                    (-extract-bearer-token (.get headers auth-metadata-key)))
            key-info (when token
                       (api-key-cache/validate-token api-key-cache token))]
        (cond
          open-mode?
          (.startCall next-handler call headers)

          (nil? token)
          (-reject-call call Status/UNAUTHENTICATED)

          (and key-info (scope/has-scope? (:scope key-info) "ingest"))
          (.startCall next-handler call headers)

          key-info
          (-reject-call call Status/PERMISSION_DENIED)

          :else
          (-reject-call call Status/UNAUTHENTICATED))))))

;; ---------------------------------------------------------
;; Component Lifecycle

(defmethod ig/init-key :server/otel-grpc
  [_ {:keys [core-config event-metadata event-batcher id-generator metric-batcher metric-normalizer sqlite api-key-cache]}]
  (let [port (:otel-grpc-port core-config)]
    (mulog/log ::otel-grpc-server-starting :port port)
    (let [executor (Executors/newVirtualThreadPerTaskExecutor)
          server (-> (ServerBuilder/forPort port)
                     (.executor executor)
                     (.intercept (-create-auth-interceptor api-key-cache))
                     (.addService (trace/create-service event-metadata event-batcher id-generator))
                     (.addService (log/create-service event-metadata event-batcher id-generator))
                     (.addService (metric/create-service metric-batcher sqlite metric-normalizer))
                     (.build)
                     (.start))]
      (mulog/log ::otel-grpc-server-started :port port)
      {:server server
       :executor executor})))

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
