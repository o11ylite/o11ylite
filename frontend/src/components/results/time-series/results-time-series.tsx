import { useMemo } from "react"

import type { QueryResponse, TimeSeriesQueryResult, TimeSeriesVisualization } from "@/types"
import { TimeSeriesChart } from "./time-series-chart"
import { TimeSeriesSettings } from "./time-series-settings"
import { groupByMetric } from "./utils"

export function ResultsTimeSeries({
  data,
  connectNulls = false,
  visualization,
  onVisualizationChange,
}: {
  data: QueryResponse
  connectNulls?: boolean
  visualization: TimeSeriesVisualization
  onVisualizationChange?: (viz: TimeSeriesVisualization) => void
}) {
  const result = data.data as TimeSeriesQueryResult
  const overlay = visualization.overlay ?? false
  const renderAs = visualization.render_as ?? "line"

  const metricGroups = useMemo(() => groupByMetric(result), [result])

  const uniqueGroupCount = new Set(
    result.series.map((s) => Object.values(s.labels).join(",")),
  ).size
  const totalDataPoints = result.series.reduce((acc, s) => acc + s.data.length, 0)

  return (
    <div className="flex flex-col overflow-hidden rounded-lg border">
      {onVisualizationChange && (
        <div className="flex justify-end px-2 pt-2">
          <TimeSeriesSettings
            overlay={overlay}
            onOverlayChange={(value) => onVisualizationChange({ ...visualization, overlay: value })}
            renderAs={renderAs}
            onRenderAsChange={(value) => onVisualizationChange({ ...visualization, render_as: value })}
          />
        </div>
      )}

      {overlay ? (
        <TimeSeriesChart
          data={result}
          connectNulls={connectNulls}
          units={result.units}
          renderAs={renderAs}
        />
      ) : (
        <div className="flex flex-col gap-4">
          {metricGroups.map(([name, subset]) => (
            <TimeSeriesChart
              key={name}
              data={subset}
              title={name}
              connectNulls={connectNulls}
              shortLegendLabels
              units={result.units}
              renderAs={renderAs}
            />
          ))}
        </div>
      )}

      <div className="px-3 py-2 border-t bg-muted/30 text-xs text-muted-foreground">
        {uniqueGroupCount} groups &middot; {result.series.length} series &middot;{" "}
        {totalDataPoints} data points &middot; {data.metadata.query_time_ms}ms
      </div>
    </div>
  )
}
