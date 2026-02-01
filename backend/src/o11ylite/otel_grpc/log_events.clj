;; ---------------------------------------------------------
;; o11ylite.otel-grpc.log-events
;;
;; Converts OTLP log protobuf directly to unified events.
;; Single-pass conversion from Java protobuf to event maps.
;; ---------------------------------------------------------

(ns o11ylite.otel-grpc.log-events
  (:require
    [clojure.string :as str]
    [o11ylite.otel-grpc.proto :as proto])
  (:import
    [io.opentelemetry.proto.resource.v1 Resource]
    [io.opentelemetry.proto.logs.v1 LogRecord ResourceLogs ScopeLogs]
    [io.opentelemetry.proto.collector.logs.v1 ExportLogsServiceRequest ExportLogsServiceResponse ExportLogsPartialSuccess]
    [java.time Instant]))

;; ---------------------------------------------------------
;; Severity parsing
;;
;; NOTE: OTLP has both severity_number (enum 0-24) and severity_text (string).
;; Most real-world log sources only provide text (e.g., "INFO", "WARNING").
;; We normalize to a simple keyword and ignore the numeric severity for now.
;; If we need severity-based filtering/sorting later, we can derive levels from the keyword.

(defn- -parse-severity
  "Parse severity from severity_text string to keyword.
   Returns :info as default if empty or unrecognized."
  [^String severity-text]
  (if (str/blank? severity-text)
    :info
    (case (str/lower-case severity-text)
      ("trace" "trace1" "trace2" "trace3" "trace4") :trace
      ("debug" "debug1" "debug2" "debug3" "debug4") :debug
      ("info" "info1" "info2" "info3" "info4" "information") :info
      ("warn" "warn1" "warn2" "warn3" "warn4" "warning") :warn
      ("error" "error1" "error2" "error3" "error4" "err") :error
      ("fatal" "fatal1" "fatal2" "fatal3" "fatal4" "critical" "crit") :fatal
      :info)))

;; ---------------------------------------------------------
;; LogRecord -> Event (direct from protobuf)

(defn- -log-record->event
  "Convert LogRecord protobuf directly to unified event.
   Attributes are prefixed with 'attr.' and merged into the event map."
  [^LogRecord log resource-attrs scope-attrs scope-name scope-version service-name observed-time]
  (let [time-nanos (.getTimeUnixNano log)
        log-attrs (proto/extract-attributes (.getAttributesList log))
        body (proto/any-value->clj (.getBody log))
        prefixed-attrs (proto/prefix-attributes resource-attrs scope-attrs log-attrs)]
    (merge
      {:service service-name
       :timestamp (or (proto/nanos->instant time-nanos)
                      (proto/nanos->instant (.getObservedTimeUnixNano log))
                      observed-time)

       ;; Trace context (optional)
       :trace_id (proto/bytestring->hex (.getTraceId log))
       :span_id (proto/bytestring->hex (.getSpanId log))

       ;; Log-specific fields
       :name (let [event-name (.getEventName log)]
               (when (seq event-name) event-name))
       :log.severity (-parse-severity (.getSeverityText log))
       :log.body body

       ;; Instrumentation scope
       :scope.name scope-name
       :scope.version scope-version

       ;; Meta
       :meta.observed_time observed-time
       :meta.signal_type :log}
      prefixed-attrs)))

;; ---------------------------------------------------------
;; Public API

(defn log-request->events
  "Convert ExportLogsServiceRequest protobuf directly to unified events.

   Returns a sequence of event maps. Rejects (skips) resource logs without service.name."
  [^ExportLogsServiceRequest request]
  (let [observed-time (Instant/now)]
    (for [^ResourceLogs resource-logs (.getResourceLogsList request)
          :let [^Resource resource (.getResource resource-logs)
                service-name (proto/extract-service-name resource)]
          :when service-name
          :let [resource-attrs (proto/extract-attributes (.getAttributesList resource))]
          ^ScopeLogs scope-logs (.getScopeLogsList resource-logs)
          :let [scope (proto/extract-scope (.getScope scope-logs))
                scope-attrs (:attributes scope)
                scope-name (:name scope)
                scope-version (:version scope)]
          ^LogRecord log (.getLogRecordsList scope-logs)]
      (-log-record->event log resource-attrs scope-attrs scope-name scope-version service-name observed-time))))

(defn log-response->proto
  "Convert Clojure response map to ExportLogsServiceResponse.

   Accepts:
   {:rejected-log-count 0
    :error-message \"\"} or nil for success"
  [{:keys [rejected-log-count error-message] :or {rejected-log-count 0 error-message ""}}]
  (let [partial-success (-> (ExportLogsPartialSuccess/newBuilder)
                            (.setRejectedLogRecords rejected-log-count)
                            (.setErrorMessage error-message)
                            (.build))]
    (-> (ExportLogsServiceResponse/newBuilder)
        (.setPartialSuccess partial-success)
        (.build))))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  ;; Example log event structure (attributes prefixed with attr.):
  {:service "my-service"
   :timestamp #inst "2024-01-15T10:30:00Z"
   :trace_id "0af7651916cd43dd8448eb211c80319c"  ; optional
   :span_id "b7ad6b7169203331"                   ; optional
   :name "user.login"                            ; event_name if present
   :log.severity :info                           ; :trace :debug :info :warn :error :fatal
   :log.body "User logged in successfully"
   :scope.name "auth-service"
   :scope.version "1.0.0"
   :meta.observed_time #inst "2024-01-15T10:30:01Z"
   :meta.signal_type :log
   ;; Prefixed attributes (from resource, scope, and log)
   "attr.user.id" "12345"
   "attr.http.method" "GET"}

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
