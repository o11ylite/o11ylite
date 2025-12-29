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

export type FilterOp = "=" | "!=" | ">" | "<" | ">=" | "<=" | "contains" | "exists"

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
  limit?: number
  sort?: {
    field: string
    order: "asc" | "desc"
  }
}

export interface TimeSeriesVisualization {
  type: "time_series"
  bucket_ms?: number
  limit_series?: number
}

// Deferred to post-v1: HeatmapVisualization, TraceVisualization
// Backend scaffolding exists in query_schema.clj and query.clj

export type Visualization = TableVisualization | TimeSeriesVisualization

// ============================================================================
// Events Query Types
// ============================================================================
// Maps to backend schema: query_schema.clj#events-query
// This is the payload sent to POST /api/query/events

export interface TimeRange {
  start: number // Unix epoch seconds
  end: number   // Unix epoch seconds
}

export interface EventsQuery {
  time_range: TimeRange
  filter?: FilterExpr
  aggregations?: Aggregation[]
  group_by?: string[]
  having?: FilterExpr
  visualization: Visualization
}

// ============================================================================
// Query Builder State
// ============================================================================
// UI-only state for the query builder component.
// Converted to EventsQuery before sending to backend.
// Persisted to URL via msgpack encoding (see lib/url-codec.ts).

export interface QueryBuilderState {
  filters: SimpleFilter[]
  aggregations: Aggregation[]
  groupBy: string[]
  visualization: Visualization
}

// ============================================================================
// Query Response Types
// ============================================================================
// Response from POST /api/query/events

export interface TableQueryResult {
  rows: Record<string, unknown>[]
  total_count: number
  truncated: boolean
}

// Time series data point with timestamp and value
export interface TimeSeriesDataPoint {
  timestamp: number // Unix epoch milliseconds
  value: number
}

// A single series with labels, name, and data points
export interface TimeSeriesSeries {
  labels: Record<string, string>
  name: string // Aggregation alias (e.g., "count(*)", "avg(span.duration_ms)")
  data: TimeSeriesDataPoint[]
}

export interface TimeSeriesQueryResult {
  bucket_ms: number
  start_ms: number
  end_ms: number
  series: TimeSeriesSeries[]
}

export type QueryResult = TableQueryResult | TimeSeriesQueryResult

export interface QueryResponse {
  data: QueryResult
  metadata: {
    query_time_ms: number
    truncated: boolean
  }
}
