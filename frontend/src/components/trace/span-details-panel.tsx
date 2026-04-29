import { useMemo } from "react"
import { useQuery } from "@tanstack/react-query"

import { ResultsLoading, ResultsError } from "@/components/results"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { flattenApiError } from "@/lib/api-error"
import type { TraceSpan } from "@/types"

// ============================================================================
// Types
// ============================================================================

interface SpanDetailsResult {
  rows: Record<string, unknown>[]
  total_count: number
}

// ============================================================================
// Constants
// ============================================================================

const COLLAPSE_THRESHOLD = 80

// ============================================================================
// API
// ============================================================================

async function fetchSpanWithEvents(
  spanId: string,
  timeRange: { start: number; end: number }
): Promise<{ span: Record<string, unknown> | null; events: Record<string, unknown>[] }> {
  const response = await fetch("/api/query/events", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      time_range: timeRange,
      filter: { field: "span_id", op: "=", value: spanId },
      visualization: { type: "table" },
      limit: 100,
    }),
  })

  if (!response.ok) {
    const errorData = (await response.json()) as { error?: unknown }
    throw new Error(flattenApiError(errorData.error, "Failed to fetch span details"))
  }

  const result = (await response.json()) as { data: SpanDetailsResult }
  const rows = result.data.rows

  // Separate span from span_events
  const span = rows.find((r) => r["meta.signal_type"] === "span") ?? null
  const events = rows.filter((r) => r["meta.signal_type"] === "span_event")

  return { span, events }
}

// ============================================================================
// Helpers
// ============================================================================

function stringifyValue(value: unknown): string {
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

// Sort fields: standard fields first (alphabetically), then attr.* fields (alphabetically)
function sortFields(fields: string[]): string[] {
  return fields.sort((a, b) => {
    const aIsAttr = a.startsWith("attr.")
    const bIsAttr = b.startsWith("attr.")
    if (aIsAttr !== bIsAttr) return aIsAttr ? 1 : -1
    return a.localeCompare(b)
  })
}

function formatRelativeTime(eventTimestamp: number, spanStart: number): string {
  const delta = eventTimestamp - spanStart
  if (delta < 1) return `+${(delta * 1000).toFixed(0)}μs`
  if (delta < 1000) return `+${delta.toFixed(1)}ms`
  return `+${(delta / 1000).toFixed(2)}s`
}

// ============================================================================
// Sub-components
// ============================================================================

function FieldEntry({ field, value }: { field: string; value: unknown }) {
  const stringified = stringifyValue(value)
  const isLong = stringified.length > COLLAPSE_THRESHOLD

  if (!isLong) {
    return (
      <div className="flex flex-col gap-0.5">
        <span className="text-xs text-muted-foreground">{field}</span>
        <span className="break-all text-sm">{stringified}</span>
      </div>
    )
  }

  return (
    <details className="group">
      <summary className="flex cursor-pointer list-none flex-col gap-0.5">
        <span className="text-xs text-muted-foreground">{field}</span>
        <span className="text-sm text-muted-foreground">
          ({stringified.length} chars)
        </span>
      </summary>
      <div className="mt-1 rounded bg-muted/50 p-2">
        <span className="break-all text-sm">{stringified}</span>
      </div>
    </details>
  )
}

function SpanEventItem({
  event,
  spanStart,
}: {
  event: Record<string, unknown>
  spanStart: number
}) {
  const name = event.name as string
  const timestamp = event.timestamp as number

  // Get all fields except common ones for the expanded view
  const excludeFields = new Set(["name", "timestamp", "meta.signal_type", "span_id", "trace_id", "service"])
  const fields = sortFields(
    Object.keys(event).filter((key) => event[key] != null && !excludeFields.has(key))
  )

  return (
    <Collapsible>
      <CollapsibleTrigger className="flex w-full items-center gap-2 rounded px-2 py-1.5 text-left hover:bg-muted/50">
        <span className="text-amber-500">*</span>
        <span className="flex-1 truncate text-sm" title={name}>{name}</span>
        <span className="shrink-0 text-xs text-muted-foreground">
          {formatRelativeTime(timestamp, spanStart)}
        </span>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="ml-4 space-y-1.5 border-l border-border py-2 pl-3">
          {fields.map((field) => (
            <FieldEntry key={field} field={field} value={event[field]} />
          ))}
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}

// ============================================================================
// Component
// ============================================================================

export function SpanDetailsPanel({
  spanId,
  spans,
}: {
  spanId: string
  spans: TraceSpan[]
}) {
  // Find the span in our existing data to get its timestamp for the query
  const spanInfo = useMemo(
    () => spans.find((s) => s.span_id === spanId && s["meta.signal_type"] === "span"),
    [spans, spanId]
  )

  // Use a wide time range based on the span's timestamp
  const timeRange = useMemo(() => {
    // Default to a very wide range that will be filtered on the backend
    if (!spanInfo) return { start: 0, end: Number.MAX_SAFE_INTEGER }
    const ts = spanInfo.timestamp
    // Use a window around the span timestamp
    return {
      start: Math.floor(ts) - 1000,
      end: Math.floor(ts + (spanInfo["span.duration_ms"] ?? 0) + 1000),
    }
  }, [spanInfo])

  const { data, isLoading, error } = useQuery({
    queryKey: ["span-details", spanId],
    queryFn: () => fetchSpanWithEvents(spanId, timeRange),
    enabled: !!spanInfo,
  })

  if (isLoading) {
    return (
      <div className="p-4">
        <ResultsLoading />
      </div>
    )
  }

  if (error instanceof Error) {
    return (
      <div className="p-4">
        <ResultsError message={error.message} />
      </div>
    )
  }

  if (!data?.span) {
    return (
      <div className="p-4 text-sm text-muted-foreground">Span not found</div>
    )
  }

  const { span, events } = data

  // Filter out null/undefined values and sort fields
  const fields = sortFields(
    Object.keys(span).filter((key) => span[key] != null)
  )

  return (
    <div className="flex flex-col gap-4 p-4">
      {/* Span Details Section */}
      <div>
        <h3 className="mb-2 text-sm font-medium">Span Details</h3>
        <div className="space-y-2">
          {fields.map((field) => (
            <FieldEntry key={field} field={field} value={span[field]} />
          ))}
        </div>
      </div>

      {/* Span Events Section */}
      {events.length > 0 && (
        <div>
          <h3 className="mb-2 text-sm font-medium">
            Span Events ({events.length})
          </h3>
          <div className="rounded border border-border">
            {events.map((event, idx) => (
              <div
                key={`${String(event.name)}-${String(event.timestamp)}-${idx}`}
                className={idx > 0 ? "border-t border-border" : ""}
              >
                <SpanEventItem event={event} spanStart={span.timestamp as number} />
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
