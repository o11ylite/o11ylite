import { useQuery } from "@tanstack/react-query"

import { buildFilterExpr, withServiceFilter } from "@/lib/query-helpers"
import { resolveTimeRange } from "@/hooks/use-time-range"
import { extractSimpleHaving } from "@/lib/metric-query-helpers"
import type {
  QueryBuilderState,
  EventsQuery,
  MetricsQuery,
  MetricDefinition,
  QueryResponse,
} from "@/types"

// ============================================================================
// API
// ============================================================================

async function fetchEventsQuery(query: EventsQuery): Promise<QueryResponse> {
  const response = await fetch("/api/query/events", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(query),
  })
  if (!response.ok) {
    const errorData = (await response.json()) as { error?: string }
    throw new Error(errorData.error ?? "Query failed")
  }
  return response.json() as Promise<QueryResponse>
}

async function fetchMetricsQuery(query: MetricsQuery): Promise<QueryResponse> {
  const response = await fetch("/api/query/metrics", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(query),
  })
  if (!response.ok) {
    const errorData = (await response.json()) as { error?: string }
    throw new Error(errorData.error ?? "Query failed")
  }
  return response.json() as Promise<QueryResponse>
}

// ============================================================================
// Payload builders
// ============================================================================

/** Builds the events query payload (without time_range). */
export function buildEventsPayload(state: QueryBuilderState) {
  if (state.mode !== "events") return null
  return {
    filter: withServiceFilter(buildFilterExpr(state.filters), state.service),
    aggregations:
      state.aggregations.length > 0 ? state.aggregations : undefined,
    group_by: state.groupBy.length > 0 ? state.groupBy : undefined,
    ...(state.having ? { having: state.having } : {}),
    limit: state.limit ?? 100,
    visualization: state.visualization,
  }
}

/** Builds the metrics query payload (without time_range). */
export function buildMetricsPayload(state: QueryBuilderState) {
  if (state.mode !== "metrics") return null
  const validMetrics = (state.metrics ?? []).filter(
    (m: MetricDefinition) => m.name,
  )
  if (validMetrics.length === 0) return null
  // Drop formulas that aren't ready yet (empty expr) — sending them would
  // cause a 400 from the backend's parse validator. We let the user keep
  // typing in peace and only include the formula once it's non-blank.
  //
  // Also normalise optional fields: msgpack URL state encodes `undefined`
  // as `null` on decode, but the backend schema demands `string` (or omit).
  // Strip nullish/empty `name`/`unit` so we send `{id, expr}` cleanly.
  const validFormulas = (state.formulas ?? [])
    .filter((f) => f.expr.trim().length > 0)
    .map((f) => {
      const out: { id: string; expr: string; name?: string; unit?: string } = {
        id: f.id,
        expr: f.expr,
      }
      if (typeof f.name === "string" && f.name.length > 0) out.name = f.name
      if (typeof f.unit === "string" && f.unit.length > 0) out.unit = f.unit
      return out
    })
  return {
    filter: withServiceFilter(buildFilterExpr(state.filters), state.service),
    group_by: state.groupBy.length > 0 ? state.groupBy : undefined,
    ...(state.having ? { having: extractSimpleHaving(state.having) } : {}),
    metrics: validMetrics,
    ...(validFormulas.length > 0 ? { formulas: validFormulas } : {}),
  }
}

// ============================================================================
// Resolve time range to epoch ms
// ============================================================================

function resolveMs(from: string, to: string) {
  const resolved = resolveTimeRange({ from, to })
  return {
    start: Math.floor(resolved.from.getTime() / 1000) * 1000,
    end: Math.floor(resolved.to.getTime() / 1000) * 1000,
  }
}

// ============================================================================
// Hook
// ============================================================================

interface UseQueryExecutionOptions {
  /** QueryBuilderState driving the query. */
  state: QueryBuilderState
  /** Time range as DSL strings (e.g. "now-1h", "now"). */
  from: string
  to: string
  /** Prefix for TanStack Query cache keys. */
  queryKeyPrefix: string | unknown[]
  /** Enable live auto-refresh at LIVE_REFRESH_INTERVAL. */
  live?: boolean
  /** Refetch interval in ms (only applies when live is true). */
  refetchInterval?: number
  /** Cursor for events pagination. */
  cursor?: string | null
  /**
   * Opaque value included in the query key to force a re-fetch.
   * Bump this (e.g. a counter) when the user clicks "Run" with an
   * unchanged query + relative time range, so TanStack Query treats
   * it as a new query instead of returning the cached result.
   */
  runId?: number
}

/**
 * Executes a query against the events or metrics API based on QueryBuilderState.
 *
 * Handles:
 * - Building the correct payload (events vs metrics)
 * - Resolving time range strings to epoch ms
 * - TanStack Query caching with stable keys
 * - Live refresh
 * - Cursor-based pagination (events only)
 */
export function useQueryExecution({
  state,
  from,
  to,
  queryKeyPrefix,
  live = false,
  refetchInterval,
  cursor,
  runId,
}: UseQueryExecutionOptions) {
  const isEventsMode = state.mode === "events"

  const eventsBase = buildEventsPayload(state)
  const metricsBase = buildMetricsPayload(state)

  // Use raw time-range strings as the query key. Relative strings like
  // "now-15m" stay stable across renders so the key doesn't change every
  // second (which would cause duplicate in-flight queries). Absolute
  // strings change only when the user picks a new range. The actual
  // epoch-ms timestamps are resolved inside queryFn at execution time.
  const timeKey = { from, to }

  const prefix = Array.isArray(queryKeyPrefix)
    ? queryKeyPrefix
    : [queryKeyPrefix]

  const effectiveRefetchInterval =
    live && refetchInterval ? refetchInterval : false

  const {
    data: eventsResult,
    isLoading: eventsLoading,
    error: eventsError,
  } = useQuery({
    queryKey: [...prefix, "events", timeKey, eventsBase, cursor, runId],
    queryFn: () => {
      const timeRange = resolveMs(from, to)
      const payload = cursor
        ? { ...eventsBase!, cursor, time_range: timeRange }
        : { ...eventsBase!, time_range: timeRange }
      return fetchEventsQuery(payload as EventsQuery)
    },
    enabled: eventsBase !== null,
    refetchInterval: effectiveRefetchInterval,
  })

  const {
    data: metricsResult,
    isLoading: metricsLoading,
    error: metricsError,
  } = useQuery({
    queryKey: [...prefix, "metrics", timeKey, metricsBase, runId],
    queryFn: () => {
      const timeRange = resolveMs(from, to)
      return fetchMetricsQuery({
        ...metricsBase!,
        time_range: timeRange,
      } as MetricsQuery)
    },
    enabled: metricsBase !== null,
    refetchInterval: effectiveRefetchInterval,
  })

  return {
    data: isEventsMode ? eventsResult : metricsResult,
    isLoading: isEventsMode ? eventsLoading : metricsLoading,
    error: isEventsMode ? eventsError : metricsError,
    /** The base payloads (without time_range), useful for derived checks. */
    eventsPayload: eventsBase,
    metricsPayload: metricsBase,
  }
}
