import { useState } from "react"
import type { FormDataConvertible } from "@inertiajs/core"
import { router } from "@inertiajs/react"
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
import {
  queryStateFromEntity,
  queryStateToPayload,
} from "@/lib/query-helpers"
import { useQueryExecution } from "@/hooks/use-query-execution"
import { resolveTimeRange } from "@/hooks/use-time-range"
import type {
  NotebookCell as NotebookCellType,
  QueryBuilderState,
  TableVisualization,
} from "@/types"

// ============================================================================
// Helpers
// ============================================================================

function cellUrl(notebookId: string, cellId: string): string {
  return `/notebooks/${notebookId}/cells/${cellId}`
}

/**
 * Build the full cell update payload from current cell props and an
 * optional queryState override. This is the single place that assembles
 * the payload shape expected by the backend.
 */
function buildCellPayload(
  cell: NotebookCellType,
  queryState: QueryBuilderState,
  overrides?: { title?: string | null; description?: string | null; pinnedFrom?: string | null; pinnedTo?: string | null },
): Record<string, FormDataConvertible> {
  return {
    title: overrides?.title !== undefined ? overrides.title : cell.title,
    description: overrides?.description !== undefined ? overrides.description : cell.description,
    query_mode: queryState.mode,
    query: queryStateToPayload(queryState),
    pinned_from: overrides?.pinnedFrom !== undefined ? overrides.pinnedFrom : cell.pinned_from,
    pinned_to: overrides?.pinnedTo !== undefined ? overrides.pinnedTo : cell.pinned_to,
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
  const [saving, setSaving] = useState(false)

  const isPinned = cell.pinned_from !== null && cell.pinned_to !== null
  const effectiveFrom = isPinned ? cell.pinned_from! : globalFrom
  const effectiveTo = isPinned ? cell.pinned_to! : globalTo

  const queryState = queryStateFromEntity(cell)

  const { data, isLoading, error, eventsPayload, metricsPayload } =
    useQueryExecution({
      state: queryState,
      from: effectiveFrom,
      to: effectiveTo,
      queryKeyPrefix: ["notebook-cell", cell.id],
    })

  // ------------------------------------------------------------------
  // Cell persistence — all router.put calls go through here
  // ------------------------------------------------------------------

  const saveCell = (
    payload: Record<string, FormDataConvertible>,
    opts?: { onFinish?: () => void },
  ) => {
    router.put(cellUrl(notebookId, cell.id), payload, {
      onFinish: opts?.onFinish,
    })
  }

  const handleSaveQuery = (title: string | null, description: string | null, newQueryState: QueryBuilderState) => {
    setSaving(true)
    saveCell(buildCellPayload(cell, newQueryState, { title, description }), {
      onFinish: () => {
        setSaving(false)
        setDrawerOpen(false)
      },
    })
  }

  const handleTogglePin = () => {
    let pinnedFrom: string | null = null
    let pinnedTo: string | null = null

    if (!isPinned) {
      const resolved = resolveTimeRange({ from: globalFrom, to: globalTo })
      pinnedFrom = resolved.from.toISOString()
      pinnedTo = resolved.to.toISOString()
    }

    saveCell(buildCellPayload(cell, queryState, { pinnedFrom, pinnedTo }))
  }

  const handleDisplayedFieldsChange = (fields: string[] | null) => {
    if (queryState.visualization.type !== "table") return

    const viz: TableVisualization = fields
      ? { ...queryState.visualization, displayed_fields: fields }
      : (() => {
          const { displayed_fields, ...rest } = queryState.visualization
          void displayed_fields
          return rest
        })()

    const newQueryState = { ...queryState, visualization: viz }
    saveCell(buildCellPayload(cell, newQueryState))
  }

  const handleDelete = () => {
    if (!confirm("Delete this cell?")) return
    router.delete(cellUrl(notebookId, cell.id))
  }

  const handleMove = (direction: "up" | "down") => {
    router.post(`${cellUrl(notebookId, cell.id)}/move`, { direction })
  }

  // ------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------

  const hasQuery = eventsPayload !== null || metricsPayload !== null
  const isEventsMode = cell.query_mode === "events"

  const currentDisplayedFields =
    queryState.visualization.type === "table"
      ? queryState.visualization.displayed_fields ?? null
      : null

  const renderResults = () => {
    if (!hasQuery) return <ResultsPlaceholder />
    if (isLoading) return <ResultsLoading />
    if (error instanceof Error) return <ResultsError message={error.message} />
    if (!data) return <ResultsPlaceholder />

    if (!isEventsMode) {
      return <ResultsTimeSeries data={data} connectNulls />
    }

    if (queryState.visualization.type === "time_series") {
      return <ResultsTimeSeries data={data} />
    }

    return (
      <ResultsTable
        data={data}
        displayedFields={currentDisplayedFields}
        onDisplayedFieldsChange={handleDisplayedFieldsChange}
      />
    )
  }

  const cellTitle = cell.title || "Untitled"
  const modeBadge = (
    <span className="rounded bg-muted px-1.5 py-0.5 text-xs text-muted-foreground">
      {cell.query_mode}
    </span>
  )

  return (
    <>
      <div className={isFirst ? "" : "border-t pt-2"}>
        <div className="flex items-center gap-2 px-1 py-2">
          <span className="text-sm font-medium truncate">{cellTitle}</span>
          {modeBadge}
          <CellTimeBadge
            pinnedFrom={cell.pinned_from}
            pinnedTo={cell.pinned_to}
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
        {cell.description && (
          <p className="px-1 pb-2 text-sm text-muted-foreground whitespace-pre-line">
            {cell.description}
          </p>
        )}
        <div className="min-h-[200px]">
          {renderResults()}
        </div>
      </div>

      <CellQueryDrawer
        cell={cell}
        open={drawerOpen}
        onOpenChange={setDrawerOpen}
        onSave={handleSaveQuery}
        saving={saving}
      />
    </>
  )
}
