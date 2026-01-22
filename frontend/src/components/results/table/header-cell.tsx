import { flexRender, type Header } from "@tanstack/react-table"
import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react"

import type { RowData } from "./columns"

export type SortOrder = "asc" | "desc" | null

function SortIcon({ order }: { order: SortOrder }) {
  if (order === "asc") return <ArrowUp className="h-3 w-3" />
  if (order === "desc") return <ArrowDown className="h-3 w-3" />
  return <ArrowUpDown className="h-3 w-3 opacity-30" />
}

export function HeaderCell({
  header,
  sortable,
  currentOrder,
  onSort,
}: {
  header: Header<RowData, unknown>
  sortable: boolean
  currentOrder: SortOrder
  onSort?: () => void
}) {
  return (
    <th
      key={header.id}
      className={`text-foreground px-2 py-2 text-left align-middle font-medium break-words relative group ${sortable ? "cursor-pointer select-none hover:bg-muted" : ""}`}
      style={{ width: header.getSize() }}
      onClick={sortable ? onSort : undefined}
    >
      <span className="flex items-center gap-1">
        {header.isPlaceholder
          ? null
          : flexRender(header.column.columnDef.header, header.getContext())}
        {sortable && <SortIcon order={currentOrder} />}
      </span>
      {header.column.getCanResize() && (
        <div
          onMouseDown={header.getResizeHandler()}
          onTouchStart={header.getResizeHandler()}
          onClick={(e) => e.stopPropagation()}
          className="absolute right-0 top-0 h-full w-1 cursor-col-resize select-none touch-none bg-transparent group-hover:bg-border hover:bg-primary"
        />
      )}
    </th>
  )
}
