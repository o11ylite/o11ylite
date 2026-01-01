import { usePage, router } from "@inertiajs/react"
import { useMemo, useCallback } from "react"

// ============================================================================
// Types
// ============================================================================

/**
 * Time range represented as DSL strings.
 * - Relative: "now", "now-5m", "now-1h", "now-7d"
 * - Absolute: ISO timestamp "2024-01-01T00:00:00Z"
 */
export interface TimeRange {
  from: string
  to: string
  live?: boolean
}

export interface ResolvedTimeRange {
  from: Date
  to: Date
}

// ============================================================================
// Constants
// ============================================================================

export const DEFAULT_TIME_RANGE: TimeRange = { from: "now-15m", to: "now" }

export const LIVE_REFRESH_INTERVAL = 3000

// ============================================================================
// Time string parsing & resolution
// ============================================================================

const RELATIVE_TIME_PATTERN = /^now(-(\d+)([mhd]))?$/

const UNIT_TO_MS: Record<string, number> = {
  m: 60_000,
  h: 3_600_000,
  d: 86_400_000,
}

/**
 * Checks if a time string is a relative time expression (now, now-5m, etc.)
 */
export function isRelativeTime(value: string): boolean {
  return RELATIVE_TIME_PATTERN.test(value)
}

/**
 * Resolves a time string to a Date.
 * - "now" -> current time
 * - "now-5m" -> 5 minutes ago
 * - "now-1h" -> 1 hour ago
 * - "now-7d" -> 7 days ago
 * - ISO string -> parsed as Date
 */
export function resolveTimeString(value: string, now: Date = new Date()): Date {
  if (value === "now") {
    return now
  }

  const match = value.match(RELATIVE_TIME_PATTERN)
  if (match) {
    const amount = match[2] ? parseInt(match[2], 10) : 0
    const unit = match[3]
    const ms = UNIT_TO_MS[unit] ?? 0
    return new Date(now.getTime() - amount * ms)
  }

  // Assume ISO timestamp
  const date = new Date(value)
  if (isNaN(date.getTime())) {
    throw new Error(`Invalid time string: ${value}`)
  }
  return date
}

/**
 * Resolves a TimeRange to actual Date objects.
 * Uses a single "now" reference for consistency between from and to.
 */
export function resolveTimeRange(range: TimeRange): ResolvedTimeRange {
  const now = new Date()
  return {
    from: resolveTimeString(range.from, now),
    to: resolveTimeString(range.to, now),
  }
}

// ============================================================================
// URL helpers
// ============================================================================

/**
 * Parses time range from a URL string.
 */
function parseTimeRangeFromUrl(url: string, defaultRange: TimeRange): TimeRange {
  try {
    const urlObj = new URL(url, window.location.origin)
    const from = urlObj.searchParams.get("from")
    const to = urlObj.searchParams.get("to")
    const live = urlObj.searchParams.get("live") === "true"

    if (!from || !to) {
      return defaultRange
    }

    // Validate
    resolveTimeString(from)
    resolveTimeString(to)
    return { from, to, live }
  } catch {
    return defaultRange
  }
}

/**
 * Updates URL params without triggering a full page reload.
 */
function updateUrlParams(range: TimeRange): void {
  const params = new URLSearchParams(window.location.search)
  params.set("from", range.from)
  params.set("to", range.to)
  if (range.live) {
    params.set("live", "true")
  } else {
    params.delete("live")
  }

  const newUrl = `${window.location.pathname}?${params.toString()}`

  // router.visit(replace: true) behave differently to this, not sure if it's a bug
  // we are not too sure if it should be replace or push, maybe it's case by case. let's see
  router.push({
    url: newUrl,
    preserveState: true,
    preserveScroll: true,
  })
}

// ============================================================================
// Hook
// ============================================================================

interface UseTimeRangeOptions {
  defaultRange?: TimeRange
}

/**
 * Hook for managing time range state via URL query parameters.
 *
 * The URL is the source of truth - no React state needed.
 * Uses Inertia's usePage() to reactively read the current URL.
 *
 * @example
 * ```tsx
 * const { from, to, setRange, resolve } = useTimeRange()
 *
 * // Set a preset
 * setRange({ from: "now-1h", to: "now" })
 *
 * // When making API calls, resolve to get current timestamps
 * const { from: fromDate, to: toDate } = resolve()
 * ```
 */
export function useTimeRange(options: UseTimeRangeOptions = {}) {
  const { defaultRange = DEFAULT_TIME_RANGE } = options

  // usePage() re-renders when URL changes via Inertia navigation
  const { url } = usePage()

  const timeRange = useMemo(
    () => parseTimeRangeFromUrl(url, defaultRange),
    [url, defaultRange]
  )

  const setRange = useCallback((range: TimeRange) => {
    updateUrlParams(range)
  }, [])

  const resolve = useCallback((): ResolvedTimeRange => {
    return resolveTimeRange(timeRange)
  }, [timeRange])

  return {
    from: timeRange.from,
    to: timeRange.to,
    live: timeRange.live ?? false,
    setRange,
    resolve,
  }
}
