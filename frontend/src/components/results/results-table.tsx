import { useMemo, useState, useEffect } from "react"
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type ColumnDef,
  type VisibilityState,
} from "@tanstack/react-table"
import { ChevronDown, Eye } from "lucide-react"

import type { QueryResponse } from "@/types"
import { Button } from "@/components/ui/button"
import {
  Drawer,
  DrawerContent,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

type RowData = Record<string, unknown>

const DEFAULT_VISIBLE_FIELDS = new Set([
  "timestamp",
  "service",
  "name",
  "log.body",
  "trace_id",
  "meta.signal_type",
])

function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) return ""
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

function buildColumns(
  fields: string[],
  onViewDetail: (row: RowData) => void
): ColumnDef<RowData>[] {
  const actionsColumn: ColumnDef<RowData> = {
    id: "_actions",
    header: "",
    cell: ({ row }) => (
      // Using native button for minimal footprint in table cells.
      // shadcn Button adds padding/height that disrupts row density.
      <button
        onClick={() => onViewDetail(row.original)}
        className="p-1 text-muted-foreground hover:text-foreground"
        aria-label="View details"
      >
        <Eye className="h-3.5 w-3.5" />
      </button>
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

function buildDefaultVisibility(fields: string[]): VisibilityState {
  // Show all fields if there are few (typical for aggregation queries)
  if (fields.length <= 6) return {}

  const defaultFieldCount = fields.filter((f) => DEFAULT_VISIBLE_FIELDS.has(f)).length

  // Show all fields if fewer than 3 default fields are available
  if (defaultFieldCount < 3) return {}

  return Object.fromEntries(
    fields.map((field) => [field, DEFAULT_VISIBLE_FIELDS.has(field)])
  )
}

function RowDetailDrawer({
  row,
  onClose,
}: {
  row: RowData | null
  onClose: () => void
}) {
  const nonNilEntries = row
    ? Object.entries(row).filter(([, v]) => v !== null && v !== undefined)
    : []

  return (
    <Drawer open={row !== null} onOpenChange={(open) => !open && onClose()}>
      <DrawerContent>
        <div className="mx-auto w-full max-w-2xl">
          <DrawerHeader>
            <DrawerTitle>Row Details</DrawerTitle>
          </DrawerHeader>
          <div className="p-4 max-h-[60vh] overflow-auto">
            <pre className="text-xs bg-muted/50 p-4 rounded-lg overflow-auto">
              {JSON.stringify(Object.fromEntries(nonNilEntries), null, 2)}
            </pre>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}

export function ResultsTable({ data }: { data: QueryResponse }) {
  const { rows, total_count, truncated } = data.data

  const [detailRow, setDetailRow] = useState<RowData | null>(null)

  const rawFields = rows.length > 0 ? Object.keys(rows[0]) : []
  // Ensure timestamp always rendered as the first field.
  const fields = rawFields.includes("timestamp")
    ? ["timestamp", ...rawFields.filter((f) => f !== "timestamp")]
    : rawFields
  const fieldsKey = fields.join(",")

  // Memoize columns based on field names, not the rows array reference
  const columns = useMemo(
    () => buildColumns(fields, setDetailRow),
    [fieldsKey] // eslint-disable-line react-hooks/exhaustive-deps
  )

  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>({})

  // Reset visibility when fields change (new query results)
  useEffect(() => {
    setColumnVisibility(buildDefaultVisibility(fields))
  }, [fieldsKey]) // eslint-disable-line react-hooks/exhaustive-deps

  // TanStack Table's useReactTable returns functions that React Compiler cannot
  // safely memoize. The compiler automatically skips this component, and the
  // warning is informational only - no action needed.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    onColumnVisibilityChange: setColumnVisibility,
    state: {
      columnVisibility,
    },
  })

  if (rows.length === 0) {
    return (
      <div className="flex-1 rounded-lg bg-muted/30 flex items-center justify-center">
        <p className="text-xs text-muted-foreground">No results found</p>
      </div>
    )
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden rounded-lg border">
      <div className="flex items-center justify-end px-3 py-2 border-b bg-muted/30">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="sm">
              Displayed fields <ChevronDown className="ml-1 h-4 w-4" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {table
              .getAllColumns()
              .filter((column) => column.getCanHide())
              .map((column) => (
                <DropdownMenuCheckboxItem
                  key={column.id}
                  checked={column.getIsVisible()}
                  onCheckedChange={(value) => column.toggleVisibility(!!value)}
                >
                  {column.id}
                </DropdownMenuCheckboxItem>
              ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      <div className="overflow-auto flex-1">
        <Table className="text-xs">
          <TableHeader className="bg-muted/50 sticky top-0">
            {table.getHeaderGroups().map((headerGroup) => (
              <TableRow key={headerGroup.id}>
                {headerGroup.headers.map((header) => (
                  <TableHead
                    key={header.id}
                    className="text-muted-foreground font-medium"
                  >
                    {header.isPlaceholder
                      ? null
                      : flexRender(
                        header.column.columnDef.header,
                        header.getContext()
                      )}
                  </TableHead>
                ))}
              </TableRow>
            ))}
          </TableHeader>
          <TableBody>
            {table.getRowModel().rows.map((row) => (
              <TableRow key={row.id}>
                {row.getVisibleCells().map((cell) => (
                  <TableCell key={cell.id}>
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
      <div className="px-3 py-2 border-t bg-muted/30 text-xs text-muted-foreground">
        {total_count} rows{truncated && " (truncated)"} &middot;{" "}
        {data.metadata.query_time_ms}ms
      </div>
      <RowDetailDrawer row={detailRow} onClose={() => setDetailRow(null)} />
    </div>
  )
}
