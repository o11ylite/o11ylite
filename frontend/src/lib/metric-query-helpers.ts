import type { MetricDefinition, MetricAggregation, MetricType, HavingExpr, SimpleHaving } from "@/types"

export const AGGREGATIONS_BY_TYPE: Record<MetricType, MetricAggregation[]> = {
  gauge: ["sum", "avg", "min", "max", "last"],
  sum: ["sum", "rate"],
  histogram: ["count", "sum", "avg", "min", "max"],
}

export const AGGREGATION_LABELS: Record<MetricAggregation, string> = {
  sum: "sum",
  avg: "avg",
  min: "min",
  max: "max",
  last: "last",
  rate: "rate",
  count: "count",
}

export const DEFAULT_AGGREGATION_BY_TYPE: Record<MetricType, MetricAggregation> = {
  gauge: "avg",
  sum: "sum",
  histogram: "avg",
}

export function getNextMetricId(metrics: MetricDefinition[]): string {
  const usedIds = new Set(metrics.map((m) => m.id))
  for (let i = 0; i < 26; i++) {
    const id = String.fromCharCode(65 + i)
    if (!usedIds.has(id)) {
      return id
    }
  }
  return "Z"
}

// Metrics only support simple having (no and/or composition)
export function extractSimpleHaving(having: HavingExpr | undefined): SimpleHaving | undefined {
  if (!having) return undefined
  if ("and" in having || "or" in having) return undefined
  return having
}
