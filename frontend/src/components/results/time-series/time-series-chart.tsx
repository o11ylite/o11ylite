import { useMemo } from "react"
import { CartesianGrid, Line, LineChart, XAxis, YAxis } from "recharts"

import type { TimeSeriesQueryResult } from "@/types"
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
}

export function TimeSeriesChart({ data, title }: TimeSeriesChartProps) {
  const { chartData, seriesMeta } = useMemo(() => transformData(data), [data])

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
              dot={true}
              connectNulls={false}
            />
          ))}
        </LineChart>
      </ChartContainer>
    </div>
  )
}
