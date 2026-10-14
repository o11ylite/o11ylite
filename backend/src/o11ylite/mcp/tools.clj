;; ---------------------------------------------------------
;; o11ylite.mcp.tools
;;
;; The tool set exposed by the MCP endpoint, in a fixed order (the
;; spec asks for deterministic tools/list output so clients and LLM
;; prompt caches stay stable), plus server-level instructions.
;;
;; Each tool carries :scope ("read" or "write"); o11ylite.routes.mcp
;; enforces it against the caller's token.
;; ---------------------------------------------------------

(ns o11ylite.mcp.tools
  (:require
    [o11ylite.mcp.tools.telemetry :as telemetry]
    [o11ylite.mcp.tools.workspace :as workspace]))

;; ---------------------------------------------------------
;; Registry

(def all
  "Every MCP tool, in tools/list order."
  [telemetry/list-services
   telemetry/list-event-fields
   telemetry/query-events
   telemetry/get-trace
   telemetry/list-metrics
   telemetry/describe-metric
   telemetry/query-metrics
   workspace/list-alert-rules
   workspace/get-alert-rule
   workspace/save-alert-rule
   workspace/create-notebook])

(def instructions
  "Server instructions returned by server/discover."
  (str "O11yLite stores OpenTelemetry traces, logs, and metrics. Spans and logs are "
       "\"events\" in one table (query_events, get_trace); metrics are separate "
       "(query_metrics). Discover before querying: list_services, list_event_fields, "
       "list_metrics, describe_metric. Attribute fields are prefixed attr. "
       "(e.g. attr.http.route). Time ranges accept {\"last\":\"1h\"} or ISO-8601 "
       "start/end; the default is the last hour. Prefer aggregations over raw rows "
       "for questions about rates, counts, or latency."))

;; ---------------------------------------------------------
;; Rich Comment
(comment

  (map :name all)

  #_()) ; End of rich comment block
;; ---------------------------------------------------------
