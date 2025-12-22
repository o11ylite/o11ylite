import { useQuery } from "@tanstack/react-query"

import ApplicationLayout from "@/components/layouts/application-layout"
import { QueryBuilder } from "@/components/query-builder"
import {
  ResultsTable,
  ResultsPlaceholder,
  ResultsLoading,
  ResultsError,
} from "@/components/results"
import { FieldsPanel } from "@/components/fields-panel"
import { useQueryState } from "@/hooks/use-query-state"
import { useTimeRange, resolveTimeRange } from "@/hooks/use-time-range"
import type {
  Field,
  EventsQuery,
  QueryResponse,
  SimpleFilter,
  FilterExpr,
} from "@/types"

// ============================================================================
// Mock Data (will come from backend later)
// ============================================================================

const MOCK_FIELDS: Field[] = [
  { name: "timestamp", type: "time" },
  { name: "service", type: "str" },
  { name: "severity", type: "enum" },
  { name: "message", type: "str" },
  { name: "trace_id", type: "str" },
  { name: "span_id", type: "str" },
  { name: "duration_ms", type: "num" },
  { name: "status_code", type: "num" },
  { name: "http_method", type: "str" },
  { name: "http_path", type: "str" },
]

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
  const { state, setState, hasQuery } = useQueryState()
  const { from, to } = useTimeRange()
  const timeRange = resolveTimeRange({ from, to })

  // Build the query for the API - only when hasQuery is true (URL has ?q=)
  const eventsQuery: EventsQuery | null = hasQuery
    ? {
        time_range: {
          start: Math.floor(timeRange.from.getTime() / 1000),
          end: Math.floor(timeRange.to.getTime() / 1000),
        },
        filter: buildFilterExpr(state.filters),
        aggregations:
          state.aggregations.length > 0 ? state.aggregations : undefined,
        group_by: state.groupBy.length > 0 ? state.groupBy : undefined,
        visualization: state.visualization,
      }
    : null

  // TanStack Query for fetching results
  // Query is keyed by the full eventsQuery object (includes time range from URL)
  // Re-fetches when URL changes (either ?q= or ?from=/?to=)
  const {
    data: queryResult,
    isLoading,
    error,
  } = useQuery({
    queryKey: ["events-query", eventsQuery],
    queryFn: () => fetchEventsQuery(eventsQuery!),
    enabled: eventsQuery !== null,
  })

  // Called when user clicks "Run" - updates URL which triggers refetch
  const handleSubmit = (newState: typeof state) => {
    setState(newState)
  }

  const handleFieldClick = () => {
    // TODO: Could add a filter for this field to the query builder
    // For now, just a no-op since QueryBuilder manages its own local state
  }

  const rightPanel = (
    <FieldsPanel fields={MOCK_FIELDS} onFieldClick={handleFieldClick} />
  )

  const renderResults = () => {
    if (!hasQuery) return <ResultsPlaceholder />
    if (isLoading) return <ResultsLoading />
    if (error instanceof Error) return <ResultsError message={error.message} />
    if (queryResult) return <ResultsTable data={queryResult} />
    return <ResultsPlaceholder />
  }

  return (
    <ApplicationLayout title="Explore" showTimeRange rightPanel={rightPanel}>
      <div className="flex flex-col h-full gap-3">
        <QueryBuilder
          fields={MOCK_FIELDS}
          initialState={state}
          onSubmit={handleSubmit}
        />
        {renderResults()}
      </div>
    </ApplicationLayout>
  )
}
