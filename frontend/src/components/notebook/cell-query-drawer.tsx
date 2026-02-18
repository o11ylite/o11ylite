import { useState } from "react"
import { Save } from "lucide-react"

import { QueryBuilder } from "@/components/query-builder"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Textarea } from "@/components/ui/textarea"
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
} from "@/components/ui/sheet"
import { queryStateFromEntity } from "@/lib/query-helpers"
import type { NotebookCell, QueryBuilderState } from "@/types"

export function CellQueryDrawer({
  cell,
  open,
  onOpenChange,
  onSave,
  saving = false,
}: {
  cell: NotebookCell
  open: boolean
  onOpenChange: (open: boolean) => void
  onSave: (title: string | null, description: string | null, queryState: QueryBuilderState) => void
  saving?: boolean
}) {
  const [title, setTitle] = useState(cell.title ?? "")
  const [description, setDescription] = useState(cell.description ?? "")
  const [queryState, setQueryState] = useState<QueryBuilderState>(
    queryStateFromEntity(cell)
  )

  // Reset local state when drawer opens (cell props may have changed)
  const [prevOpen, setPrevOpen] = useState(open)
  if (open && !prevOpen) {
    setTitle(cell.title ?? "")
    setDescription(cell.description ?? "")
    setQueryState(queryStateFromEntity(cell))
  }
  if (open !== prevOpen) {
    setPrevOpen(open)
  }

  const handleSave = () => {
    onSave(title || null, description || null, queryState)
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
            <Label htmlFor="cell-description">Description</Label>
            <Textarea
              id="cell-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional notes about this cell"
              rows={3}
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
