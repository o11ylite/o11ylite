import { useEffect } from "react"
import { usePage } from "@inertiajs/react"
import { toast } from "sonner"

import ApplicationLayout from "@/components/layouts/application-layout"
import { NotebookCell } from "@/components/notebook/notebook-cell"
import { AddCellButton } from "@/components/notebook/add-cell-button"
import { useTimeRange } from "@/hooks/use-time-range"
import type { Notebook } from "@/types"

export default function NotebookShow() {
  const { notebook, errors } = usePage<{
    notebook: Notebook
    errors?: Record<string, string>
  }>().props

  useEffect(() => {
    if (errors && Object.keys(errors).length > 0) {
      const messages = Object.values(errors).join(", ")
      toast.error(`Failed to save cell: ${messages}`)
    }
  }, [errors])

  const cells = notebook.cells ?? []

  const { from, to } = useTimeRange()

  const breadcrumb = [
    { label: "Notebooks", href: "/notebooks" },
    { label: notebook.name },
  ]

  return (
    <ApplicationLayout title={breadcrumb} showTimeRange>
      <div className="space-y-6">
        {notebook.description && (
          <p className="text-sm text-muted-foreground">{notebook.description}</p>
        )}
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
