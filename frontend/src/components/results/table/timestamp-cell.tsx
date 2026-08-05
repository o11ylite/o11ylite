import { useState } from "react"

import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip"

type TimestampFormat = "local" | "utc"

function formatTimestamp(value: unknown, format: TimestampFormat): string {
  if (value === null || value === undefined) return ""
  if (typeof value !== "string" && typeof value !== "number") {
    return JSON.stringify(value)
  }

  const date = new Date(value)
  if (isNaN(date.getTime())) return String(value)

  if (format === "utc") {
    return date.toISOString().replace("T", " ").replace("Z", " UTC")
  }

  // Local time with millisecond precision
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  const hours = String(date.getHours()).padStart(2, "0")
  const minutes = String(date.getMinutes()).padStart(2, "0")
  const seconds = String(date.getSeconds()).padStart(2, "0")
  const ms = String(date.getMilliseconds()).padStart(3, "0")

  return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}.${ms}`
}

export function TimestampCell({ value }: { value: unknown }) {
  const [format, setFormat] = useState<TimestampFormat>("local")

  const displayValue = formatTimestamp(value, format)
  const altFormat = format === "local" ? "utc" : "local"
  const altValue = formatTimestamp(value, altFormat)

  const handleClick = () => {
    setFormat(altFormat)
  }

  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span
            onClick={handleClick}
            className="font-mono cursor-pointer hover:underline select-text"
          />
        }
      >
        {displayValue}
      </TooltipTrigger>
      <TooltipContent>
        Click to show {altFormat === "utc" ? "UTC" : "local"} time: {altValue}
      </TooltipContent>
    </Tooltip>
  )
}
