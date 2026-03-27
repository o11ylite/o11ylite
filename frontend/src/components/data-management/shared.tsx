import { Search, Ban, CheckCircle, Trash2 } from "lucide-react"

import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import type { FieldCategory, FieldStatus } from "@/types"

// ---------------------------------------------------------------------------
// Status badge

export function StatusBadge({ status }: { status: FieldStatus }) {
  if (status === "blocked") {
    return (
      <Badge variant="outline" className="bg-red-500/10 text-red-600 dark:text-red-400 border-red-500/20">
        Blocked
      </Badge>
    )
  }
  return (
    <Badge variant="outline" className="bg-green-500/10 text-green-600 dark:text-green-400 border-green-500/20">
      Active
    </Badge>
  )
}

// ---------------------------------------------------------------------------
// Category badge — distinguishes system fields from user attributes

export function CategoryBadge({ category }: { category: FieldCategory }) {
  if (category === "system") {
    return (
      <Badge variant="outline" className="text-muted-foreground border-border text-[10px]">
        System
      </Badge>
    )
  }
  return (
    <Badge variant="outline" className="text-blue-600 dark:text-blue-400 border-blue-500/30 bg-blue-500/5 text-[10px]">
      Attribute
    </Badge>
  )
}

// ---------------------------------------------------------------------------
// Bulk action bar

export function BulkActionBar({
  count,
  onBlock,
  onActivate,
  onDelete,
}: {
  count: number
  onBlock: () => void
  onActivate: () => void
  onDelete: () => void
}) {
  return (
    <div className="flex items-center gap-2 rounded-md border bg-muted/50 px-3 py-2">
      <span className="text-sm text-muted-foreground">
        {count} selected
      </span>
      <div className="ml-auto flex gap-2">
        <Button variant="outline" size="sm" onClick={onBlock}>
          <Ban className="mr-1" size={14} />
          Block
        </Button>
        <Button variant="outline" size="sm" onClick={onActivate}>
          <CheckCircle className="mr-1" size={14} />
          Activate
        </Button>
        <Button variant="destructive" size="sm" onClick={onDelete}>
          <Trash2 className="mr-1" size={14} />
          Delete
        </Button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Delete confirmation dialog

export function DeleteConfirmDialog({
  open,
  fields,
  onConfirm,
  onCancel,
}: {
  open: boolean
  fields: string[]
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={(v) => !v && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Delete {fields.length} field{fields.length > 1 ? "s" : ""}?</DialogTitle>
          <DialogDescription>
            This will permanently drop the column{fields.length > 1 ? "s" : ""} from the database.
            Historical data for {fields.length > 1 ? "these fields" : "this field"} will be lost.
            The field name{fields.length > 1 ? "s" : ""} will be auto-blocked to prevent re-creation.
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-40 overflow-y-auto rounded-md border bg-muted/50 p-2">
          <ul className="space-y-1">
            {fields.map((f) => (
              <li key={f} className="font-mono text-sm">{f}</li>
            ))}
          </ul>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel}>Cancel</Button>
          <Button variant="destructive" onClick={onConfirm}>Delete</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
// Search input

export function SearchInput({
  value,
  onChange,
  placeholder,
}: {
  value: string
  onChange: (v: string) => void
  placeholder: string
}) {
  return (
    <div className="relative">
      <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" />
      <Input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="pl-8"
      />
    </div>
  )
}
