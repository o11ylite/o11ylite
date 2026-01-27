import { useCallback } from "react"
import { useQueryState } from "./use-query-state"
import { useMetricsListQuery } from "./use-metrics-list-query"
import {
  getNextMetricId,
  DEFAULT_AGGREGATION_BY_TYPE,
} from "@/lib/metric-query-helpers"

export function useMetricQueryActions() {
  const { state, setState } = useQueryState()
  const { metrics } = useMetricsListQuery()

  const addMetric = useCallback((name: string) => {
    const metric = metrics.find((m) => m.name === name)
    const defaultAgg = metric ? DEFAULT_AGGREGATION_BY_TYPE[metric.metric_type] : "avg"
    const id = getNextMetricId(state.metrics)
    const newMetric = { id, name, agg: defaultAgg }
    setState({ ...state, metrics: [...state.metrics, newMetric] })
  }, [state, setState, metrics])

  return { addMetric }
}
