import { router } from "@inertiajs/react"
import { Trash2 } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { AlertStateBadge } from "@/components/alert-rules/alert-state-badge"
import type { AlertInstance } from "@/types"

function formatLabels(labels: Record<string, unknown>): string {
  const entries = Object.entries(labels)
  if (entries.length === 0) return "(all results)"
  return entries
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${k}=${String(v)}`)
    .join(", ")
}

function dismiss(ruleId: string, instance: AlertInstance) {
  const label = formatLabels(instance.labels)
  if (!confirm(`Stop tracking instance "${label}"? It will re-track if seen again.`)) return
  router.post(`/alert-rules/${ruleId}/instances/dismiss`, {
    fingerprints: [instance.fingerprint],
  })
}

export function AlertInstances({
  ruleId,
  instances,
}: {
  ruleId: string
  instances: AlertInstance[]
}) {
  if (instances.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No tracked instances yet. Instances appear here once the rule has evaluated.
      </p>
    )
  }

  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[80px]">State</TableHead>
            <TableHead>Group</TableHead>
            <TableHead className="w-[80px]" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {instances.map((instance) => (
            <TableRow key={instance.fingerprint}>
              <TableCell>
                <AlertStateBadge state={instance.state} />
              </TableCell>
              <TableCell>
                <span className="font-mono text-sm">{formatLabels(instance.labels)}</span>
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label="Dismiss instance"
                  onClick={() => dismiss(ruleId, instance)}
                >
                  <Trash2 size={16} />
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
