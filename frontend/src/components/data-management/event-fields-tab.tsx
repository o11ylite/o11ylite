import { useState, useMemo } from "react"
import { router } from "@inertiajs/react"

import { FieldTypeBadge } from "@/components/field-type-badge"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import type { ManagedField } from "@/types"

import {
  BulkActionBar,
  CategoryBadge,
  DeleteConfirmDialog,
  SearchInput,
  StatusBadge,
} from "./shared"
import { useSelection } from "./use-selection"

const MAX_VISIBLE_SERVICES = 3

export function EventFieldsTab({ fields }: { fields: ManagedField[] }) {
  const [search, setSearch] = useState("")
  const [deleteFields, setDeleteFields] = useState<string[] | null>(null)

  const filtered = useMemo(
    () => fields.filter((f) => f.name.toLowerCase().includes(search.toLowerCase())),
    [fields, search]
  )

  const { selected, toggle, toggleAll, clear, allSelected, someSelected } =
    useSelection(filtered)

  const selectedNames = useMemo(
    () => filtered.filter((f) => selected.has(f.name)).map((f) => f.name),
    [filtered, selected]
  )

  const serviceList = (services: string[]) => {
    if (services.length === 0) return <span className="text-muted-foreground">--</span>
    const visible = services.slice(0, MAX_VISIBLE_SERVICES)
    const remaining = services.length - MAX_VISIBLE_SERVICES
    return (
      <span className="font-mono text-xs">
        {visible.join(", ")}
        {remaining > 0 && (
          <span className="text-muted-foreground"> +{remaining} more</span>
        )}
      </span>
    )
  }

  const handleBlock = () => {
    router.put("/system/data-management/event-fields/status", {
      fields: selectedNames,
      status: "blocked",
    }, { onSuccess: clear })
  }

  const handleActivate = () => {
    router.put("/system/data-management/event-fields/status", {
      fields: selectedNames,
      status: "active",
    }, { onSuccess: clear })
  }

  const handleDeleteConfirm = () => {
    if (!deleteFields) return
    router.delete("/system/data-management/event-fields", {
      data: { fields: deleteFields },
      onSuccess: () => {
        setDeleteFields(null)
        clear()
      },
    })
  }

  return (
    <div className="space-y-3">
      <SearchInput value={search} onChange={setSearch} placeholder="Filter event fields..." />

      {selected.size > 0 && (
        <BulkActionBar
          count={selected.size}
          onBlock={handleBlock}
          onActivate={handleActivate}
          onDelete={() => setDeleteFields(selectedNames)}
        />
      )}

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-10">
              <Checkbox
                checked={allSelected ? true : someSelected ? "indeterminate" : false}
                onCheckedChange={toggleAll}
              />
            </TableHead>
            <TableHead>Name</TableHead>
            <TableHead className="w-24">Category</TableHead>
            <TableHead className="w-20">Type</TableHead>
            <TableHead className="w-24">Status</TableHead>
            <TableHead className="w-48">Services</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-muted-foreground py-8">
                {search ? "No fields match your search." : "No event fields."}
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((field) => (
              <TableRow key={field.name}>
                <TableCell>
                  <Checkbox
                    checked={selected.has(field.name)}
                    onCheckedChange={() => toggle(field.name)}
                  />
                </TableCell>
                <TableCell className="font-mono text-sm">{field.name}</TableCell>
                <TableCell><CategoryBadge category={field.category} /></TableCell>
                <TableCell><FieldTypeBadge type={field.type} /></TableCell>
                <TableCell><StatusBadge status={field.status} /></TableCell>
                <TableCell>{serviceList(field.services)}</TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>

      <DeleteConfirmDialog
        open={deleteFields !== null}
        fields={deleteFields ?? []}
        onConfirm={handleDeleteConfirm}
        onCancel={() => setDeleteFields(null)}
      />
    </div>
  )
}
