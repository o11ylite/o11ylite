import { useMemo, useState, useCallback, useRef } from "react"
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
import { createUnitFormatter, resolveChartUnit } from "@/lib/format-metric-value"
import { transformData, createTimestampFormatter } from "./utils"

// Minimum horizontal pixel distance before a pointer gesture becomes a drag selection.
// Prevents clicks/taps and small jitters from accidentally entering time-range selection mode.
const DRAG_THRESHOLD_PX = 8

interface TimeSeriesChartProps {
  data: TimeSeriesQueryResult
  title?: string
  connectNulls?: boolean
  // Use shorter legend labels (omit metric name) when charts are split by metric
  shortLegendLabels?: boolean
  // Metric name -> OTel unit string, from the query response
  units?: Record<string, string | null>
}

export function TimeSeriesChart({
  data,
  title,
  connectNulls = false,
  shortLegendLabels = false,
  units,
}: TimeSeriesChartProps) {
  const { chartData, seriesMeta } = useMemo(
    () => transformData(data, { shortLegendLabels }),
    [data, shortLegendLabels]
  )
  const showLegend = seriesMeta.length > 1
  const { setRange } = useTimeRange()

  // Drag-to-zoom selection state.
  //
  // Recharts' onMouseMove fires spuriously (via its throttled tooltip handler)
  // with wrong coordinates even when the pointer hasn't moved.  We gate the
  // selection behind a pixel-distance threshold checked via a native
  // onPointerMove on the container, which only fires on real movement.
  const [refAreaLeft, setRefAreaLeft] = useState<number | null>(null)
  const [refAreaRight, setRefAreaRight] = useState<number | null>(null)

  const dragRef = useRef<{ startX: number; label: number; confirmed: boolean } | null>(null)

  const handlePointerMove = useCallback((e: React.PointerEvent) => {
    const drag = dragRef.current
    if (!drag || drag.confirmed) return
    if (Math.abs(e.clientX - drag.startX) >= DRAG_THRESHOLD_PX) {
      drag.confirmed = true
      setRefAreaLeft(drag.label)
    }
  }, [])

  type RechartsMouseEvent = { activeLabel?: string } | null

  // Recharts passes (chartState, nativeEvent) -- we read both.
  const handleMouseDown = useCallback((e: RechartsMouseEvent, nativeEvent?: React.MouseEvent) => {
    if (!e?.activeLabel || !nativeEvent) return
    dragRef.current = { startX: nativeEvent.clientX, label: Number(e.activeLabel), confirmed: false }
  }, [])

  const handleMouseMove = useCallback((e: RechartsMouseEvent) => {
    if (e?.activeLabel && dragRef.current?.confirmed) {
      setRefAreaRight(Number(e.activeLabel))
    }
  }, [])

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
    dragRef.current = null
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

  // Unit-aware value formatting for Y-axis ticks and tooltip values
  const chartUnit = useMemo(() => resolveChartUnit(data.series, units), [data.series, units])
  const unitFormatter = useMemo(() => createUnitFormatter(chartUnit), [chartUnit])

  return (
    <div className="flex flex-col">
      {title && (
        <div className="px-3 py-2 text-sm font-medium text-muted-foreground">{title}</div>
      )}
      <ChartContainer
        config={chartConfig}
        className="h-[240px] w-full py-2 px-2 touch-pan-y"
        onPointerMove={handlePointerMove}
      >
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
          <YAxis
            tickLine={false}
            axisLine={false}
            tickMargin={8}
            width={60}
            tickFormatter={unitFormatter.formatTick}
          />
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
                formatter={(value, name, item) => (
                  <>
                    <div
                      className="h-2.5 w-2.5 shrink-0 rounded-[2px] border-(--color-border) bg-(--color-bg)"
                      style={{ "--color-bg": item.color, "--color-border": item.color } as React.CSSProperties}
                    />
                    <div className="flex flex-1 justify-between gap-4 leading-none items-center">
                      <span className="text-muted-foreground truncate">
                        {chartConfig[name as string]?.label || name}
                      </span>
                      <span className="text-foreground font-mono font-medium tabular-nums shrink-0">
                        {unitFormatter.format(value as number)}
                      </span>
                    </div>
                  </>
                )}
              />
            }
          />
          {showLegend && <ChartLegend content={<ChartLegendContent />} />}
          {seriesMeta.map((series) => (
            <Line
              key={series.key}
              dataKey={series.key}
              type="monotone"
              stroke={series.color}
              strokeWidth={2}
              dot={{ fill: series.color, strokeWidth: 0, r: 2 }}
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
