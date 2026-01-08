import { useQuery } from "@tanstack/react-query"
import { usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { TraceWaterfall } from "@/components/trace-waterfall"
import { ResultsLoading, ResultsError } from "@/components/results"
import { useTimeRange, resolveTimeRange } from "@/hooks/use-time-range"
import type { TraceQueryResult } from "@/types"

// ============================================================================
// API
// ============================================================================

async function fetchTrace(
  traceId: string,
  timeRange: { start: number; end: number }
): Promise<TraceQueryResult> {
  const response = await fetch("/api/query/events", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      time_range: timeRange,
      filter: { field: "trace_id", op: "=", value: traceId },
      visualization: { type: "trace" },
    }),
  })

  if (!response.ok) {
    const errorData = (await response.json()) as { error?: string }
    throw new Error(errorData.error ?? "Failed to fetch trace")
  }

  const result = (await response.json()) as { data: TraceQueryResult }
  return result.data
}

// ============================================================================
// Page
// ============================================================================

export default function Trace() {
  const { traceId } = usePage<{ traceId: string }>().props
  const { from, to } = useTimeRange()

  // Use stable strings for query key to prevent infinite refetch loops.
  // Relative time strings like "now-1h" stay stable; timestamps are resolved fresh in queryFn.
  const { data, isLoading, error } = useQuery({
    queryKey: ["trace", traceId, from, to],
    queryFn: () => {
      const resolved = resolveTimeRange({ from, to })
      return fetchTrace(traceId, {
        start: Math.floor(resolved.from.getTime()),
        end: Math.floor(resolved.to.getTime()),
      })
    },
  })

  const renderContent = () => {
    if (isLoading) return <ResultsLoading />
    if (error instanceof Error) return <ResultsError message={error.message} />
    if (!data || data.spans.length === 0) {
      return (
        <div className="flex h-64 items-center justify-center text-muted-foreground">
          No spans found for this trace in the selected time range
        </div>
      )
    }
    return <TraceWaterfall spans={data.spans} />
  }

  return (
    <ApplicationLayout title={`Trace: ${traceId}`} showTimeRange>
      <div className="flex h-full flex-col">{renderContent()}</div>
    </ApplicationLayout>
  )
}
