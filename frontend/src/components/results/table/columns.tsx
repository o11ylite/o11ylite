import type { ColumnDef } from "@tanstack/react-table"
import { Eye } from "lucide-react"

import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"
import { TimestampCell } from "./timestamp-cell"
import { TraceIdCell } from "./trace-id-cell"

export type RowData = Record<string, unknown>

export function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) return ""
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

export function isErrorRow(row: RowData): boolean {
  return row.error === true
}

// Default column widths by field type
const COLUMN_SIZES = {
  _actions: { size: 30, minSize: 30, maxSize: 30 },
  timestamp: { size: 200, minSize: 80 },
  trace_id: { size: 100, minSize: 50 },
  default: { size: 200, minSize: 50 },
} as const

function getColumnSize(field: string) {
  if (field in COLUMN_SIZES) {
    return COLUMN_SIZES[field as keyof typeof COLUMN_SIZES]
  }
  return COLUMN_SIZES.default
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
        <TooltipTrigger
          render={
            <button
              onClick={() => onViewDetail(row.original)}
              className="p-1 text-muted-foreground hover:text-foreground"
              aria-label="View details"
            />
          }
        >
          <Eye className="h-3.5 w-3.5" />
        </TooltipTrigger>
        <TooltipContent>View details</TooltipContent>
      </Tooltip>
    ),
    enableHiding: false,
    enableResizing: false,
    ...getColumnSize("_actions"),
  }

  const fieldColumns: ColumnDef<RowData>[] = fields.map((field) => {
    const baseColumn = {
      id: field,
      // Use accessorFn instead of accessorKey to avoid TanStack Table
      // interpreting dots as nested property paths (e.g., "scope.version")
      accessorFn: (row: RowData) => row[field],
      header: field,
      enableResizing: true,
      ...getColumnSize(field),
    }

    // Specialized cell renderers
    if (field === "timestamp") {
      return {
        ...baseColumn,
        cell: ({ getValue }: { getValue: () => unknown }) => (
          <TimestampCell value={getValue()} />
        ),
      }
    }

    if (field === "trace_id") {
      return {
        ...baseColumn,
        cell: ({ getValue }: { getValue: () => unknown }) => (
          <TraceIdCell value={getValue()} />
        ),
      }
    }

    // Default cell renderer
    return {
      ...baseColumn,
      cell: ({ getValue }: { getValue: () => unknown }) => (
        <span className="break-words">{formatCellValue(getValue())}</span>
      ),
    }
  })

  return [actionsColumn, ...fieldColumns]
}
