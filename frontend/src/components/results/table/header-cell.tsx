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
  const sortProps = sortable
    ? { role: "button" as const, onClick: onSort, className: "cursor-pointer select-none hover:bg-muted" }
    : {}

  return (
    <th
      key={header.id}
      className="text-foreground text-left align-middle font-medium break-words relative group"
      style={{ width: header.getSize() }}
    >
      {/* Sort target covers the content area but not the resize handle */}
      <span
        {...sortProps}
        className={`flex items-center gap-1 px-2 py-2 ${sortProps.className ?? ""}`}
      >
        {header.isPlaceholder
          ? null
          : flexRender(header.column.columnDef.header, header.getContext())}
        {sortable && <SortIcon order={currentOrder} />}
      </span>
      {header.column.getCanResize() && (
        <div
          onMouseDown={header.getResizeHandler()}
          onTouchStart={header.getResizeHandler()}
          className="absolute right-0 top-0 h-full w-2 cursor-col-resize select-none touch-none bg-transparent group-hover:bg-border hover:bg-primary"
        />
      )}
    </th>
  )
}
