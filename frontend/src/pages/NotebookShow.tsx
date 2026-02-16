import { usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { NotebookCell } from "@/components/notebook/notebook-cell"
import { AddCellButton } from "@/components/notebook/add-cell-button"
import { useTimeRange } from "@/hooks/use-time-range"
import type { Notebook } from "@/types"

export default function NotebookShow() {
  const { notebook } = usePage<{
    notebook: Notebook
  }>().props

  const cells = notebook.cells ?? []

  // Global time range from URL, defaults to notebook's saved values
  const { from, to } = useTimeRange({
    defaultRange: { from: notebook.globalFrom, to: notebook.globalTo },
  })

  const breadcrumb = [
    { label: "Notebooks", href: "/notebooks" },
    { label: notebook.name },
  ]

  return (
    <ApplicationLayout title={breadcrumb} showTimeRange>
      <div className="space-y-4">
        {cells.map((cell, index) => (
          <NotebookCell
            key={cell.id}
            cell={cell}
            notebookId={notebook.id}
            globalFrom={from}
            globalTo={to}
            isFirst={index === 0}
            isLast={index === cells.length - 1}
          />
        ))}
        <AddCellButton notebookId={notebook.id} />
      </div>
    </ApplicationLayout>
  )
}
