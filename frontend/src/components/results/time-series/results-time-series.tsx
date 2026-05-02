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

  // Filter out series whose source-metric id is listed in
  // visualization.hidden_metrics. Series without an `id` (e.g. derived
  // / legacy results) are kept as-is. No useMemo: `result` is fresh on
  // every refetch (live tick), so memoising would just add bookkeeping
  // for no cache hits.
  const hidden = visualization.hidden_metrics ?? []
  const filteredResult: TimeSeriesQueryResult =
    hidden.length === 0
      ? result
      : {
          ...result,
          series: result.series.filter(
            (s) => !s.id || !hidden.includes(s.id),
          ),
        }

  const metricGroups = groupByMetric(filteredResult)

  const uniqueGroupCount = new Set(
    filteredResult.series.map((s) => Object.values(s.labels).join(",")),
  ).size
  const totalDataPoints = filteredResult.series.reduce(
    (acc, s) => acc + s.data.length,
    0,
  )

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
          data={filteredResult}
          connectNulls={connectNulls}
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
              renderAs={renderAs}
            />
          ))}
        </div>
      )}

      <div className="px-3 py-2 border-t bg-muted/30 text-xs text-muted-foreground">
        {uniqueGroupCount} groups &middot; {filteredResult.series.length} series &middot;{" "}
        {totalDataPoints} data points &middot; {data.metadata.query_time_ms}ms
      </div>
    </div>
  )
}
