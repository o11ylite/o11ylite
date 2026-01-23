import { useQuery } from "@tanstack/react-query"

import { ResultsLoading, ResultsError } from "@/components/results"

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

async function fetchSpanDetails(
  id: string,
  timeRange: { start: number; end: number }
): Promise<Record<string, unknown> | null> {
  const response = await fetch("/api/query/events", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      time_range: timeRange,
      filter: { field: "id", op: "=", value: id },
      visualization: { type: "table" },
      limit: 1,
    }),
  })

  if (!response.ok) {
    const errorData = (await response.json()) as { error?: string }
    throw new Error(errorData.error ?? "Failed to fetch span details")
  }

  const result = (await response.json()) as { data: SpanDetailsResult }
  return result.data.rows[0] ?? null
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

// ============================================================================
// Component
// ============================================================================

export function SpanDetailsPanel({
  id,
  timestamp,
}: {
  id: string
  timestamp: number
}) {
  // Use a tight time range around the span's timestamp
  const timeRange = {
    start: Math.floor(timestamp),
    end: Math.floor(timestamp) + 10,
  }
  const { data, isLoading, error } = useQuery({
    queryKey: ["span-details", id],
    queryFn: () => fetchSpanDetails(id, timeRange),
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

  if (!data) {
    return (
      <div className="p-4 text-sm text-muted-foreground">Span not found</div>
    )
  }

  // Filter out null/undefined values and sort fields
  const fields = sortFields(
    Object.keys(data).filter((key) => data[key] != null)
  )

  return (
    <div className="flex flex-col gap-2 p-4">
      <h3 className="text-sm font-medium">Span Details</h3>
      <div className="space-y-2">
        {fields.map((field) => (
          <FieldEntry key={field} field={field} value={data[field]} />
        ))}
      </div>
    </div>
  )
}
