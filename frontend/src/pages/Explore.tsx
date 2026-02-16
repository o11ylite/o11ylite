import { useState } from "react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { QueryBuilder } from "@/components/query-builder"
import {
  ResultsTable,
  ResultsTimeSeries,
  ResultsPlaceholder,
  ResultsLoading,
  ResultsError,
} from "@/components/results"
import { EventsSidePanel } from "@/components/events-side-panel"
import { MetricSidePanel } from "@/components/metric-side-panel"
import { useQueryState } from "@/hooks/use-query-state"
import {
  useTimeRange,
  LIVE_REFRESH_INTERVAL,
} from "@/hooks/use-time-range"
import {
  useQueryExecution,
  buildEventsPayload,
} from "@/hooks/use-query-execution"
import type { TableVisualization, SortConfig } from "@/types"

// ============================================================================
// Page
// ============================================================================

export default function Explore() {
  const { state, setState, hasQuery } = useQueryState()
  const { from, to, live } = useTimeRange()

  const mode = state.mode ?? "events"
  const isEventsMode = mode === "events"
  const isMetricsMode = mode === "metrics"

  // Sorting is enabled for all table queries (including aggregated)
  const sortingEnabled = state.visualization.type === "table"
  const currentSort = sortingEnabled
    ? (state.visualization as TableVisualization).sort
    : undefined

  // Pagination state (in-memory only, resets on query change, sort change, or refresh)
  // Page index is derived from stack length: [null] = page 0, [null, c1] = page 1
  // queryKey is stored to detect when query or sort changes and reset pagination
  const eventsBase = buildEventsPayload(state)
  const queryResetKey = JSON.stringify({ eventsBase, from, to, sort: currentSort })
  const [pagination, setPagination] = useState({
    queryKey: queryResetKey,
    cursorStack: [null] as (string | null)[],
  })

  // Reset pagination when query or sort changes (derived state during render)
  if (pagination.queryKey !== queryResetKey) {
    setPagination({ queryKey: queryResetKey, cursorStack: [null] })
  }

  // Use fresh stack if key matches, otherwise use reset value
  const cursorStack = pagination.queryKey === queryResetKey
    ? pagination.cursorStack
    : [null]
  const currentCursor = cursorStack[cursorStack.length - 1]

  // Pagination disabled in live mode (data constantly refreshing, cursors would be stale)
  const isTableWithoutAggregations =
    state.visualization.type === "table" && state.aggregations.length === 0
  const paginationEnabled = isTableWithoutAggregations && !live

  const { data: queryResult, isLoading, error } = useQueryExecution({
    state,
    from,
    to,
    queryKeyPrefix: "explore",
    live,
    refetchInterval: LIVE_REFRESH_INTERVAL,
    cursor: paginationEnabled ? currentCursor : undefined,
  })

  // Pagination handlers
  const handlePrevPage = () => {
    if (cursorStack.length > 1) {
      setPagination(p => ({ ...p, cursorStack: p.cursorStack.slice(0, -1) }))
    }
  }

  const handleNextPage = (nextCursor: string) => {
    setPagination(p => ({ ...p, cursorStack: [...p.cursorStack, nextCursor] }))
  }

  // Sort handler - updates visualization config which resets pagination via queryResetKey
  const handleSortChange = (newSort: SortConfig) => {
    setState({
      ...state,
      visualization: { ...state.visualization, sort: newSort } as TableVisualization,
    })
  }

  // Called when user clicks "Run" - updates URL which triggers refetch
  // Clear sort when aggregations change (fields change between raw and aggregated)
  const handleSubmit = (newState: typeof state) => {
    const aggregationsChanged =
      JSON.stringify(state.aggregations) !== JSON.stringify(newState.aggregations)

    if (aggregationsChanged && newState.visualization.type === "table") {
      const { sort, ...vizWithoutSort } = newState.visualization
      void sort // Explicitly discard
      setState({ ...newState, visualization: vizWithoutSort })
    } else {
      setState(newState)
    }
  }

  const rightPanel = isEventsMode ? (
    <EventsSidePanel />
  ) : (
    <MetricSidePanel />
  )

  const renderResults = () => {
    if (isMetricsMode && !hasQuery) return <ResultsPlaceholder />
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
        return (
          <ResultsTable
            data={queryResult}
            live={live}
            canGoPrev={paginationEnabled && cursorStack.length > 1}
            onPrevPage={paginationEnabled ? handlePrevPage : undefined}
            onNextPage={paginationEnabled ? handleNextPage : undefined}
            sortable={sortingEnabled}
            sort={currentSort}
            onSortChange={sortingEnabled ? handleSortChange : undefined}
          />
        )
    }
  }

  return (
    <ApplicationLayout title="Explore" showTimeRange rightPanel={rightPanel}>
      <div className="flex flex-col h-full gap-3">
        <QueryBuilder
          initialState={state}
          onSubmit={handleSubmit}
        />
        {renderResults()}
      </div>
    </ApplicationLayout>
  )
}
