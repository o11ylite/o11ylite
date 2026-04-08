import { useState, useMemo } from "react"
import { router } from "@inertiajs/react"
import { Trash2 } from "lucide-react"

import { MetricTypeBadge } from "@/components/metric-type-badge"
import { Checkbox } from "@/components/ui/checkbox"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import type { ManagedMetric } from "@/types"

import { RemoveMetricsConfirmDialog } from "./remove-metrics-confirm-dialog"
import { SearchInput } from "./shared"
import { useSelection } from "./use-selection"

export function MetricsListTab({ metrics }: { metrics: ManagedMetric[] }) {
  const [search, setSearch] = useState("")
  const [removeMetrics, setRemoveMetrics] = useState<string[] | null>(null)

  const filtered = useMemo(
    () => metrics.filter((m) => m.name.toLowerCase().includes(search.toLowerCase())),
    [metrics, search],
  )

  const { selected, toggle, toggleAll, clear, allSelected, someSelected } =
    useSelection(filtered)

  const selectedNames = useMemo(
    () => filtered.filter((m) => selected.has(m.name)).map((m) => m.name),
    [filtered, selected],
  )

  const handleRemoveConfirm = () => {
    if (!removeMetrics) return
    router.delete("/system/data-management/metrics", {
      data: { names: removeMetrics },
      onSuccess: () => {
        setRemoveMetrics(null)
        clear()
      },
    })
  }

  return (
    <div className="space-y-3">
      <SearchInput value={search} onChange={setSearch} placeholder="Filter metrics..." />

      {selected.size > 0 && (
        <div className="flex items-center gap-2 rounded-md border bg-muted/50 px-3 py-2">
          <span className="text-sm text-muted-foreground">
            {selected.size} selected
          </span>
          <div className="ml-auto flex gap-2">
            <Button variant="destructive" size="sm" onClick={() => setRemoveMetrics(selectedNames)}>
              <Trash2 className="mr-1" size={14} />
              Remove
            </Button>
          </div>
        </div>
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
            <TableHead className="w-20">Type</TableHead>
            <TableHead className="w-24">Unit</TableHead>
            <TableHead className="w-28">Attributes</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={5} className="text-center text-muted-foreground py-8">
                {search ? "No metrics match your search." : "No metrics."}
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((metric) => (
              <TableRow key={metric.name}>
                <TableCell>
                  <Checkbox
                    checked={selected.has(metric.name)}
                    onCheckedChange={() => toggle(metric.name)}
                  />
                </TableCell>
                <TableCell>
                  <div>
                    <span className="font-mono text-sm">{metric.name}</span>
                    {metric.description && (
                      <p className="text-xs text-muted-foreground mt-0.5">{metric.description}</p>
                    )}
                  </div>
                </TableCell>
                <TableCell><MetricTypeBadge type={metric.metric_type} /></TableCell>
                <TableCell className="text-sm text-muted-foreground">{metric.unit || "-"}</TableCell>
                <TableCell className="text-sm text-muted-foreground">{metric.attributes.length}</TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>

      <RemoveMetricsConfirmDialog
        open={removeMetrics !== null}
        metrics={removeMetrics ?? []}
        onConfirm={handleRemoveConfirm}
        onCancel={() => setRemoveMetrics(null)}
      />
    </div>
  )
}
