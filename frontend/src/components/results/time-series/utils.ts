import type { TimeSeriesQueryResult } from "@/types"

// Chart color palette - cycles through these for multiple series
export const CHART_COLORS = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
]

// Builds a unique key for a series from its labels and name
export function seriesKey(labels: Record<string, string>, name: string): string {
  const entries = Object.entries(labels).sort(([a], [b]) => a.localeCompare(b))
  const labelPart = entries.length === 0 ? "all" : entries.map(([k, v]) => `${k}:${v}`).join(",")
  return `${labelPart}::${name}`
}

// Builds a human-readable label for a series
export function seriesLabel(labels: Record<string, string>, name: string): string {
  const labelValues = Object.values(labels)
  if (labelValues.length === 0) return name
  return `${labelValues.join(", ")} (${name})`
}

// Series metadata for chart rendering
export interface SeriesMeta {
  key: string
  label: string
  color: string
}

// Transforms backend series data into Recharts-compatible format
// Each data point becomes a row with timestamp and values for each series
// Missing data points are set to null so charts can show gaps
export function transformData(result: TimeSeriesQueryResult): {
  chartData: Record<string, number | null>[]
  seriesMeta: SeriesMeta[]
} {
  const { series, start_ms, end_ms, bucket_ms } = result

  // Generate all bucket timestamps from start to end
  const timestamps: number[] = []
  for (let ts = start_ms; ts < end_ms; ts += bucket_ms) {
    timestamps.push(ts)
  }

  // Build series metadata
  const seriesMeta = series.map((s, idx) => ({
    key: seriesKey(s.labels, s.name),
    label: seriesLabel(s.labels, s.name),
    color: CHART_COLORS[idx % CHART_COLORS.length],
  }))

  // Index series data by timestamp for O(1) lookup
  const seriesDataMaps = series.map((s) => {
    const map = new Map<number, number>()
    for (const point of s.data) {
      map.set(point.timestamp, point.value)
    }
    return { series: s, map }
  })

  // Build chart data - one row per timestamp with all series values
  const chartData = timestamps.map((timestamp) => {
    const row: Record<string, number | null> = { timestamp }

    for (const { series: s, map } of seriesDataMaps) {
      const key = seriesKey(s.labels, s.name)
      const value = map.get(timestamp)
      row[key] = value !== undefined ? value : null
    }

    return row
  })

  return { chartData, seriesMeta }
}

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS

// Creates a timestamp formatter adaptive to the time range.
// - < 24 hours: show time only (HH:MM)
// - < 7 days: show short date + time (Mon 14:00)
// - >= 7 days: show date only (Jan 5)
export function createTimestampFormatter(rangeMs: number): (timestamp: number) => string {
  return (timestamp: number) => {
    const date = new Date(timestamp)
    if (rangeMs < DAY_MS) {
      return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
    }
    if (rangeMs < 7 * DAY_MS) {
      return date.toLocaleDateString([], { weekday: "short", hour: "2-digit", minute: "2-digit" })
    }
    return date.toLocaleDateString([], { month: "short", day: "numeric" })
  }
}

// Groups a TimeSeriesQueryResult by metric name
// Returns an array of [metricName, subsetResult] tuples
export function groupByMetric(result: TimeSeriesQueryResult): [string, TimeSeriesQueryResult][] {
  const { series, ...rest } = result

  // Get unique metric names
  const metricNames = [...new Set(series.map((s) => s.name))]

  return metricNames.map((name) => {
    const filteredSeries = series.filter((s) => s.name === name)
    return [name, { ...rest, series: filteredSeries }]
  })
}
