import { isRelativeTime, resolveTimeString } from "@/hooks/use-time-range"

/**
 * Formats a Date to datetime-local input format (YYYY-MM-DDTHH:mm) in local timezone.
 */
export function formatDateTimeLocal(date: Date): string {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, "0")
  const day = String(date.getDate()).padStart(2, "0")
  const hours = String(date.getHours()).padStart(2, "0")
  const minutes = String(date.getMinutes()).padStart(2, "0")
  return `${year}-${month}-${day}T${hours}:${minutes}`
}

/**
 * Formats a Date to a human-friendly local datetime string.
 * Example: "Jan 15, 2024 14:30"
 */
export function formatDisplayDateTime(date: Date): string {
  return date.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  })
}

/**
 * Parses a datetime-local string (YYYY-MM-DDTHH:mm) as local time.
 */
export function parseDateTimeLocal(value: string): Date | null {
  const match = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/)
  if (!match) return null

  const [, year, month, day, hours, minutes] = match
  const date = new Date(
    parseInt(year),
    parseInt(month) - 1,
    parseInt(day),
    parseInt(hours),
    parseInt(minutes)
  )
  return isNaN(date.getTime()) ? null : date
}

const MONTH_MAP: Record<string, number> = {
  Jan: 0, Feb: 1, Mar: 2, Apr: 3, May: 4, Jun: 5,
  Jul: 6, Aug: 7, Sep: 8, Oct: 9, Nov: 10, Dec: 11,
}

/**
 * Parses a display format string (e.g., "Jan 15, 2024, 14:30") as local time.
 */
export function parseDisplayDateTime(value: string): Date | null {
  const match = value.match(/^([A-Z][a-z]{2})\s+(\d{1,2}),?\s+(\d{4}),?\s+(\d{2}):(\d{2})$/)
  if (!match) return null

  const [, monthStr, day, year, hours, minutes] = match
  const month = MONTH_MAP[monthStr]
  if (month === undefined) return null

  const date = new Date(
    parseInt(year),
    month,
    parseInt(day),
    parseInt(hours),
    parseInt(minutes)
  )
  return isNaN(date.getTime()) ? null : date
}

/**
 * Attempts to parse a datetime string in various formats.
 */
export function parseDateTime(value: string): Date | null {
  // Try display format first
  const displayDate = parseDisplayDateTime(value)
  if (displayDate) return displayDate

  // Try datetime-local format
  const localDate = parseDateTimeLocal(value)
  if (localDate) return localDate

  // Try ISO/general format
  const date = new Date(value)
  return isNaN(date.getTime()) ? null : date
}

/**
 * Validates a time input value.
 * Returns true if it's a valid relative time DSL or a parseable datetime string.
 */
export function isValidTimeInput(value: string): boolean {
  if (!value) return false
  if (isRelativeTime(value)) return true
  return parseDateTime(value) !== null
}

/**
 * Converts a time input value to a storable format (for URL).
 * - Relative times (now-5m) are kept as-is
 * - Datetime values are converted to ISO strings
 */
export function normalizeTimeInput(value: string): string {
  if (isRelativeTime(value)) return value

  const date = parseDateTime(value)
  if (date) {
    return date.toISOString()
  }

  return value
}

/**
 * Converts a stored time value (from URL) to a display value.
 * - Relative times (now-5m) are kept as-is
 * - ISO strings are converted to human-readable local format
 */
export function toDisplayValue(value: string): string {
  if (isRelativeTime(value)) return value

  try {
    const date = new Date(value)
    if (!isNaN(date.getTime())) {
      return formatDisplayDateTime(date)
    }
  } catch {
    // Fall through
  }
  return value
}

/**
 * Resolves a time input value to a Date for the datetime picker.
 */
export function resolveInputToDate(value: string): Date {
  if (isRelativeTime(value)) {
    try {
      return resolveTimeString(value)
    } catch {
      return new Date()
    }
  }

  const date = parseDateTime(value)
  return date ?? new Date()
}
