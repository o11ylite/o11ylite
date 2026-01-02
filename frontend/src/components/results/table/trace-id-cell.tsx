import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"

export function TraceIdCell({ value }: { value: unknown }) {
  if (value === null || value === undefined || value === "") {
    return null
  }

  const traceId =
    typeof value === "string" || typeof value === "number"
      ? String(value)
      : JSON.stringify(value)
  // Show abbreviated version (first 8 chars)
  const abbreviated = traceId.length > 8 ? traceId.slice(0, 8) + "…" : traceId

  // TODO: Update href when trace detail page is implemented
  const href = `#trace/${traceId}`

  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <a
          href={href}
          onClick={(e) => e.preventDefault()}
          className="font-mono text-xs text-blue-600 dark:text-blue-400 hover:underline"
        >
          {abbreviated}
        </a>
      </TooltipTrigger>
      <TooltipContent>{traceId}</TooltipContent>
    </Tooltip>
  )
}
