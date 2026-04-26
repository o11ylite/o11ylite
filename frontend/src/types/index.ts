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
  id: string // "A", "B", "C"... (single uppercase letter, auto-generated)
  field: string
  function: AggregationFunction
}

// ============================================================================
// Visualization Types
// ============================================================================
// Maps to backend schema: query_schema.clj#visualization
// Note: heatmap and trace are deferred to post-v1 (backend scaffolding exists)

export type VisualizationType = "table" | "time_series"

export interface SortByField {
  field: string
  order: "asc" | "desc"
}

export interface SortByRef {
  ref: string
  order: "asc" | "desc"
}

export type SortConfig = SortByField | SortByRef

export interface TableVisualization {
  type: "table"
  sort?: SortConfig
  displayed_fields?: string[]
}

export type TimeSeriesRenderAs = "line" | "stacked_area" | "bar"

export interface TimeSeriesVisualization {
  type: "time_series"
  bucket_ms?: number
  overlay?: boolean
  // How to draw each series. Defaults to "line" when omitted.
  render_as?: TimeSeriesRenderAs
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

// ============================================================================
// Having Types
// ============================================================================
// Post-aggregation filter on an aggregation ref.
// Maps to backend: query_schema.clj#having-expr

export type HavingOp = ">" | "<" | ">=" | "<=" | "=" | "!="

export interface SimpleHaving {
  ref: string
  op: HavingOp
  value: number
}

export interface AndHaving {
  and: HavingExpr[]
}

export interface OrHaving {
  or: HavingExpr[]
}

export type HavingExpr = SimpleHaving | AndHaving | OrHaving

export interface EventsQuery {
  time_range: TimeRange
  filter?: FilterExpr
  aggregations?: Aggregation[]
  group_by?: string[]
  having?: HavingExpr
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
  having?: SimpleHaving
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
  service?: string // selected service name, undefined means no filter
  // Events mode fields
  filters: SimpleFilter[]
  aggregations: Aggregation[]
  groupBy: string[]
  having?: SimpleHaving
  limit?: number
  visualization: Visualization
  // Metrics mode fields
  metrics: MetricDefinition[]
}

// ============================================================================
// Query Response Types
// ============================================================================
// Response from POST /api/query/events

export interface ColumnMetadata {
  ref: string
  key: string
}

export interface TableQueryResult {
  columns?: ColumnMetadata[] // Present when aggregations used
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
  // Metric name -> OTel unit string (e.g., "By" for bytes, "%" for percent)
  // Only present in metrics query responses.
  units?: Record<string, string | null>
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

// ============================================================================
// Alert Rule Types
// ============================================================================

export type AlertState = "ok" | "firing"

export type AlertOn = "result" | "no_result"

export interface AlertRule {
  id: string
  name: string
  description: string | null
  enabled: boolean
  query_mode: QueryMode
  query: Record<string, unknown>
  eval_window_ms: number
  eval_interval_ms: number
  alert_on: AlertOn
  state: AlertState
  state_changed_at: number | null
  last_eval_at: number | null
  last_eval_error: string | null
  created_at: number
  updated_at: number
}

// ============================================================================
// Notebook Types
// ============================================================================

export interface Notebook {
  id: string
  name: string
  description: string | null
  cell_count?: number             // Present in list view (from JOIN)
  cells?: NotebookCell[]          // Present in show view
  created_at: number
  updated_at: number
}

export interface NotebookCell {
  id: string
  notebook_id: string
  position: number
  title: string | null
  description: string | null
  query_mode: QueryMode
  query: Record<string, unknown>
  pinned_from: string | null      // null = use global time, ISO timestamp when pinned
  pinned_to: string | null        // null = use global time, ISO timestamp when pinned
  created_at: number
  updated_at: number
}

// ============================================================================
// API Key Types
// ============================================================================

export type ApiKeyScope = "ingest" | "read" | "write" | "admin"

export interface ApiKey {
  id: string
  name: string
  prefix: string
  scope: ApiKeyScope
  created_at: number
  last_used_at: number | null
}

// ============================================================================
// Scheduled Job Types
// ============================================================================

export interface ScheduledJob {
  job_name: string
  description: string
  interval_ms: number
  last_run_at: number | null
  last_success_at: number | null
  last_error: string | null
  next_run_at: number | null
  enabled: number
  created_at: number
  updated_at: number
}

// ============================================================================
// Data Management Types
// ============================================================================

export type FieldStatus = "active" | "blocked"

export type FieldCategory = "system" | "attribute"

export interface ManagedField {
  name: string
  type: FieldType
  category: FieldCategory
  status: FieldStatus
}

export interface ManagedMetric {
  name: string
  metric_type: MetricType
  unit: string
  description: string
  attributes: string[]
}

export interface ManagedMetricAttribute {
  name: string
  status: FieldStatus
}

export interface ManagedService {
  name: string
  last_seen_at: number | null
  metric_count: number
  event_field_count: number
}

// ============================================================================
// Auth Shared Data (from Inertia shared props)
// ============================================================================

export interface AuthUser {
  email: string
  name: string
  sub: string
}

export interface AuthSharedData {
  user: AuthUser | null
  oidc_enabled: boolean
}
