import { useState, useMemo } from "react"
import { router } from "@inertiajs/react"

import { Checkbox } from "@/components/ui/checkbox"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import type { ManagedMetricAttribute } from "@/types"

import {
  BulkActionBar,
  DeleteConfirmDialog,
  SearchInput,
  StatusBadge,
} from "./shared"
import { useSelection } from "./use-selection"

export function MetricAttributesTab({ attributes }: { attributes: ManagedMetricAttribute[] }) {
  const [search, setSearch] = useState("")
  const [deleteFields, setDeleteFields] = useState<string[] | null>(null)

  const filtered = useMemo(
    () => attributes.filter((f) => f.name.toLowerCase().includes(search.toLowerCase())),
    [attributes, search]
  )

  const { selected, toggle, toggleAll, clear, allSelected, someSelected } =
    useSelection(filtered)

  const selectedNames = useMemo(
    () => filtered.filter((f) => selected.has(f.name)).map((f) => f.name),
    [filtered, selected]
  )

  const handleBlock = () => {
    router.put("/system/data-management/metric-attributes/status", {
      fields: selectedNames,
      status: "blocked",
    }, { onSuccess: clear })
  }

  const handleActivate = () => {
    router.put("/system/data-management/metric-attributes/status", {
      fields: selectedNames,
      status: "active",
    }, { onSuccess: clear })
  }

  const handleDeleteConfirm = () => {
    if (!deleteFields) return
    router.delete("/system/data-management/metric-attributes", {
      data: { fields: deleteFields },
      onSuccess: () => {
        setDeleteFields(null)
        clear()
      },
    })
  }

  return (
    <div className="space-y-3">
      <SearchInput value={search} onChange={setSearch} placeholder="Filter metric attributes..." />

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
            <TableHead className="w-24">Status</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={3} className="text-center text-muted-foreground py-8">
                {search ? "No attributes match your search." : "No metric attributes."}
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((attr) => (
              <TableRow key={attr.name}>
                <TableCell>
                  <Checkbox
                    checked={selected.has(attr.name)}
                    onCheckedChange={() => toggle(attr.name)}
                  />
                </TableCell>
                <TableCell className="font-mono text-sm">{attr.name}</TableCell>
                <TableCell><StatusBadge status={attr.status} /></TableCell>
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
