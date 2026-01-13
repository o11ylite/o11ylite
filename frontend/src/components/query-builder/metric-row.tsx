import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type {
  MetricDefinition,
  MetricAggregation,
  MetricType,
  MetricSummary,
} from "@/types"
import { MetricPicker } from "./metric-picker"

// ============================================================================
// Aggregation Options by Metric Type
// ============================================================================
// Based on backend query-validation.clj:
//   gauge: sum, avg, min, max, last
//   sum (counter): sum, rate
//   histogram: count, sum, avg, min, max

const AGGREGATIONS_BY_TYPE: Record<MetricType, MetricAggregation[]> = {
  gauge: ["sum", "avg", "min", "max", "last"],
  sum: ["sum", "rate"],
  histogram: ["count", "sum", "avg", "min", "max"],
}

const AGGREGATION_LABELS: Record<MetricAggregation, string> = {
  sum: "sum",
  avg: "avg",
  min: "min",
  max: "max",
  last: "last",
  rate: "rate",
  count: "count",
}

// Default aggregation when a metric is first selected
const DEFAULT_AGGREGATION_BY_TYPE: Record<MetricType, MetricAggregation> = {
  gauge: "avg",
  sum: "sum",
  histogram: "avg",
}

// ============================================================================
// Component
// ============================================================================

export function MetricRow({
  metric,
  metricType,
  onUpdate,
  onRemove,
}: {
  metric: MetricDefinition
  metricType: MetricType | null
  onUpdate: (metric: MetricDefinition) => void
  onRemove: () => void
}) {
  const availableAggregations = metricType
    ? AGGREGATIONS_BY_TYPE[metricType]
    : Object.keys(AGGREGATION_LABELS) as MetricAggregation[]

  const handleMetricSelect = (selected: MetricSummary) => {
    // When metric changes, update name and set default aggregation for the type
    const defaultAgg = DEFAULT_AGGREGATION_BY_TYPE[selected.metric_type]
    onUpdate({
      ...metric,
      name: selected.name,
      agg: defaultAgg,
    })
  }

  const handleAggChange = (agg: MetricAggregation) => {
    onUpdate({ ...metric, agg })
  }

  return (
    <div className="flex items-center gap-2">
      {/* Letter Badge */}
      <div className="flex items-center justify-center w-6 h-8 rounded bg-secondary text-xs font-semibold text-muted-foreground">
        {metric.id}
      </div>

      {/* Main Content */}
      <div className="flex-1 space-y-2">
        <div className="flex items-center gap-2">
          {/* Metric Picker */}
          <MetricPicker
            value={metric.name}
            onSelect={handleMetricSelect}
            placeholder="Select metric..."
          />

          {/* Aggregation Select */}
          <Select value={metric.agg} onValueChange={handleAggChange}>
            <SelectTrigger size="sm" className="w-auto min-w-[80px]">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {availableAggregations.map((agg) => (
                <SelectItem key={agg} value={agg}>
                  {AGGREGATION_LABELS[agg]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {/* Spacer */}
          <div className="flex-1" />

          {/* Remove Button */}
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={onRemove}
            className="text-muted-foreground hover:text-foreground"
          >
            <X />
          </Button>
        </div>

        {/* TODO: Per-metric filter (deferred) */}
        {/* <div className="text-xs text-muted-foreground">+ Add filter</div> */}
      </div>
    </div>
  )
}
