// ============================================================================
// Service Types
// ============================================================================
// Service metadata from the backend's service_metadata table.

export interface Service {
  name: string
  first_seen_at: number
  updated_at: number
}

// ============================================================================
// Field Types
// ============================================================================
// Field metadata from the backend's event_metadata component.
// Used to populate field pickers and determine available operators.
// Maps to backend schema.clj normalized types.

export type FieldType = "string" | "instant" | "integer" | "float" | "boolean"

export interface Field {
  name: string
  type: FieldType
}

// ============================================================================
// Filter Types
// ============================================================================
// Maps to backend schema: query_schema.clj#filter-op, simple-filter, filter-expr

export type FilterOp = "=" | "!=" | ">" | "<" | ">=" | "<=" | "contains" | "exists" | "starts-with"

export interface SimpleFilter {
  field: string
  op: FilterOp
  value: string | number | boolean
}

export interface AndFilter {
  and: FilterExpr[]
}

export interface OrFilter {
  or: FilterExpr[]
}

export type FilterExpr = SimpleFilter | AndFilter | OrFilter

// ============================================================================
// Aggregation Types
// ============================================================================
// Maps to backend schema: query_schema.clj#aggregation-function, aggregation

export type AggregationFunction =
  | "count"
  | "sum"
  | "avg"
  | "min"
  | "max"
  | "p50"
  | "p90"
  | "p99"

export interface Aggregation {
  field: string
  function: AggregationFunction
}

// ============================================================================
// Visualization Types
// ============================================================================
// Maps to backend schema: query_schema.clj#visualization
// Note: heatmap and trace are deferred to post-v1 (backend scaffolding exists)

export type VisualizationType = "table" | "time_series"

export interface TableVisualization {
  type: "table"
  sort?: {
    field: string
    order: "asc" | "desc"
  }
}

export interface TimeSeriesVisualization {
  type: "time_series"
  bucket_ms?: number
}

export interface TraceVisualization {
  type: "trace"
}

export type Visualization = TableVisualization | TimeSeriesVisualization | TraceVisualization

// ============================================================================
// Events Query Types
// ============================================================================
// Maps to backend schema: query_schema.clj#events-query
// This is the payload sent to POST /api/query/events

export interface TimeRange {
  start: number // Unix epoch milliseconds
  end: number   // Unix epoch milliseconds
}

export interface EventsQuery {
  time_range: TimeRange
  filter?: FilterExpr
  aggregations?: Aggregation[]
  group_by?: string[]
  having?: FilterExpr
  limit?: number
  visualization: Visualization
}

// ============================================================================
// Metric Types
// ============================================================================
// Maps to backend: store/metrics/metadata.clj and query_schema.clj

export type MetricType = "gauge" | "sum" | "histogram"

// Metric summary from GET /api/metrics (lightweight list)
export interface MetricSummary {
  name: string
  metric_type: MetricType
  unit: string | null
}

// Full metric detail from GET /api/metrics/:name
export interface MetricDetail extends MetricSummary {
  description: string | null
  temporality: "delta" | "cumulative" | null
  attributes: string[]
  hist_boundaries: number[] | null
}

// Metric aggregation functions (different from event aggregations)
// Valid aggregations per metric type (from query-validation.clj):
//   gauge: sum, avg, min, max, last
//   sum (counter): sum, rate
//   histogram: count, sum, avg, min, max
export type MetricAggregation = "sum" | "avg" | "min" | "max" | "last" | "rate" | "count"

// Single metric definition in query builder
export interface MetricDefinition {
  id: string // "A", "B", "C"... (single uppercase letter)
  name: string // metric name
  agg: MetricAggregation
  // TODO: filter?: FilterExpr  // per-metric filter (deferred)
}

// ============================================================================
// Metrics Query Types
// ============================================================================
// Maps to backend schema: metrics/query_schema.clj#metrics-query
// This is the payload sent to POST /api/query/metrics

export interface MetricsQuery {
  time_range: TimeRange
  bucket_ms?: number
  filter?: FilterExpr
  group_by?: string[]
  metrics: MetricDefinition[]
}

// ============================================================================
// Query Builder State
// ============================================================================
// UI-only state for the query builder component.
// Supports both events and metrics modes.
// Persisted to URL via msgpack encoding (see lib/url-codec.ts).

export type QueryMode = "events" | "metrics"

export interface QueryBuilderState {
  mode: QueryMode
  // Events mode fields
  filters: SimpleFilter[]
  aggregations: Aggregation[]
  groupBy: string[]
  limit?: number
  visualization: Visualization
  // Metrics mode fields
  metrics: MetricDefinition[]
}

// ============================================================================
// Query Response Types
// ============================================================================
// Response from POST /api/query/events

export interface TableQueryResult {
  rows: Record<string, unknown>[]
  total_count: number
  has_more: boolean
  next_cursor: string | null
}

// Time series data point with timestamp and value
export interface TimeSeriesDataPoint {
  timestamp: number // Unix epoch milliseconds
  value: number
}

// A single series with labels, name, and data points
export interface TimeSeriesSeries {
  labels: Record<string, string>
  name: string // Display name: aggregation alias for events (e.g., "count(*)"), or agg(metric) for metrics (e.g., "avg(cpu.utilization)")
  data: TimeSeriesDataPoint[]
  // Metrics-specific fields (only present in metrics query results)
  id?: string // Metric query ID (e.g., "A", "B") - for formula references
  metric?: string // Metric name (e.g., "cpu.utilization")
}

export interface TimeSeriesQueryResult {
  bucket_ms: number
  start_ms: number
  end_ms: number
  series: TimeSeriesSeries[]
}

// Trace data - a single span or span_event in a trace waterfall
export interface TraceSpan {
  span_id: string
  parent_span_id: string | null
  name: string
  service: string
  "meta.signal_type": "span" | "span_event"
  "span.status_code": string | null  // null for span_events
  "span.duration_ms": number | null  // null for span_events
  timestamp: number // epoch ms float
}

export interface TraceQueryResult {
  spans: TraceSpan[]
  total_count: number
}

export type QueryResult = TableQueryResult | TimeSeriesQueryResult | TraceQueryResult

export interface QueryResponse {
  data: QueryResult
  metadata: {
    query_time_ms: number
    has_more: boolean
  }
}
