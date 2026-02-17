import { useEffect, useMemo, useRef, useState } from "react"
import {
  flexRender,
  getCoreRowModel,
  useReactTable,
} from "@tanstack/react-table"

import type { QueryResponse, TableQueryResult, SortConfig, ColumnMetadata } from "@/types"
import { useDisplayedFields } from "@/hooks/use-displayed-fields"

import { buildColumns, isErrorRow, type RowData } from "./columns"
import { DisplayedFieldsSelector } from "./displayed-fields-selector"
import { HeaderCell } from "./header-cell"
import { RowDetailDrawer } from "./row-detail-drawer"

function getRowKey(row: RowData): string {
  const ts = row.timestamp
  return String(ts)
}

function matchSortOrder(
  sort: SortConfig | undefined,
  fieldName: string,
  columnMeta: ColumnMetadata | undefined,
): "asc" | "desc" | null {
  if (!sort) return null
  if (columnMeta && "ref" in sort && sort.ref === columnMeta.ref) return sort.order
  if ("field" in sort && sort.field === fieldName) return sort.order
  return null
}

// Light green that works in both light and dark modes
const NEW_ROW_HIGHLIGHT = "rgba(74, 222, 128, 0.2)"

export function ResultsTable({
  data,
  live = false,
  canGoPrev = false,
  onPrevPage,
  onNextPage,
  sortable = false,
  sort,
  onSortChange,
  displayedFields,
  onDisplayedFieldsChange,
}: {
  data: QueryResponse
  live?: boolean
  canGoPrev?: boolean
  onPrevPage?: () => void
  onNextPage?: (cursor: string) => void
  sortable?: boolean
  sort?: SortConfig
  onSortChange?: (sort: SortConfig) => void
  displayedFields?: string[] | null
  onDisplayedFieldsChange?: (fields: string[] | null) => void
}) {
  const { rows, total_count, has_more, next_cursor, columns: responseColumns } = data.data as TableQueryResult

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
    displayedFields,
    onDisplayedFieldsChange,
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
    columnResizeMode: "onChange",
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
        <table
          className="text-xs border-collapse table-fixed"
          style={{ minWidth: "100%", width: table.getTotalSize() }}
        >
          <thead className="bg-muted/50 sticky top-0">
            {table.getHeaderGroups().map((headerGroup) => (
              <tr key={headerGroup.id} className="border-b">
                {headerGroup.headers.map((header) => {
                  const fieldName = header.column.id
                  const isSortable = sortable && fieldName !== "_actions"
                  const columnMeta = responseColumns?.find((c: ColumnMetadata) => c.key === fieldName)
                  const currentOrder = matchSortOrder(sort, fieldName, columnMeta)

                  const handleSort = () => {
                    if (!onSortChange) return
                    const nextOrder = currentOrder === "desc" ? "asc" : "desc"
                    // Emit ref-based sort for aggregation columns, field-based for raw columns
                    const newSort: SortConfig = columnMeta
                      ? { ref: columnMeta.ref, order: nextOrder }
                      : { field: fieldName, order: nextOrder }
                    onSortChange(newSort)
                  }

                  return (
                    <HeaderCell
                      key={header.id}
                      header={header}
                      sortable={isSortable}
                      currentOrder={currentOrder}
                      onSort={handleSort}
                    />
                  )
                })}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map((row) => {
              const rowKey = getRowKey(row.original)
              const isNew = live && rowKey && newRowKeys.has(rowKey)
              const isError = isErrorRow(row.original)

              return (
                <tr
                  key={row.id}
                  className={`border-b hover:bg-muted/50 ${isError ? "border-l-2 border-l-red-500" : ""}`}
                  style={
                    isNew
                      ? {
                          backgroundColor: NEW_ROW_HIGHLIGHT,
                          transition: "background-color 1s ease-out",
                        }
                      : undefined
                  }
                >
                  {row.getVisibleCells().map((cell) => (
                    <td
                      key={cell.id}
                      className="p-2 align-top overflow-hidden break-words"
                    >
                      {flexRender(
                        cell.column.columnDef.cell,
                        cell.getContext()
                      )}
                    </td>
                  ))}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className="px-3 py-2 border-t bg-muted/30 text-xs text-muted-foreground flex items-center justify-between">
        <span>
          {total_count} rows{has_more && "+"} &middot; {data.metadata.query_time_ms}ms
        </span>
        {onNextPage && (canGoPrev || has_more) && (
          <div className="flex gap-2">
            <button
              onClick={onPrevPage}
              disabled={!canGoPrev}
              className="px-2 py-1 rounded hover:bg-muted disabled:opacity-40 disabled:cursor-not-allowed"
            >
              ← Prev
            </button>
            <button
              onClick={() => next_cursor && onNextPage(next_cursor)}
              disabled={!has_more}
              className="px-2 py-1 rounded hover:bg-muted disabled:opacity-40 disabled:cursor-not-allowed"
            >
              Next →
            </button>
          </div>
        )}
      </div>
      <RowDetailDrawer row={detailRow} onClose={() => setDetailRow(null)} />
    </div>
  )
}
