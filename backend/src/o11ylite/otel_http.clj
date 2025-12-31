;; ---------------------------------------------------------
;; o11ylite.otel-http
;;
;; OTLP HTTP endpoints for traces and logs.
;; Supports both protobuf binary and JSON content types.
;; Reuses conversion logic from otel_grpc/*_events namespaces.
;;
;; Why a single file instead of a folder like otel_grpc/?
;; ------------------------------------------------------
;; The otel_grpc/ folder contains:
;;   - proto.clj        - Shared protobuf conversion helpers
;;   - trace_events.clj - Trace protobuf → event conversion
;;   - log_events.clj   - Log protobuf → event conversion
;;   - trace.clj        - gRPC service (StreamObserver, proxy)
;;   - log.clj          - gRPC service (StreamObserver, proxy)
;;
;; This HTTP module reuses all the conversion logic (*_events.clj)
;; and only adds thin HTTP handlers. The transport-specific code
;; (parsing request body, building response) is minimal and doesn't
;; warrant separate files. If HTTP-specific logic grows (e.g., 
;; streaming, compression), we can split into otel_http/ folder.
;; ---------------------------------------------------------

(ns o11ylite.otel-http
  (:require
   [com.brunobonacci.mulog :as mulog]
   [o11ylite.store.events.ingest :as events.ingest]
   [o11ylite.otel-grpc.trace-events :as trace-events]
   [o11ylite.otel-grpc.log-events :as log-events])
  (:import
   [com.google.protobuf.util JsonFormat]
   [io.opentelemetry.proto.collector.trace.v1 ExportTraceServiceRequest]
   [io.opentelemetry.proto.collector.logs.v1 ExportLogsServiceRequest]
   [java.io InputStream]))

;; ---------------------------------------------------------
;; Content-Type Detection

(def ^:private content-type-protobuf "application/x-protobuf")
(def ^:private content-type-json "application/json")

(defn- -get-content-type
  "Extract content type from request headers, defaulting to JSON."
  [request]
  (let [ct (get-in request [:headers "content-type"] "")]
    (cond
      (.contains ct "protobuf") :protobuf
      (.contains ct "json") :json
      ;; OTLP spec says default to protobuf, but mulog uses JSON
      :else :json)))

(defn- -accepts-protobuf?
  "Check if client accepts protobuf response."
  [request]
  (let [accept (get-in request [:headers "accept"] "")]
    (.contains accept "protobuf")))

;; ---------------------------------------------------------
;; Protobuf Parsing

(defn- -parse-trace-request
  "Parse request body into ExportTraceServiceRequest.
   Supports both protobuf binary and JSON."
  [request]
  (let [content-type (-get-content-type request)
        body (:body request)]
    (case content-type
      :protobuf
      (ExportTraceServiceRequest/parseFrom ^InputStream body)

      :json
      (let [json-str (if (string? body) body (slurp body))
            builder (ExportTraceServiceRequest/newBuilder)]
        (.merge (JsonFormat/parser) json-str builder)
        (.build builder)))))

(defn- -parse-log-request
  "Parse request body into ExportLogsServiceRequest.
   Supports both protobuf binary and JSON."
  [request]
  (let [content-type (-get-content-type request)
        body (:body request)]
    (case content-type
      :protobuf
      (ExportLogsServiceRequest/parseFrom ^InputStream body)

      :json
      (let [json-str (if (string? body) body (slurp body))
            builder (ExportLogsServiceRequest/newBuilder)]
        (.merge (JsonFormat/parser) json-str builder)
        (.build builder)))))

;; ---------------------------------------------------------
;; Response Building

(defn- -trace-response
  "Build HTTP response for trace export.
   Returns protobuf or JSON based on Accept header."
  [request {:keys [rejected-span-count error-message]
            :or {rejected-span-count 0 error-message ""}}]
  (let [proto-response (trace-events/trace-response->proto
                        {:rejected-span-count rejected-span-count
                         :error-message error-message})]
    (if (-accepts-protobuf? request)
      {:status 200
       :headers {"Content-Type" content-type-protobuf}
       :body (.toByteArray proto-response)}
      {:status 200
       :headers {"Content-Type" content-type-json}
       :body (.print (JsonFormat/printer) proto-response)})))

(defn- -log-response
  "Build HTTP response for log export.
   Returns protobuf or JSON based on Accept header."
  [request {:keys [rejected-log-count error-message]
            :or {rejected-log-count 0 error-message ""}}]
  (let [proto-response (log-events/log-response->proto
                        {:rejected-log-count rejected-log-count
                         :error-message error-message})]
    (if (-accepts-protobuf? request)
      {:status 200
       :headers {"Content-Type" content-type-protobuf}
       :body (.toByteArray proto-response)}
      {:status 200
       :headers {"Content-Type" content-type-json}
       :body (.print (JsonFormat/printer) proto-response)})))

(defn- -error-response
  "Build error response."
  [status message]
  {:status status
   :headers {"Content-Type" content-type-json}
   :body (str "{\"error\":\"" message "\"}")})

;; ---------------------------------------------------------
;; Handlers

(defn trace-handler
  "Handle POST /v1/traces requests.
   Parses OTLP trace data and ingests into storage."
  [{:keys [event-metadata batcher]} request]
  (try
    (let [proto-request (-parse-trace-request request)
          events (trace-events/trace-request->events proto-request)
          rejected-spans (trace-events/count-rejected-spans proto-request)
          span-count (count (filter #(= :span (:meta.signal_type %)) events))
          span-event-count (count (filter #(= :span_event (:meta.signal_type %)) events))]
      (mulog/log ::http-traces-received
                 :span-count span-count
                 :span-event-count span-event-count
                 :rejected-spans rejected-spans)
      (when (seq events)
        (events.ingest/ingest-events! event-metadata batcher events))
      (-trace-response request {:rejected-span-count rejected-spans}))
    (catch Exception e
      (mulog/log ::http-trace-error :error (.getMessage e))
      (-error-response 400 (.getMessage e)))))

(defn log-handler
  "Handle POST /v1/logs requests.
   Parses OTLP log data and ingests into storage."
  [{:keys [event-metadata batcher]} request]
  (try
    (let [proto-request (-parse-log-request request)
          events (log-events/log-request->events proto-request)
          rejected-log-count (log-events/count-rejected-logs proto-request)
          log-count (count events)]
      (mulog/log ::http-logs-received
                 :log-count log-count
                 :rejected-log-count rejected-log-count)
      (when (seq events)
        (events.ingest/ingest-events! event-metadata batcher events))
      (-log-response request {:rejected-log-count rejected-log-count}))
    (catch Exception e
      (mulog/log ::http-log-error :error (.getMessage e))
      (-error-response 400 (.getMessage e)))))

;; ---------------------------------------------------------
;; Routes

(defn routes
  "OTLP HTTP routes.
   
   Arguments:
     opts - Map with :event-metadata and :batcher components"
  [opts]
  ["/v1"
   ["/traces" {:post {:handler (partial trace-handler opts)}}]
   ["/logs" {:post {:handler (partial log-handler opts)}}]])

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Test with curl (JSON):
  ;; curl -X POST http://localhost:3000/v1/traces \
  ;;   -H "Content-Type: application/json" \
  ;;   -d '{"resourceSpans":[]}'

  ;; Test with curl (protobuf):
  ;; echo '' | protoc --encode=... | curl -X POST http://localhost:3000/v1/traces \
  ;;   -H "Content-Type: application/x-protobuf" \
  ;;   --data-binary @-

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
