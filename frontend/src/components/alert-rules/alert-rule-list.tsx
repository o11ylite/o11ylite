import { Link, router } from "@inertiajs/react"
import { Pencil, Trash2 } from "lucide-react"

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
import { formatMs } from "@/components/alert-rules/eval-presets"
import type { AlertRule } from "@/types"

function formatRelativeTime(epochMs: number | null): string {
  if (!epochMs) return "Never"
  const seconds = Math.floor((Date.now() - epochMs) / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  return `${hours}h ago`
}

function handleDelete(id: string, name: string) {
  if (!confirm(`Delete alert rule "${name}"?`)) return
  router.delete(`/alert-rules/${id}`)
}

export function AlertRuleList({ alertRules }: { alertRules: AlertRule[] }) {
  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-[80px]">State</TableHead>
            <TableHead>Name</TableHead>
            <TableHead className="w-[80px]">Mode</TableHead>
            <TableHead className="w-[100px]">Window</TableHead>
            <TableHead className="w-[100px]">Interval</TableHead>
            <TableHead className="w-[120px]">Last Evaluated</TableHead>
            <TableHead className="w-[80px]" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {alertRules.map((rule) => (
            <TableRow key={rule.id}>
              <TableCell>
                <AlertStateBadge state={rule.state} />
              </TableCell>
              <TableCell>
                <div>
                  <span className="font-medium">{rule.name}</span>
                  {rule.description && (
                    <p className="text-xs text-muted-foreground truncate max-w-[300px]">
                      {rule.description}
                    </p>
                  )}
                  {rule.lastEvalError && (
                    <p className="text-xs text-destructive truncate max-w-[300px]">
                      {rule.lastEvalError}
                    </p>
                  )}
                </div>
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {rule.queryMode}
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {formatMs(rule.evalWindowMs)}
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {formatMs(rule.evalIntervalMs)}
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {formatRelativeTime(rule.lastEvalAt)}
              </TableCell>
              <TableCell>
                <div className="flex items-center gap-1">
                  <Button variant="ghost" size="icon-sm" asChild>
                    <Link href={`/alert-rules/${rule.id}/edit`}>
                      <Pencil size={14} />
                    </Link>
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => handleDelete(rule.id, rule.name)}
                  >
                    <Trash2 size={14} />
                  </Button>
                </div>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
