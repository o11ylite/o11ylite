import { Link } from "@inertiajs/react"
import { BookText, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"

export function NotebookEmpty() {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed py-16">
      <BookText className="mb-4 text-muted-foreground" size={40} />
      <p className="mb-2 text-lg font-medium">No notebooks yet</p>
      <p className="mb-4 text-sm text-muted-foreground">
        Create your first notebook to save multi-query investigations.
      </p>
      <Button asChild>
        <Link href="/notebooks/new">
          <Plus className="mr-2" size={16} />
          New Notebook
        </Link>
      </Button>
    </div>
  )
}
