import { useMemo, useState, useCallback } from "react"
import { CartesianGrid, Line, LineChart, XAxis, YAxis, ReferenceArea } from "recharts"

import type { TimeSeriesQueryResult } from "@/types"
import { useTimeRange } from "@/hooks/use-time-range"
import {
  type ChartConfig,
  ChartContainer,
  ChartLegend,
  ChartLegendContent,
  ChartTooltip,
  ChartTooltipContent,
} from "@/components/ui/chart"
import { transformData, createTimestampFormatter } from "./utils"

interface TimeSeriesChartProps {
  data: TimeSeriesQueryResult
  title?: string
  connectNulls?: boolean
}

export function TimeSeriesChart({ data, title, connectNulls = false }: TimeSeriesChartProps) {
  const { chartData, seriesMeta } = useMemo(() => transformData(data), [data])
  const { setRange } = useTimeRange()

  // Drag selection state
  const [refAreaLeft, setRefAreaLeft] = useState<number | null>(null)
  const [refAreaRight, setRefAreaRight] = useState<number | null>(null)

  const handleMouseDown = useCallback((e: { activeLabel?: string } | null) => {
    if (e?.activeLabel) {
      setRefAreaLeft(Number(e.activeLabel))
      setRefAreaRight(null)
    }
  }, [])

  const handleMouseMove = useCallback((e: { activeLabel?: string } | null) => {
    if (refAreaLeft !== null && e?.activeLabel) {
      setRefAreaRight(Number(e.activeLabel))
    }
  }, [refAreaLeft])

  const handleMouseUp = useCallback(() => {
    if (refAreaLeft !== null && refAreaRight !== null) {
      const [start, end] = [refAreaLeft, refAreaRight].sort((a, b) => a - b)
      // Only update if selection spans a meaningful range (at least 1 second)
      // TODO: milliseconds support coming very soon!
      if (end - start >= 1000) {
        setRange({
          from: new Date(start).toISOString(),
          to: new Date(end).toISOString(),
        })
      }
    }
    setRefAreaLeft(null)
    setRefAreaRight(null)
  }, [refAreaLeft, refAreaRight, setRange])

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

  // Use API-provided time bounds for x-axis domain
  const xDomain: [number, number] = [data.start_ms, data.end_ms]
  const rangeMs = data.end_ms - data.start_ms
  const formatTimestamp = useMemo(() => createTimestampFormatter(rangeMs), [rangeMs])

  return (
    <div className="flex flex-col">
      {title && (
        <div className="px-3 py-2 text-sm font-medium text-muted-foreground">{title}</div>
      )}
      <ChartContainer config={chartConfig} className="h-[240px] w-full py-2 px-2">
        <LineChart
          accessibilityLayer
          data={chartData}
          onMouseDown={handleMouseDown}
          onMouseMove={handleMouseMove}
          onMouseUp={handleMouseUp}
          onMouseLeave={handleMouseUp}
        >
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
              dot={true}
              connectNulls={connectNulls}
            />
          ))}
          {refAreaLeft !== null && refAreaRight !== null && (
            <ReferenceArea
              x1={refAreaLeft}
              x2={refAreaRight}
              stroke="white"
              strokeOpacity={0.8}
              fill="white"
              fillOpacity={0.3}
            />
          )}
        </LineChart>
      </ChartContainer>
    </div>
  )
}
