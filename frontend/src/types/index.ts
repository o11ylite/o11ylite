// ============================================================================
// Field Types
// ============================================================================
// Field metadata from the backend's event_metadata component.
// Used to populate field pickers and determine available operators.

export type FieldType = "time" | "str" | "num" | "enum"

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
  alias?: string
}

// ============================================================================
// Visualization Types
// ============================================================================
// Maps to backend schema: query_schema.clj#visualization (union of 4 types)
// Note: heatmap requires exactly one group_by field (enforced by backend)

export type VisualizationType = "table" | "time_series" | "heatmap" | "trace"

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

export interface HeatmapVisualization {
  type: "heatmap"
  y_buckets?: number
}

export interface TraceVisualization {
  type: "trace"
}

export type Visualization =
  | TableVisualization
  | TimeSeriesVisualization
  | HeatmapVisualization
  | TraceVisualization

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

export interface QueryResponse {
  data: TableQueryResult // Will be union type when other viz types implemented
  metadata: {
    query_time_ms: number
    truncated: boolean
  }
}
