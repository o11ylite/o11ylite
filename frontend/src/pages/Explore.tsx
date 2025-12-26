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
import { useTimeRange, resolveTimeRange } from "@/hooks/use-time-range"
import type {
  Field,
  Service,
  EventsQuery,
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
    <FieldsPanel fields={fields} onFieldClick={handleFieldClick} />
  )

  const renderResults = () => {
    if (!hasQuery) return <ResultsPlaceholder />
    if (isLoading) return <ResultsLoading />
    if (error instanceof Error) return <ResultsError message={error.message} />
    if (!queryResult) return <ResultsPlaceholder />

    switch (state.visualization.type) {
      case "time_series":
        return <ResultsTimeSeries data={queryResult} />
      case "table":
      default:
        return <ResultsTable data={queryResult} />
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
