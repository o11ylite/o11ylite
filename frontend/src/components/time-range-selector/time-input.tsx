import { useRef } from "react"
import { CalendarIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  formatDateTimeLocal,
  formatDisplayDateTime,
  parseDateTimeLocal,
  resolveInputToDate,
} from "./utils"

/**
 * Input component that accepts both relative time DSL (now-5m) and datetime values.
 * Text input with a calendar button that triggers native datetime picker.
 */
export function TimeInput({
  id,
  value,
  onChange,
}: {
  id: string
  value: string
  onChange: (value: string) => void
}) {
  const dateInputRef = useRef<HTMLInputElement>(null)

  // Resolve current value to datetime-local format for the picker
  const pickerValue = formatDateTimeLocal(resolveInputToDate(value))

  const handleCalendarClick = () => {
    dateInputRef.current?.showPicker()
  }

  const handleDateTimeChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.value) {
      // Convert datetime-local to display format
      const date = parseDateTimeLocal(e.target.value)
      if (date) {
        onChange(formatDisplayDateTime(date))
      }
    }
  }

  return (
    <div className="relative">
      <Input
        id={id}
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="now-1h or pick a date"
        className="h-8 pr-8 text-sm font-mono"
      />
      <input
        ref={dateInputRef}
        type="datetime-local"
        value={pickerValue}
        onChange={handleDateTimeChange}
        className="invisible absolute right-0 top-0 h-8 w-8"
        tabIndex={-1}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="absolute right-0 top-0 h-8 w-8 hover:bg-transparent"
        onClick={handleCalendarClick}
      >
        <CalendarIcon className="size-3.5 text-muted-foreground" />
        <span className="sr-only">Pick date and time</span>
      </Button>
    </div>
  )
}
