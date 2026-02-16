import { useState } from "react"
import { router } from "@inertiajs/react"
import { useQuery } from "@tanstack/react-query"
import {
  Pencil,
  Trash2,
  ChevronUp,
  ChevronDown,
} from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  ResultsTable,
  ResultsTimeSeries,
  ResultsLoading,
  ResultsError,
  ResultsPlaceholder,
} from "@/components/results"
import { CellTimeBadge } from "@/components/notebook/cell-time-badge"
import { CellQueryDrawer } from "@/components/notebook/cell-query-drawer"
import { queryStateFromCell, queryStateToPayload } from "@/components/notebook/query-helpers"
import { resolveTimeRange } from "@/hooks/use-time-range"
import { extractSimpleHaving } from "@/lib/metric-query-helpers"
import type {
  NotebookCell as NotebookCellType,
  EventsQuery,
  MetricsQuery,
  QueryResponse,
  SimpleFilter,
  FilterExpr,
  MetricDefinition,
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
  const valid = filters.filter((f) => f.field && f.value !== "")
  if (valid.length === 0) return undefined
  if (valid.length === 1) return valid[0]
  return { and: valid }
}

function resolveMs(from: string, to: string) {
  const resolved = resolveTimeRange({ from, to })
  return {
    start: Math.floor(resolved.from.getTime() / 1000) * 1000,
    end: Math.floor(resolved.to.getTime() / 1000) * 1000,
  }
}

// ============================================================================
// Component
// ============================================================================

export function NotebookCell({
  cell,
  notebookId,
  globalFrom,
  globalTo,
  isFirst,
  isLast,
}: {
  cell: NotebookCellType
  notebookId: string
  globalFrom: string
  globalTo: string
  isFirst: boolean
  isLast: boolean
}) {
  const [drawerOpen, setDrawerOpen] = useState(false)

  const isPinned = cell.pinnedFrom !== null && cell.pinnedTo !== null
  const effectiveFrom = isPinned ? cell.pinnedFrom! : globalFrom
  const effectiveTo = isPinned ? cell.pinnedTo! : globalTo

  const queryState = queryStateFromCell(cell)
  const isEventsMode = cell.queryMode === "events"

  // Build query payloads
  const eventsPayload = isEventsMode
    ? {
        filter: buildFilterExpr(queryState.filters),
        aggregations: queryState.aggregations.length > 0 ? queryState.aggregations : undefined,
        group_by: queryState.groupBy.length > 0 ? queryState.groupBy : undefined,
        ...(queryState.having ? { having: queryState.having } : {}),
        limit: queryState.limit ?? 100,
        visualization: queryState.visualization,
      }
    : null

  const validMetrics = (queryState.metrics ?? []).filter(
    (m: MetricDefinition) => m.name,
  )
  const metricsPayload = !isEventsMode && validMetrics.length > 0
    ? {
        filter: buildFilterExpr(queryState.filters),
        group_by: queryState.groupBy.length > 0 ? queryState.groupBy : undefined,
        ...(queryState.having ? { having: extractSimpleHaving(queryState.having) } : {}),
        metrics: validMetrics,
      }
    : null

  // Use stable key based on time range strings (resolve fresh inside queryFn)
  const timeKey = { from: effectiveFrom, to: effectiveTo }

  const {
    data: eventsResult,
    isLoading: eventsLoading,
    error: eventsError,
  } = useQuery({
    queryKey: ["notebook-cell", cell.id, "events", timeKey, eventsPayload],
    queryFn: () => {
      const timeRange = resolveMs(effectiveFrom, effectiveTo)
      return fetchEventsQuery({ ...eventsPayload!, time_range: timeRange })
    },
    enabled: eventsPayload !== null,
  })

  const {
    data: metricsResult,
    isLoading: metricsLoading,
    error: metricsError,
  } = useQuery({
    queryKey: ["notebook-cell", cell.id, "metrics", timeKey, metricsPayload],
    queryFn: () => {
      const timeRange = resolveMs(effectiveFrom, effectiveTo)
      return fetchMetricsQuery({ ...metricsPayload!, time_range: timeRange })
    },
    enabled: metricsPayload !== null,
  })

  const result = isEventsMode ? eventsResult : metricsResult
  const isLoading = isEventsMode ? eventsLoading : metricsLoading
  const error = isEventsMode ? eventsError : metricsError

  const handleDelete = () => {
    if (!confirm("Delete this cell?")) return
    router.delete(`/notebooks/${notebookId}/cells/${cell.id}`)
  }

  const handleMove = (direction: "up" | "down") => {
    router.post(`/notebooks/${notebookId}/cells/${cell.id}/move`, { direction })
  }

  const handleTogglePin = () => {
    const payload = {
      title: cell.title,
      query_mode: cell.queryMode,
      query: JSON.stringify(queryStateToPayload(queryState)),
      pinned_from: isPinned ? null : globalFrom,
      pinned_to: isPinned ? null : globalTo,
    }
    router.put(`/notebooks/${notebookId}/cells/${cell.id}`, payload)
  }

  const hasQuery = isEventsMode
    ? eventsPayload !== null
    : metricsPayload !== null

  const renderResults = () => {
    if (!hasQuery) return <ResultsPlaceholder />
    if (isLoading) return <ResultsLoading />
    if (error instanceof Error) return <ResultsError message={error.message} />
    if (!result) return <ResultsPlaceholder />

    if (!isEventsMode) {
      return <ResultsTimeSeries data={result} connectNulls />
    }

    if (queryState.visualization.type === "time_series") {
      return <ResultsTimeSeries data={result} />
    }

    return <ResultsTable data={result} />
  }

  const cellTitle = cell.title || "Untitled"
  const modeBadge = (
    <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
      {cell.queryMode}
    </span>
  )

  return (
    <>
      <div className="rounded-lg border">
        <div className="flex items-center gap-2 border-b px-3 py-2">
          <span className="text-sm font-medium truncate">{cellTitle}</span>
          {modeBadge}
          <CellTimeBadge
            pinnedFrom={cell.pinnedFrom}
            pinnedTo={cell.pinnedTo}
            onToggle={handleTogglePin}
          />
          <div className="ml-auto flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleMove("up")}
              disabled={isFirst}
            >
              <ChevronUp size={14} />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => handleMove("down")}
              disabled={isLast}
            >
              <ChevronDown size={14} />
            </Button>
            <Button
              variant="ghost"
              size="icon-sm"
              onClick={() => setDrawerOpen(true)}
            >
              <Pencil size={14} />
            </Button>
            <Button variant="ghost" size="icon-sm" onClick={handleDelete}>
              <Trash2 size={14} />
            </Button>
          </div>
        </div>
        <div className="p-3 min-h-[200px] max-h-[500px] overflow-y-auto">
          {renderResults()}
        </div>
      </div>

      <CellQueryDrawer
        cell={cell}
        notebookId={notebookId}
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
      />
    </>
  )
}
