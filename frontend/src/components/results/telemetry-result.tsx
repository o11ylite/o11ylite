import { ResultsTable } from "./table"
import { ResultsTimeSeries } from "./time-series"
import { ResultsPlaceholder } from "./results-placeholder"
import { ResultsLoading } from "./results-loading"
import { ResultsError } from "./results-error"
import type {
  QueryMode,
  QueryResponse,
  Visualization,
  TimeSeriesVisualization,
} from "@/types"

/**
 * Renders the result of a telemetry query, handling loading/error/empty
 * states and choosing between time-series chart and table views based on
 * the query mode and visualization config.
 */
export function TelemetryResult({
  mode,
  hasQuery,
  data,
  isLoading,
  error,
  visualization,
  onVisualizationChange,
  live,
  canGoPrev,
  onPrevPage,
  onNextPage,
  sortable,
}: {
  mode: QueryMode
  hasQuery: boolean
  data: QueryResponse | undefined
  isLoading: boolean
  error: Error | null
  visualization: Visualization
  onVisualizationChange?: (viz: Visualization) => void
  live?: boolean
  canGoPrev?: boolean
  onPrevPage?: () => void
  onNextPage?: (cursor: string) => void
  sortable?: boolean
}) {
  if (!hasQuery) return <ResultsPlaceholder />
  if (isLoading) return <ResultsLoading />
  if (error instanceof Error) return <ResultsError message={error.message} />
  if (!data) return <ResultsPlaceholder />

  const isMetricsMode = mode === "metrics"
  const showTimeSeries = isMetricsMode || visualization.type === "time_series"

  if (showTimeSeries) {
    const tsViz: TimeSeriesVisualization =
      visualization.type === "time_series"
        ? visualization
        : { type: "time_series" }

    return (
      <ResultsTimeSeries
        data={data}
        connectNulls={isMetricsMode}
        visualization={tsViz}
        onVisualizationChange={onVisualizationChange}
      />
    )
  }

  return (
    <ResultsTable
      data={data}
      live={live}
      canGoPrev={canGoPrev}
      onPrevPage={onPrevPage}
      onNextPage={onNextPage}
      sortable={sortable}
      visualization={visualization}
      onVisualizationChange={onVisualizationChange}
    />
  )
}
