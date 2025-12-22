import { Loader2 } from "lucide-react"

export function ResultsLoading() {
  return (
    <div className="flex-1 rounded-lg bg-muted/30 flex items-center justify-center">
      <div className="text-center">
        <Loader2 size={24} className="mx-auto mb-3 animate-spin text-muted-foreground" />
        <p className="text-xs text-muted-foreground">Running query...</p>
      </div>
    </div>
  )
}
