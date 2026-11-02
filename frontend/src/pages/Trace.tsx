import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { TraceWaterfall, SpanDetailsPanel } from "@/components/trace"
import { resolveTraceFocus } from "@/components/trace/trace-subtree"
import { ResultsLoading, ResultsError } from "@/components/results"
import { Button } from "@/components/ui/button"
import { flattenApiError } from "@/lib/api-error"
import { useSpanFocus } from "@/hooks/use-span-focus"
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
    const errorData = (await response.json()) as { error?: unknown }
    throw new Error(flattenApiError(errorData.error, "Failed to fetch trace"))
  }

  const result = (await response.json()) as { data: TraceQueryResult }
  return result.data
}

// ============================================================================
// Page
// ============================================================================

export default function Trace() {
  const { trace_id } = usePage<{ trace_id: string }>().props
  const { from, to } = useTimeRange()
  const {
    focusedSpanId: urlFocusedSpanId,
    setFocusedSpanId,
    clearFocusHref,
  } = useSpanFocus()
  const [selectedSpanId, setSelectedSpanId] = useState<string | null>(null)

  // Use stable strings for query key to prevent infinite refetch loops.
  // Relative time strings like "now-1h" stay stable; timestamps are resolved fresh in queryFn.
  const { data, isLoading, error } = useQuery({
    queryKey: ["trace", trace_id, from, to],
    queryFn: () => {
      const resolved = resolveTimeRange({ from, to })
      return fetchTrace(trace_id, {
        start: Math.floor(resolved.from.getTime()),
        end: Math.floor(resolved.to.getTime()),
      })
    },
  })

  const resolvedFocus = useMemo(
    () => (data ? resolveTraceFocus(data.spans, urlFocusedSpanId) : null),
    [data, urlFocusedSpanId]
  )
  const focusedSpan = resolvedFocus?.focusSpan
  const focusSpanId = focusedSpan?.span_id ?? null
  const focusedSubtree = resolvedFocus?.subtree ?? null

  const handleSpanSelect = (spanId: string | null) => {
    setSelectedSpanId(spanId)
  }

  const handleZoomIn = (spanId: string) => {
    // Keep the panel only when the selected span is the new root. Clearing it
    // here avoids a panel blink while the Inertia visit updates the URL.
    setSelectedSpanId((current) => (current === spanId ? current : null))
    setFocusedSpanId(spanId)
  }

  // Find the selected span to get its timestamp
  const selectedSpan = selectedSpanId
    ? data?.spans.find((s) => s.span_id === selectedSpanId && s["meta.signal_type"] === "span")
    : null

  // A selection outside the focused subtree stays hidden while zoomed in.
  const selectedSpanIsVisible =
    !selectedSpan ||
    !focusSpanId ||
    selectedSpan.span_id === focusSpanId ||
    Boolean(
      focusedSubtree?.some(
        (span) =>
          span["meta.signal_type"] === "span" &&
          span.span_id === selectedSpan.span_id
      )
    )

  const rightPanel = selectedSpan && selectedSpanIsVisible ? (
    <SpanDetailsPanel
      spanId={selectedSpan.span_id}
      traceId={trace_id}
      spans={data?.spans ?? []}
      onFocus={
        selectedSpan.span_id === focusSpanId
          ? undefined
          : () => handleZoomIn(selectedSpan.span_id)
      }
    />
  ) : undefined

  const title = focusedSpan
    ? [
        { label: `Trace: ${trace_id}`, href: clearFocusHref },
        { label: focusedSpan.name },
      ]
    : `Trace: ${trace_id}`

  const focusMissing = Boolean(urlFocusedSpanId && data && !focusedSpan)

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
    return (
      <div className="flex h-full flex-col gap-2">
        {focusMissing && (
          <div
            role="status"
            className="flex flex-wrap items-center gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-sm"
          >
            <span className="min-w-0">
              Span <span className="font-mono text-xs">{urlFocusedSpanId}</span> is
              not part of this trace. Showing the full trace.
            </span>
            <Button
              type="button"
              variant="ghost"
              size="xs"
              className="ml-auto h-7 px-2"
              onClick={() => setFocusedSpanId(null)}
            >
              Clear focus
            </Button>
          </div>
        )}
        <TraceWaterfall
          spans={data.spans}
          focusSpanId={focusSpanId}
          selectedSpanId={selectedSpanId}
          onSpanSelect={handleSpanSelect}
          onZoomIn={handleZoomIn}
          onZoomOut={focusSpanId ? () => setFocusedSpanId(null) : undefined}
        />
      </div>
    )
  }

  return (
    <ApplicationLayout title={title} showTimeRange rightPanel={rightPanel}>
      <div className="flex h-full flex-col">{renderContent()}</div>
    </ApplicationLayout>
  )
}
