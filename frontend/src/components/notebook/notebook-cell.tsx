import { useState } from "react"
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
import type { NotebookCell as NotebookCellType } from "@/types"

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

  const queryState = queryStateFromEntity(cell)

  const { data, isLoading, error, eventsPayload, metricsPayload } =
    useQueryExecution({
      state: queryState,
      from: effectiveFrom,
      to: effectiveTo,
      queryKeyPrefix: ["notebook-cell", cell.id],
    })

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

  const hasQuery = eventsPayload !== null || metricsPayload !== null
  const isEventsMode = cell.queryMode === "events"

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

    return <ResultsTable data={data} />
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
