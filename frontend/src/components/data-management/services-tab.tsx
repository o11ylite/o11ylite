import { useMemo, useState } from "react"

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatRelativeTime, useNow } from "@/lib/datetime"
import type { ManagedService } from "@/types"

import { SearchInput } from "./shared"

const LIVE_UPDATE_INTERVAL_MS = 30_000

export function ServicesTab({ services }: { services: ManagedService[] }) {
  const [search, setSearch] = useState("")
  const now = useNow(LIVE_UPDATE_INTERVAL_MS)

  const filtered = useMemo(
    () => services.filter((s) => s.name.toLowerCase().includes(search.toLowerCase())),
    [services, search],
  )

  return (
    <div className="space-y-3">
      <SearchInput value={search} onChange={setSearch} placeholder="Filter services..." />

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead className="w-28 text-right">Metrics</TableHead>
            <TableHead className="w-32 text-right">Event Fields</TableHead>
            <TableHead className="w-40">Last Seen</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {filtered.length === 0 ? (
            <TableRow>
              <TableCell colSpan={4} className="text-center text-muted-foreground py-8">
                {search ? "No services match your search." : "No services."}
              </TableCell>
            </TableRow>
          ) : (
            filtered.map((service) => (
              <TableRow key={service.name}>
                <TableCell className="font-mono text-sm">{service.name}</TableCell>
                <TableCell className="text-right tabular-nums text-sm text-muted-foreground">
                  {service.metric_count}
                </TableCell>
                <TableCell className="text-right tabular-nums text-sm text-muted-foreground">
                  {service.event_field_count}
                </TableCell>
                <TableCell className="text-sm text-muted-foreground">
                  {formatRelativeTime(service.last_seen_at, now)}
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
    </div>
  )
}
