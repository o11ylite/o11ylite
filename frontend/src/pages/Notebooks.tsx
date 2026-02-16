import { Link, usePage } from "@inertiajs/react"
import { Plus } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"
import { NotebookEmpty } from "@/components/notebook/notebook-empty"
import { NotebookList } from "@/components/notebook/notebook-list"
import type { Notebook } from "@/types"

export default function Notebooks() {
  const { notebooks } = usePage<{
    notebooks: Notebook[]
  }>().props

  if (notebooks.length === 0) {
    return (
      <ApplicationLayout title="Notebooks">
        <NotebookEmpty />
      </ApplicationLayout>
    )
  }

  return (
    <ApplicationLayout title="Notebooks">
      <div className="space-y-4">
        <Button asChild>
          <Link href="/notebooks/new">
            <Plus className="mr-2" size={16} />
            New Notebook
          </Link>
        </Button>
        <NotebookList notebooks={notebooks} />
      </div>
    </ApplicationLayout>
  )
}
