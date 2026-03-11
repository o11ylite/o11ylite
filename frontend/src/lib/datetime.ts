import { useEffect, useState } from "react"

/**
 * Returns a `now` timestamp (epoch ms) that updates on a fixed interval,
 * suitable for driving relative-time displays without impure render calls.
 */
export function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), intervalMs)
    return () => clearInterval(id)
  }, [intervalMs])
  return now
}

/**
 * Format an epoch-ms timestamp as a locale string, or "Never" if null/0.
 */
export function formatDateTime(epochMs: number | null) {
  if (!epochMs) return "Never"
  return new Date(epochMs).toLocaleString()
}

/**
 * Format an epoch-ms timestamp relative to `now`, e.g. "5m ago" or "in 30s".
 */
export function formatRelativeTime(epochMs: number | null, now: number) {
  if (!epochMs) return "--"
  const diffMs = now - epochMs

  if (diffMs < 0) {
    const futureSec = Math.floor(-diffMs / 1000)
    if (futureSec < 60) return `in ${futureSec}s`
    const futureMin = Math.floor(futureSec / 60)
    if (futureMin < 60) return `in ${futureMin}m`
    const futureHr = Math.floor(futureMin / 60)
    return `in ${futureHr}h`
  }

  const sec = Math.floor(diffMs / 1000)
  if (sec < 60) return `${sec}s ago`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.floor(min / 60)
  return `${hr}h ago`
}

/**
 * Format a duration in milliseconds as a compact human-readable string,
 * e.g. 30000 -> "30s", 900000 -> "15m", 3600000 -> "1h".
 */
export function formatInterval(ms: number) {
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  const remainingMinutes = minutes % 60
  if (remainingMinutes === 0) return `${hours}h`
  return `${hours}h ${remainingMinutes}m`
}
