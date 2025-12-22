import type { QueryResponse } from "@/types"

function formatCellValue(value: unknown): string {
  if (value === null || value === undefined) return ""
  if (typeof value === "string") return value
  if (typeof value === "number" || typeof value === "boolean") return String(value)
  return JSON.stringify(value)
}

export function ResultsTable({ data }: { data: QueryResponse }) {
  const { rows, total_count, truncated } = data.data

  if (rows.length === 0) {
    return (
      <div className="flex-1 rounded-lg bg-muted/30 flex items-center justify-center">
        <p className="text-xs text-muted-foreground">No results found</p>
      </div>
    )
  }

  const columns = Object.keys(rows[0])

  return (
    <div className="flex-1 flex flex-col overflow-hidden rounded-lg border">
      <div className="overflow-auto flex-1">
        <table className="w-full text-xs">
          <thead className="bg-muted/50 sticky top-0">
            <tr>
              {columns.map((col) => (
                <th
                  key={col}
                  className="px-3 py-2 text-left font-medium text-muted-foreground"
                >
                  {col}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i} className="border-t hover:bg-muted/30">
                {columns.map((col) => (
                  <td key={col} className="px-3 py-2 truncate max-w-[300px]">
                    {formatCellValue(row[col])}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="px-3 py-2 border-t bg-muted/30 text-xs text-muted-foreground">
        {total_count} rows{truncated && " (truncated)"} &middot;{" "}
        {data.metadata.query_time_ms}ms
      </div>
    </div>
  )
}
