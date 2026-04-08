import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"

export function RemoveMetricsConfirmDialog({
  open,
  metrics,
  onConfirm,
  onCancel,
}: {
  open: boolean
  metrics: string[]
  onConfirm: () => void
  onCancel: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={(v) => !v && onCancel()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Remove {metrics.length} metric{metrics.length > 1 ? "s" : ""}?</DialogTitle>
          <DialogDescription>
            This will delete the metadata for {metrics.length > 1 ? "these metrics" : "this metric"}.
            Incoming data will re-create {metrics.length > 1 ? "them" : "it"} with fresh metadata,
            allowing immutable fields (unit, type, temporality) to be reset.
            The metric data itself is <strong>not</strong> deleted — it will be removed
            automatically by the retention mechanism.
          </DialogDescription>
        </DialogHeader>
        <div className="max-h-40 overflow-y-auto rounded-md border bg-muted/50 p-2">
          <ul className="space-y-1">
            {metrics.map((m) => (
              <li key={m} className="font-mono text-sm">{m}</li>
            ))}
          </ul>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={onCancel}>Cancel</Button>
          <Button variant="destructive" onClick={onConfirm}>Remove</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
