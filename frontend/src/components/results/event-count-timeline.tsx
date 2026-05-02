import { useQuery } from "@tanstack/react-query"

import { TimeSeriesChart } from "./time-series/time-series-chart"
import { flattenApiError } from "@/lib/api-error"
import { withServiceFilter, buildFilterExpr } from "@/lib/query-helpers"
import { resolveTimeRange, LIVE_REFRESH_INTERVAL } from "@/hooks/use-time-range"
import type {
  EventsQuery,
  QueryBuilderState,
  QueryResponse,
  TimeSeriesQueryResult,
} from "@/types"

// ============================================================================
// EventCountTimeline
// ============================================================================
//
// Auxiliary "data glance" bar chart shown above the events table. Renders
// count(*) bucketed over the active time range, sharing the page's filter +
// service scope. Drag-to-zoom is inherited from TimeSeriesChart and updates
// the page's time range via useTimeRange, so the table refetches into the
// selected window.
//
// This fires its own /api/query/events request (separate from the main
// table query) so the bucketed series isn't tangled with table pagination
// or sort. The chart is small (~120px tall) and intentionally minimal —
// it's a navigation aid, not the primary visualization.

const CHART_HEIGHT_CLASS = "h-[120px]"

async function fetchEventsCount(payload: EventsQuery): Promise<QueryResponse> {
  const response = await fetch("/api/query/events", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  })
  if (!response.ok) {
    const errorData = (await response.json()) as { error?: unknown }
    throw new Error(flattenApiError(errorData.error, "Query failed"))
  }
  return response.json() as Promise<QueryResponse>
}

interface EventCountTimelineProps {
  state: QueryBuilderState
  from: string
  to: string
  live?: boolean
  /** Bumped when the user clicks "Run" so the chart refetches in lockstep. */
  runId?: number
}

export function EventCountTimeline({
  state,
  from,
  to,
  live = false,
  runId,
}: EventCountTimelineProps) {
  const filter = withServiceFilter(buildFilterExpr(state.filters), state.service)

  // Stable, resolution-independent cache key. The actual epoch ms are
  // resolved inside queryFn, matching the main events query's pattern in
  // use-query-execution.ts (so relative ranges like "now-15m" don't churn
  // the cache every render).
  const queryKey = ["explore-aux-count", { from, to }, filter, runId]

  const { data, isLoading, error } = useQuery({
    queryKey,
    queryFn: () => {
      const resolved = resolveTimeRange({ from, to })
      const time_range = {
        start: Math.floor(resolved.from.getTime() / 1000) * 1000,
        end: Math.floor(resolved.to.getTime() / 1000) * 1000,
      }
      const payload: EventsQuery = {
        time_range,
        ...(filter ? { filter } : {}),
        aggregations: [{ id: "A", field: "*", function: "count" }],
        visualization: { type: "time_series" },
      }
      return fetchEventsCount(payload)
    },
    refetchInterval: live ? LIVE_REFRESH_INTERVAL : false,
  })

  // Reserve the same vertical space whether loading, errored, or empty so
  // the page doesn't reflow when the chart populates.
  if (isLoading || !data) {
    return (
      <div
        className={`${CHART_HEIGHT_CLASS} rounded-lg border bg-muted/10`}
        aria-label="Event count over time"
        aria-busy={isLoading || undefined}
      />
    )
  }
  if (error instanceof Error) {
    return (
      <div
        className={`${CHART_HEIGHT_CLASS} rounded-lg border bg-muted/10 px-3 py-2 text-xs text-muted-foreground`}
      >
        Couldn’t load event count: {error.message}
      </div>
    )
  }

  const result = data.data as TimeSeriesQueryResult
  const series = result.series ?? []
  const totalEvents = series
    .flatMap((s) => s.data ?? [])
    .reduce((acc, p) => acc + (p.value ?? 0), 0)

  return (
    <div className="rounded-lg border" aria-label="Event count over time">
      <div className="flex items-center justify-between px-3 pt-2 text-xs text-muted-foreground">
        <span>Events over time</span>
        <span className="tabular-nums">{totalEvents.toLocaleString()} events</span>
      </div>
      <TimeSeriesChart
        data={result}
        renderAs="bar"
        hideLegend
        containerClassName={`${CHART_HEIGHT_CLASS} w-full px-2 pb-2 touch-pan-y`}
      />
    </div>
  )
}
