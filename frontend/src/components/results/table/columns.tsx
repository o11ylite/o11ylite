import { type ColumnDef, type VisibilityState } from "@tanstack/react-table"
import { Eye } from "lucide-react"

import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"

export type RowData = Record<string, unknown>

const DEFAULT_VISIBLE_FIELDS = new Set([
  "timestamp",
  "service",
  "name",
  "log.body",
  "trace_id",
  "meta.signal_type",
])

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

export function buildDefaultVisibility(fields: string[]): VisibilityState {
  // Show all fields if there are few (typical for aggregation queries)
  if (fields.length <= 6) return {}

  const defaultFieldCount = fields.filter((f) => DEFAULT_VISIBLE_FIELDS.has(f)).length

  // Show all fields if fewer than 3 default fields are available
  if (defaultFieldCount < 3) return {}

  return Object.fromEntries(
    fields.map((field) => [field, DEFAULT_VISIBLE_FIELDS.has(field)])
  )
}
