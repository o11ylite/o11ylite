import { useEffect, useMemo, useRef, useState } from "react"
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table"

import type { QueryResponse, TableQueryResult } from "@/types"
import { useDisplayedFields } from "@/hooks/use-displayed-fields"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"

import { buildColumns, isErrorRow, type RowData } from "./columns"
import { DisplayedFieldsSelector } from "./displayed-fields-selector"
import { RowDetailDrawer } from "./row-detail-drawer"

function getRowKey(row: RowData): string {
  const ts = row.timestamp
  return String(ts)
}

// Light green that works in both light and dark modes
const NEW_ROW_HIGHLIGHT = "rgba(74, 222, 128, 0.2)"

export function ResultsTable({
  data,
  live = false,
}: {
  data: QueryResponse
  live?: boolean
}) {
  const { rows, total_count, truncated } = data.data as TableQueryResult

  const [detailRow, setDetailRow] = useState<RowData | null>(null)

  // Track previous row keys to detect new rows in live mode
  const prevRowKeysRef = useRef<Set<string>>(new Set())
  const [newRowKeys, setNewRowKeys] = useState<Set<string>>(new Set())

  useEffect(() => {
    if (!live) {
      prevRowKeysRef.current = new Set()
      setNewRowKeys(new Set())
      return
    }

    const currentKeys = new Set(rows.map(getRowKey).filter(Boolean))
    const prevKeys = prevRowKeysRef.current

    const newKeys = new Set<string>()
    for (const key of currentKeys) {
      if (!prevKeys.has(key)) {
        newKeys.add(key)
      }
    }
    setNewRowKeys(newKeys)

    prevRowKeysRef.current = currentKeys
  }, [rows, live])

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

  const { visibility, setVisibility } = useDisplayedFields({
    availableFields: fields,
  })

  // TanStack Table's useReactTable returns functions that React Compiler cannot
  // safely memoize. The compiler automatically skips this component, and the
  // warning is informational only - no action needed.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data: rows,
    columns,
    getCoreRowModel: getCoreRowModel(),
    onColumnVisibilityChange: setVisibility,
    state: {
      columnVisibility: visibility,
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
        <DisplayedFieldsSelector table={table} />
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
            {table.getRowModel().rows.map((row) => {
              const rowKey = getRowKey(row.original)
              const isNew = live && rowKey && newRowKeys.has(rowKey)
              const isError = isErrorRow(row.original)

              return (
                <TableRow
                  key={row.id}
                  className={isError ? "border-l-2 border-l-red-500" : ""}
                  style={{
                    backgroundColor: isNew ? NEW_ROW_HIGHLIGHT : undefined,
                    transition: "background-color 1s ease-out",
                  }}
                >
                  {row.getVisibleCells().map((cell) => (
                    <TableCell key={cell.id}>
                      {flexRender(cell.column.columnDef.cell, cell.getContext())}
                    </TableCell>
                  ))}
                </TableRow>
              )
            })}
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
