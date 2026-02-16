import { useState, useEffect } from "react"
import { router } from "@inertiajs/react"
import { Save } from "lucide-react"

import { QueryBuilder } from "@/components/query-builder"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from "@/components/ui/sheet"
import {
  queryStateToPayload,
  queryStateFromEntity,
} from "@/lib/query-helpers"
import type { NotebookCell, QueryBuilderState } from "@/types"

export function CellQueryDrawer({
  cell,
  notebookId,
  open,
  onOpenChange,
}: {
  cell: NotebookCell
  notebookId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const [title, setTitle] = useState(cell.title ?? "")
  const [queryState, setQueryState] = useState<QueryBuilderState>(
    queryStateFromEntity(cell)
  )
  const [saving, setSaving] = useState(false)

  // Reset local state when drawer opens (cell props may have changed)
  useEffect(() => {
    if (open) {
      setTitle(cell.title ?? "")
      setQueryState(queryStateFromEntity(cell))
    }
  }, [open, cell])

  const handleSave = () => {
    const payload = {
      title: title || null,
      query_mode: queryState.mode,
      query: JSON.stringify(queryStateToPayload(queryState)),
      pinned_from: cell.pinnedFrom,
      pinned_to: cell.pinnedTo,
    }

    router.put(`/notebooks/${notebookId}/cells/${cell.id}`, payload, {
      onBefore: () => setSaving(true),
      onFinish: () => {
        setSaving(false)
        onOpenChange(false)
      },
    })
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="sm:max-w-2xl w-full overflow-y-auto">
        <SheetHeader>
          <SheetTitle>Edit Cell</SheetTitle>
          <SheetDescription>
            Configure the query for this cell.
          </SheetDescription>
        </SheetHeader>

        <div className="space-y-4 px-4 pb-4">
          <div className="space-y-2">
            <Label htmlFor="cell-title">Title</Label>
            <Input
              id="cell-title"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Optional cell title"
            />
          </div>

          <div className="space-y-2">
            <Label>Query</Label>
            <div className="rounded-lg border p-4">
              <QueryBuilder
                initialState={queryState}
                onSubmit={setQueryState}
                onChange={setQueryState}
                autoSubmit={false}
                embeddedMode
                showVisualizationToggle
              />
            </div>
          </div>

          <Button onClick={handleSave} disabled={saving}>
            <Save className="mr-2" size={16} />
            Save Cell
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
