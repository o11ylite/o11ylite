import { useMemo, useState, useEffect } from "react"
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
  type VisibilityState,
} from "@tanstack/react-table"
import { ChevronDown } from "lucide-react"

import type { QueryResponse, TableQueryResult } from "@/types"
import { Button } from "@/components/ui/button"
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

import { buildColumns, buildDefaultVisibility, isErrorRow, type RowData } from "./columns"
import { RowDetailDrawer } from "./row-detail-drawer"

export function ResultsTable({ data }: { data: QueryResponse }) {
  const { rows, total_count, truncated } = data.data as TableQueryResult

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
              <TableRow
                key={row.id}
                className={isErrorRow(row.original) ? "border-l-2 border-l-red-500" : ""}
              >
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
