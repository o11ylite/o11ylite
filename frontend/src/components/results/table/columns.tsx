import type { ColumnDef } from "@tanstack/react-table"
import { Eye } from "lucide-react"

import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"

export type RowData = Record<string, unknown>

export function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) return ""
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

export function isErrorRow(row: RowData): boolean {
  const spanStatus = row["span.status_code"]
  const logSeverity = row["log.severity"]

  return (
    (typeof spanStatus === "string" && spanStatus.toLowerCase() === "error") ||
    (typeof logSeverity === "string" && logSeverity.toLowerCase() === "error")
  )
}

export function buildColumns(
  fields: string[],
  onViewDetail: (row: RowData) => void
): ColumnDef<RowData>[] {
  const actionsColumn: ColumnDef<RowData> = {
    id: "_actions",
    header: "",
    cell: ({ row }) => (
      // Using native button for minimal footprint in table cells.
      // shadcn Button adds padding/height that disrupts row density.
      <Tooltip>
        <TooltipTrigger asChild>
          <button
            onClick={() => onViewDetail(row.original)}
            className="p-1 text-muted-foreground hover:text-foreground"
            aria-label="View details"
          >
            <Eye className="h-3.5 w-3.5" />
          </button>
        </TooltipTrigger>
        <TooltipContent>View details</TooltipContent>
      </Tooltip>
    ),
    enableHiding: false,
  }

  const fieldColumns = fields.map((field) => ({
    id: field,
    // Use accessorFn instead of accessorKey to avoid TanStack Table
    // interpreting dots as nested property paths (e.g., "scope.version")
    accessorFn: (row: RowData) => row[field],
    header: field,
    cell: ({ getValue }: { getValue: () => unknown }) => (
      <span className="truncate max-w-[300px] block">
        {formatCellValue(getValue())}
      </span>
    ),
  }))

  return [actionsColumn, ...fieldColumns]
}
