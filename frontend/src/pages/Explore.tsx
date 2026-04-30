import { useState } from "react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { QueryBuilder } from "@/components/query-builder"
import { TelemetryResult, EventCountTimeline } from "@/components/results"
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
import type { TableVisualization, Visualization } from "@/types"

// ============================================================================
// Page
// ============================================================================

export default function Explore() {
  const { state, setState, hasQuery } = useQueryState()
  const { from, to, live } = useTimeRange()
  const [runId, setRunId] = useState(0)

  const mode = state.mode ?? "events"
  const isEventsMode = mode === "events"

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
    runId,
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

  const handleVisualizationChange = (viz: Visualization) => {
    setState({ ...state, visualization: viz })
  }

  // Called when user clicks "Run" - updates URL which triggers refetch
  // Clear sort when aggregations change (fields change between raw and aggregated)
  const handleSubmit = (newState: typeof state) => {
    // Bump runId so the query key changes even when the query and time
    // range strings are identical (e.g. re-running "now-15m" to "now").
    setRunId(r => r + 1)

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

  // Show the auxiliary count-over-time bar chart when the user is in the
  // typical "logs explorer" mode: events mode + table view + no
  // aggregations. With aggregations the table already shows a summarised
  // view, and a count-over-time histogram of raw events would be both
  // redundant and ambiguous (count of what?). The same gating mirrors the
  // existing pagination rule (`isTableWithoutAggregations`).
  const showCountTimeline = isEventsMode && isTableWithoutAggregations

  return (
    <ApplicationLayout title="Explore" showTimeRange rightPanel={rightPanel}>
      <div className="flex flex-col h-full gap-3">
        <QueryBuilder
          initialState={state}
          onSubmit={handleSubmit}
        />
        {showCountTimeline && (
          <EventCountTimeline
            state={state}
            from={from}
            to={to}
            live={live}
            runId={runId}
          />
        )}
        <TelemetryResult
          mode={mode}
          hasQuery={isEventsMode || hasQuery}
          data={queryResult}
          isLoading={isLoading}
          error={error}
          visualization={state.visualization}
          onVisualizationChange={handleVisualizationChange}
          live={live}
          canGoPrev={paginationEnabled && cursorStack.length > 1}
          onPrevPage={paginationEnabled ? handlePrevPage : undefined}
          onNextPage={paginationEnabled ? handleNextPage : undefined}
          sortable={sortingEnabled}
        />
      </div>
    </ApplicationLayout>
  )
}
