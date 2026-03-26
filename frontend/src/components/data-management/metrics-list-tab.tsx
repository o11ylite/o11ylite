import { useState, useMemo } from "react"

import { MetricTypeBadge } from "@/components/metric-type-badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import type { ManagedMetric } from "@/types"

import { SearchInput } from "./shared"

export function MetricsListTab({ metrics }: { metrics: ManagedMetric[] }) {
  const [search, setSearch] = useState("")

  const filtered = useMemo(
    () => metrics.filter((m) => m.name.toLowerCase().includes(search.toLowerCase())),
    [metrics, search]
  )

  return (
    <div className="space-y-3">
      <SearchInput value={search} onChange={setSearch} placeholder="Filter metrics..." />

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead className="w-20">Type</TableHead>
            <TableHead className="w-24">Unit</TableHead>
            <TableHead className="w-28">Attributes</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={4} className="text-center text-muted-foreground py-8">
                {search ? "No metrics match your search." : "No metrics."}
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((metric) => (
              <TableRow key={metric.name}>
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
    </div>
  )
}
