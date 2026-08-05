import { Eye, EyeOff, X } from "lucide-react"

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
import {
  AGGREGATIONS_BY_TYPE,
  AGGREGATION_LABELS,
  DEFAULT_AGGREGATION_BY_TYPE,
} from "@/lib/metric-query-helpers"

// ============================================================================
// Component
// ============================================================================

export function MetricRow({
  metric,
  metricType,
  hidden,
  onUpdate,
  onToggleHidden,
  onRemove,
}: {
  metric: MetricDefinition
  metricType: MetricType | null
  hidden: boolean
  onUpdate: (metric: MetricDefinition) => void
  onToggleHidden: () => void
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

  const handleAggChange = (agg: MetricAggregation | null) => {
    if (!agg) {
      return
    }
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
              <SelectValue>
                {(v) => (v ? AGGREGATION_LABELS[v as MetricAggregation] : "")}
              </SelectValue>
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

          {/* Show / Hide toggle */}
          <Button
            variant="ghost"
            size="icon-sm"
            onClick={onToggleHidden}
            className="text-muted-foreground hover:text-foreground"
            aria-label={
              hidden
                ? `Show ${metric.id} in chart`
                : `Hide ${metric.id} from chart`
            }
            title={hidden ? "Show in chart" : "Hide from chart"}
          >
            {hidden ? <EyeOff /> : <Eye />}
          </Button>

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
