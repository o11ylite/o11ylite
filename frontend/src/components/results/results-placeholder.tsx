import { Sparkles } from "lucide-react"

export function ResultsPlaceholder() {
  return (
    <div className="flex-1 rounded-lg bg-muted/30 flex items-center justify-center">
      <div className="text-center">
        <div className="w-10 h-10 mx-auto mb-3 rounded-full bg-muted flex items-center justify-center">
          <Sparkles size={18} className="text-muted-foreground" />
        </div>
        <p className="text-xs text-muted-foreground">
          Run a query to explore your data
        </p>
      </div>
    </div>
  )
}
