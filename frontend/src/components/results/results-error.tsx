import { AlertCircle } from "lucide-react"

export function ResultsError({ message }: { message: string }) {
  return (
    <div className="flex-1 rounded-lg bg-destructive/10 flex items-center justify-center">
      <div className="text-center">
        <div className="w-10 h-10 mx-auto mb-3 rounded-full bg-destructive/20 flex items-center justify-center">
          <AlertCircle size={18} className="text-destructive" />
        </div>
        <p className="text-xs text-destructive">{message}</p>
      </div>
    </div>
  )
}
