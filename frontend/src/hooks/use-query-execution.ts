import { useQuery } from "@tanstack/react-query"

import { buildFilterExpr } from "@/lib/query-helpers"
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
    filter: buildFilterExpr(state.filters),
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
  return {
    filter: buildFilterExpr(state.filters),
    group_by: state.groupBy.length > 0 ? state.groupBy : undefined,
    ...(state.having ? { having: extractSimpleHaving(state.having) } : {}),
    metrics: validMetrics,
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
}: UseQueryExecutionOptions) {
  const isEventsMode = state.mode === "events"

  const eventsBase = buildEventsPayload(state)
  const metricsBase = buildMetricsPayload(state)

  // For query key stability: in live mode, use the relative time strings
  // so only refetchInterval triggers refetches. In non-live mode, resolve
  // to epoch ms (second precision) so the key changes on each "Run" click.
  const resolved = resolveTimeRange({ from, to })
  const timeKey = live
    ? { from, to }
    : {
        start: Math.floor(resolved.from.getTime() / 1000) * 1000,
        end: Math.floor(resolved.to.getTime() / 1000) * 1000,
      }

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
    queryKey: [...prefix, "events", timeKey, eventsBase, cursor],
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
    queryKey: [...prefix, "metrics", timeKey, metricsBase],
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
