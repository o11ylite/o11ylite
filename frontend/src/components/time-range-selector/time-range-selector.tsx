import { useState } from "react"
import { Clock, ChevronDown, Radio } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Label } from "@/components/ui/label"
import { Separator } from "@/components/ui/separator"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  useTimeRange,
  resolveTimeString,
  LIVE_REFRESH_INTERVAL,
} from "@/hooks/use-time-range"
import { TimeInput } from "./time-input"
import {
  isValidTimeInput,
  normalizeTimeInput,
  toDisplayValue,
} from "./utils"

const LIVE_PRESET = { from: "now-5m", to: "now", live: true, label: "Live 5 mins" }

const TIME_RANGE_PRESETS = [
  { from: "now-5m", to: "now", label: "Last 5 minutes" },
  { from: "now-15m", to: "now", label: "Last 15 minutes" },
  { from: "now-1h", to: "now", label: "Last 1 hour" },
  { from: "now-6h", to: "now", label: "Last 6 hours" },
  { from: "now-24h", to: "now", label: "Last 24 hours" },
  { from: "now-7d", to: "now", label: "Last 7 days" },
]

function getLabel(from: string, to: string, live: boolean): string {
  if (live && from === LIVE_PRESET.from && to === LIVE_PRESET.to) {
    return LIVE_PRESET.label
  }
  const preset = TIME_RANGE_PRESETS.find(
    (p) => p.from === from && p.to === to
  )
  if (preset) {
    return preset.label
  }
  return `${toDisplayValue(from)} - ${toDisplayValue(to)}`
}

export function TimeRangeSelector() {
  const { from, to, live, setRange } = useTimeRange()
  const label = getLabel(from, to, live)
  const [isOpen, setIsOpen] = useState(false)
  const [customFrom, setCustomFrom] = useState("")
  const [customTo, setCustomTo] = useState("")

  const handleOpenChange = (open: boolean) => {
    if (open) {
      setCustomFrom(toDisplayValue(from))
      setCustomTo(toDisplayValue(to))
    }
    setIsOpen(open)
  }

  const handlePresetClick = (preset: { from: string; to: string }) => {
    setRange({ from: preset.from, to: preset.to, live: false })
    setIsOpen(false)
  }

  const handleLiveClick = () => {
    setRange({ from: LIVE_PRESET.from, to: LIVE_PRESET.to, live: true })
    setIsOpen(false)
  }

  const handleApplyCustom = () => {
    if (!isValidTimeInput(customFrom) || !isValidTimeInput(customTo)) return

    const normalizedFrom = normalizeTimeInput(customFrom)
    const normalizedTo = normalizeTimeInput(customTo)

    try {
      const fromDate = resolveTimeString(normalizedFrom)
      const toDate = resolveTimeString(normalizedTo)
      if (fromDate >= toDate) return
    } catch {
      return
    }

    setRange({ from: normalizedFrom, to: normalizedTo, live: false })
    setIsOpen(false)
  }

  const isValidCustomRange = (() => {
    if (!isValidTimeInput(customFrom) || !isValidTimeInput(customTo)) return false

    try {
      const fromDate = resolveTimeString(normalizeTimeInput(customFrom))
      const toDate = resolveTimeString(normalizeTimeInput(customTo))
      return fromDate < toDate
    } catch {
      return false
    }
  })()

  const isPresetSelected = (preset: { from: string; to: string }) =>
    !live && from === preset.from && to === preset.to

  const isLiveSelected = live && from === LIVE_PRESET.from && to === LIVE_PRESET.to

  return (
    <Popover open={isOpen} onOpenChange={handleOpenChange}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm" className="min-w-[160px] justify-between gap-2">
          {live ? (
            <Radio className="size-4 text-green-500" />
          ) : (
            <Clock className="size-4" />
          )}
          <span className="truncate">{label}</span>
          <ChevronDown className="size-4 opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-80" align="end">
        <div className="space-y-4">
          <div>
            <Button
              variant={isLiveSelected ? "secondary" : "outline"}
              size="sm"
              className="w-full justify-start gap-2"
              onClick={handleLiveClick}
            >
              <Radio className="size-4" />
              {LIVE_PRESET.label}
              <span className="ml-auto text-xs text-muted-foreground">
                refreshes every {LIVE_REFRESH_INTERVAL / 1000}s
              </span>
            </Button>
          </div>

          <Separator />

          <div>
            <h4 className="mb-2 text-sm font-medium">Quick Select</h4>
            <div className="grid grid-cols-2 gap-1">
              {TIME_RANGE_PRESETS.map((option) => (
                <Button
                  key={option.from}
                  variant={isPresetSelected(option) ? "secondary" : "ghost"}
                  size="sm"
                  className="justify-start"
                  onClick={() => handlePresetClick(option)}
                >
                  {option.label}
                </Button>
              ))}
            </div>
          </div>

          <Separator />

          <div>
            <h4 className="mb-2 text-sm font-medium">Custom Range</h4>
            <p className="mb-3 text-xs text-muted-foreground">
              Type relative time (e.g., now-1h) or use the picker
            </p>
            <div className="space-y-3">
              <div className="space-y-1">
                <Label htmlFor="time-range-from" className="text-xs">
                  From
                </Label>
                <TimeInput
                  id="time-range-from"
                  value={customFrom}
                  onChange={setCustomFrom}
                />
              </div>
              <div className="space-y-1">
                <Label htmlFor="time-range-to" className="text-xs">
                  To
                </Label>
                <TimeInput
                  id="time-range-to"
                  value={customTo}
                  onChange={setCustomTo}
                />
              </div>
              <Button
                size="sm"
                className="w-full"
                disabled={!isValidCustomRange}
                onClick={handleApplyCustom}
              >
                Apply
              </Button>
            </div>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}
