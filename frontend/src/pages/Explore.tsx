import { useQuery } from "@tanstack/react-query"
import { usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { QueryBuilder } from "@/components/query-builder"
import {
  ResultsTable,
  ResultsTimeSeries,
  ResultsPlaceholder,
  ResultsLoading,
  ResultsError,
} from "@/components/results"
import { FieldsPanel } from "@/components/fields-panel"
import { useQueryState } from "@/hooks/use-query-state"
import {
  useTimeRange,
  resolveTimeRange,
  LIVE_REFRESH_INTERVAL,
} from "@/hooks/use-time-range"
import type {
  Field,
  Service,
  EventsQuery,
  MetricsQuery,
  MetricDefinition,
  QueryResponse,
  SimpleFilter,
  FilterExpr,
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
// Helpers
// ============================================================================

function buildFilterExpr(filters: SimpleFilter[]): FilterExpr | undefined {
  const validFilters = filters.filter((f) => f.field && f.value !== "")
  if (validFilters.length === 0) return undefined
  if (validFilters.length === 1) return validFilters[0]
  return { and: validFilters }
}

// ============================================================================
// Page
// ============================================================================

export default function Explore() {
  const { fields, services } = usePage<{ fields: Field[]; services: Service[] }>().props
  const { state, setState, hasQuery } = useQueryState()
  const { from, to, live } = useTimeRange()

  const mode = state.mode ?? "events"
  const isEventsMode = mode === "events"
  const isMetricsMode = mode === "metrics"

  // Build the events query payload (without time_range for stable key in live mode)
  const eventsPayload = isEventsMode && hasQuery
    ? {
        filter: buildFilterExpr(state.filters),
        aggregations:
          state.aggregations.length > 0 ? state.aggregations : undefined,
        group_by: state.groupBy.length > 0 ? state.groupBy : undefined,
        visualization: state.visualization,
      }
    : null

  // Build the metrics query payload
  // Filter to only include metrics with names selected
  const validMetrics = (state.metrics ?? []).filter(
    (m: MetricDefinition) => m.name
  )
  const metricsPayload = isMetricsMode && validMetrics.length > 0
    ? {
        filter: buildFilterExpr(state.filters),
        group_by: state.groupBy.length > 0 ? state.groupBy : undefined,
        metrics: validMetrics,
      }
    : null

  // For query key: in live mode, use stable relative strings (from, to)
  // so only refetchInterval triggers queries.
  // In non-live mode, resolve to timestamps with second-level precision.
  // This keeps the key stable across renders within the same second,
  // while still changing on each "Run" click (typically >1s apart).
  const resolved = resolveTimeRange({ from, to })
  const queryKeyTimeRange = live
    ? { from, to }
    : {
        start: Math.floor(resolved.from.getTime() / 1000) * 1000,
        end: Math.floor(resolved.to.getTime() / 1000) * 1000,
      }

  // TanStack Query for events
  // In live mode, refetchInterval triggers periodic re-fetches with fresh timestamps
  const {
    data: eventsResult,
    isLoading: eventsLoading,
    error: eventsError,
  } = useQuery({
    queryKey: ["events-query", queryKeyTimeRange, eventsPayload],
    queryFn: () => {
      // Resolve time range fresh on each fetch
      const freshResolved = resolveTimeRange({ from, to })
      return fetchEventsQuery({
        ...eventsPayload!,
        time_range: {
          start: Math.floor(freshResolved.from.getTime() / 1000) * 1000,
          end: Math.floor(freshResolved.to.getTime() / 1000) * 1000,
        },
      })
    },
    enabled: eventsPayload !== null,
    refetchInterval: live ? LIVE_REFRESH_INTERVAL : false,
  })

  // TanStack Query for metrics
  const {
    data: metricsResult,
    isLoading: metricsLoading,
    error: metricsError,
  } = useQuery({
    queryKey: ["metrics-query", queryKeyTimeRange, metricsPayload],
    queryFn: () => {
      const freshResolved = resolveTimeRange({ from, to })
      return fetchMetricsQuery({
        ...metricsPayload!,
        time_range: {
          start: Math.floor(freshResolved.from.getTime() / 1000) * 1000,
          end: Math.floor(freshResolved.to.getTime() / 1000) * 1000,
        },
      })
    },
    enabled: metricsPayload !== null,
    refetchInterval: live ? LIVE_REFRESH_INTERVAL : false,
  })

  // Select the appropriate result based on mode
  const queryResult = isEventsMode ? eventsResult : metricsResult
  const isLoading = isEventsMode ? eventsLoading : metricsLoading
  const error = isEventsMode ? eventsError : metricsError

  // Called when user clicks "Run" - updates URL which triggers refetch
  const handleSubmit = (newState: typeof state) => {
    setState(newState)
  }

  const handleFieldClick = () => {
    // TODO: Could add a filter for this field to the query builder
    // For now, just a no-op since QueryBuilder manages its own local state
  }

  const rightPanel = (
    <FieldsPanel fields={fields} onFieldClick={handleFieldClick} />
  )

  const renderResults = () => {
    if (!hasQuery) return <ResultsPlaceholder />
    if (isLoading) return <ResultsLoading />
    if (error instanceof Error) return <ResultsError message={error.message} />
    if (!queryResult) return <ResultsPlaceholder />

    // Metrics mode always shows time series with connected lines
    if (isMetricsMode) {
      return <ResultsTimeSeries data={queryResult} connectNulls />
    }

    // Events mode respects visualization setting
    switch (state.visualization.type) {
      case "time_series":
        return <ResultsTimeSeries data={queryResult} />
      case "table":
      default:
        return <ResultsTable data={queryResult} live={live} />
    }
  }

  return (
    <ApplicationLayout title="Explore" showTimeRange rightPanel={rightPanel}>
      <div className="flex flex-col h-full gap-3">
        <QueryBuilder
          fields={fields}
          services={services}
          initialState={state}
          onSubmit={handleSubmit}
        />
        {renderResults()}
      </div>
    </ApplicationLayout>
  )
}
