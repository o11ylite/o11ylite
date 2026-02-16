import { router } from "@inertiajs/react"
import { Plus } from "lucide-react"

import { Button } from "@/components/ui/button"

export function AddCellButton({ notebookId }: { notebookId: string }) {
  const handleAdd = () => {
    router.post(`/notebooks/${notebookId}/cells`, {
      query_mode: "events",
      query: JSON.stringify({ visualization: { type: "table" } }),
    })
  }

  return (
    <Button
      variant="outline"
      className="w-full border-dashed"
      onClick={handleAdd}
    >
      <Plus className="mr-2" size={16} />
      Add Cell
    </Button>
  )
}
