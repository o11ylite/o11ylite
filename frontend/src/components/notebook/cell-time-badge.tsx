import { Pin, PinOff } from "lucide-react"
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"

import { Button } from "@/components/ui/button"

function formatPinnedLabel(from: string, to: string): string {
  const fromLabel = from.startsWith("now") ? from : "abs"
  const toLabel = to.startsWith("now") ? to : "abs"
  return `${fromLabel} \u2192 ${toLabel}`
}

export function CellTimeBadge({
  pinnedFrom,
  pinnedTo,
  onToggle,
}: {
  pinnedFrom: string | null
  pinnedTo: string | null
  onToggle: () => void
}) {
  const isPinned = pinnedFrom !== null && pinnedTo !== null

  if (isPinned) {
    return (
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="outline"
            size="sm"
            className="h-6 gap-1 px-2 text-xs"
            onClick={onToggle}
          >
            <PinOff size={12} />
            {formatPinnedLabel(pinnedFrom, pinnedTo)}
          </Button>
        </TooltipTrigger>
        <TooltipContent>Click to unpin and use global time</TooltipContent>
      </Tooltip>
    )
  }

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="h-6 gap-1 px-2 text-xs text-muted-foreground"
          onClick={onToggle}
        >
          <Pin size={12} />
          Pin time
        </Button>
      </TooltipTrigger>
      <TooltipContent>Pin current time range to this cell</TooltipContent>
    </Tooltip>
  )
}
