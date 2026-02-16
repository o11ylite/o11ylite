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
import type { Notebook } from "@/types"

function formatRelativeTime(epochMs: number): string {
  const seconds = Math.floor((Date.now() - epochMs) / 1000)
  if (seconds < 60) return `${seconds}s ago`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

function handleDelete(id: string, name: string) {
  if (!confirm(`Delete notebook "${name}"?`)) return
  router.delete(`/notebooks/${id}`)
}

export function NotebookList({ notebooks }: { notebooks: Notebook[] }) {
  return (
    <div className="rounded-md border">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead className="w-[80px]">Cells</TableHead>
            <TableHead className="w-[120px]">Updated</TableHead>
            <TableHead className="w-[80px]" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {notebooks.map((nb) => (
            <TableRow key={nb.id}>
              <TableCell>
                <Link
                  href={`/notebooks/${nb.id}`}
                  className="block hover:underline"
                >
                  <span className="font-medium">{nb.name}</span>
                  {nb.description && (
                    <p className="text-xs text-muted-foreground truncate max-w-[400px]">
                      {nb.description}
                    </p>
                  )}
                </Link>
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {nb.cellCount ?? 0}
              </TableCell>
              <TableCell className="text-muted-foreground text-sm">
                {formatRelativeTime(nb.updatedAt)}
              </TableCell>
              <TableCell>
                <div className="flex items-center gap-1">
                  <Button variant="ghost" size="icon-sm" asChild>
                    <Link href={`/notebooks/${nb.id}/edit`}>
                      <Pencil size={14} />
                    </Link>
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon-sm"
                    onClick={() => handleDelete(nb.id, nb.name)}
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
