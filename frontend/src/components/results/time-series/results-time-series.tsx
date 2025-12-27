import { useMemo } from "react"
import { CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts"

import type { QueryResponse, TimeSeriesQueryResult } from "@/types"
import {
  type ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart"

// Chart color palette - cycles through these for multiple series
const CHART_COLORS = [
  "var(--chart-1)",
  "var(--chart-2)",
  "var(--chart-3)",
  "var(--chart-4)",
  "var(--chart-5)",
]

// Builds a unique key for a series from its labels and name
function seriesKey(labels: Record<string, string>, name: string): string {
  const entries = Object.entries(labels).sort(([a], [b]) => a.localeCompare(b))
  const labelPart = entries.length === 0 ? "all" : entries.map(([k, v]) => `${k}:${v}`).join(",")
  return `${labelPart}::${name}`
}

// Builds a human-readable label for a series
function seriesLabel(labels: Record<string, string>, name: string): string {
  const labelValues = Object.values(labels)
  if (labelValues.length === 0) return name
  return `${labelValues.join(", ")} (${name})`
}

// Transforms backend series data into Recharts-compatible format
// Each data point becomes a row with timestamp and values for each series
function transformData(result: TimeSeriesQueryResult) {
  const { series } = result

  // Get all unique timestamps across all series
  const timestamps = new Set<number>()
  for (const s of series) {
    for (const point of s.data) {
      timestamps.add(point.timestamp)
    }
  }

  // Build series metadata
  const seriesMeta = series.map((s, idx) => ({
    key: seriesKey(s.labels, s.name),
    label: seriesLabel(s.labels, s.name),
    color: CHART_COLORS[idx % CHART_COLORS.length],
  }))

  // Build chart data - one row per timestamp with all series values
  const chartData = Array.from(timestamps)
    .sort((a, b) => a - b)
    .map((timestamp) => {
      const row: Record<string, number> = { timestamp }

      for (const s of series) {
        const point = s.data.find((p) => p.timestamp === timestamp)
        const key = seriesKey(s.labels, s.name)
        row[key] = point?.value ?? 0
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
function createTimestampFormatter(rangeMs: number): (timestamp: number) => string {
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

export function ResultsTimeSeries({ data }: { data: QueryResponse }) {
  const result = data.data as TimeSeriesQueryResult
  const { chartData, seriesMeta } = useMemo(() => transformData(result), [result])
  const uniqueSeriesCount = new Set(result.series.map((s) => Object.values(s.labels).join(","))).size

  // Build chart config from series metadata
  const chartConfig = useMemo(() => {
    const config: ChartConfig = {}
    for (const series of seriesMeta) {
      config[series.key] = {
        label: series.label,
        color: series.color,
      }
    }
    return config
  }, [seriesMeta])

  // Use API-provided time bounds for x-axis domain.
  // This ensures the chart shows the full query time range, including
  // leading/trailing gaps where no data exists.
  const xDomain: [number, number] = [result.start_ms, result.end_ms]
  const rangeMs = result.end_ms - result.start_ms
  const formatTimestamp = useMemo(() => createTimestampFormatter(rangeMs), [rangeMs])

  return (
    <div className="flex flex-col overflow-hidden rounded-lg border">
      <ChartContainer config={chartConfig} className="h-[280px] w-full py-2 px-2">
        <LineChart accessibilityLayer data={chartData}>
          <CartesianGrid vertical={false} />
          <XAxis
            dataKey="timestamp"
            type="number"
            domain={xDomain}
            tickLine={false}
            axisLine={false}
            tickMargin={8}
            tickFormatter={formatTimestamp}
          />
          <YAxis tickLine={false} axisLine={false} tickMargin={8} width={50} />
          <ChartTooltip
            content={
              <ChartTooltipContent
                labelFormatter={(_, payload) => {
                  const firstPayload = payload[0]?.payload as Record<string, number> | undefined
                  if (firstPayload?.timestamp) {
                    return new Date(firstPayload.timestamp).toLocaleString()
                  }
                  return ""
                }}
              />
            }
          />
          <ChartLegend content={<ChartLegendContent />} />
          {seriesMeta.map((series) => (
            <Line
              key={series.key}
              dataKey={series.key}
              type="monotone"
              stroke={series.color}
              strokeWidth={2}
              dot={false}
            />
          ))}
        </LineChart>
      </ChartContainer>
      <div className="px-3 py-2 border-t bg-muted/30 text-xs text-muted-foreground">
        {uniqueSeriesCount} groups &middot; {result.series.length} series &middot;{" "}
        {chartData.length} data points &middot; {data.metadata.query_time_ms}ms
      </div>
    </div>
  )
}
