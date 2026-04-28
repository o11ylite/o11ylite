import { Plus } from "lucide-react"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import type { MetricDefinition, MetricType, MetricSummary } from "@/types"
import { MetricRow } from "./metric-row"

// ============================================================================
// API
// ============================================================================

async function fetchMetricsList(): Promise<MetricSummary[]> {
  const response = await fetch("/api/metrics")
  if (!response.ok) {
    throw new Error("Failed to fetch metrics")
  }
  return response.json() as Promise<MetricSummary[]>
}

// ============================================================================
// Helpers
// ============================================================================

// Generate next available letter ID (A, B, C, ... Z)
function getNextMetricId(metrics: MetricDefinition[]): string {
  const usedIds = new Set(metrics.map((m) => m.id))
  for (let i = 0; i < 26; i++) {
    const id = String.fromCharCode(65 + i) // A=65, B=66, etc.
    if (!usedIds.has(id)) {
      return id
    }
  }
  return "Z" // fallback, shouldn't happen with <= 26 metrics
}

// ============================================================================
// Component
// ============================================================================

export function MetricsSection({
  metrics,
  hiddenIds,
  onMetricsChange,
  onHiddenChange,
}: {
  metrics: MetricDefinition[]
  hiddenIds: string[]
  onMetricsChange: (metrics: MetricDefinition[]) => void
  onHiddenChange: (hiddenIds: string[]) => void
}) {
  // Fetch metrics list to look up metric types
  const { data: metricsList = [] } = useQuery({
    queryKey: ["metrics-list"],
    queryFn: fetchMetricsList,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })

  // Build a lookup map for metric types
  const metricTypeMap = new Map<string, MetricType>(
    metricsList.map((m) => [m.name, m.metric_type])
  )

  const hiddenSet = new Set(hiddenIds)

  const toggleHidden = (id: string) => {
    onHiddenChange(
      hiddenSet.has(id) ? hiddenIds.filter((x) => x !== id) : [...hiddenIds, id],
    )
  }

  const addMetric = () => {
    const newMetric: MetricDefinition = {
      id: getNextMetricId(metrics),
      name: "",
      agg: "avg", // default, will be updated when metric is selected
    }
    onMetricsChange([...metrics, newMetric])
  }

  const updateMetric = (index: number, updated: MetricDefinition) => {
    const newMetrics = [...metrics]
    newMetrics[index] = updated
    onMetricsChange(newMetrics)
  }

  const removeMetric = (index: number) => {
    const removedId = metrics[index]?.id
    onMetricsChange(metrics.filter((_, i) => i !== index))
    // Also drop the hidden flag for the removed id, if any.
    if (removedId && hiddenSet.has(removedId)) {
      onHiddenChange(hiddenIds.filter((x) => x !== removedId))
    }
  }

  const canAddMore = metrics.length < 26

  return (
    <div className="bg-muted/50 rounded-lg p-2 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Metrics
        </span>
      </div>

      {metrics.length === 0 ? (
        <div className="text-center py-4">
          <p className="text-sm text-muted-foreground mb-2">
            Add a metric to start building your query
          </p>
          <Button variant="outline" size="sm" onClick={addMetric}>
            <Plus className="mr-1" />
            Add Metric
          </Button>
        </div>
      ) : (
        <>
          <div className="space-y-2">
            {metrics.map((metric, index) => (
              <MetricRow
                key={metric.id}
                metric={metric}
                metricType={metricTypeMap.get(metric.name) ?? null}
                hidden={hiddenSet.has(metric.id)}
                onUpdate={(updated) => updateMetric(index, updated)}
                onToggleHidden={() => toggleHidden(metric.id)}
                onRemove={() => removeMetric(index)}
              />
            ))}
          </div>

          {canAddMore && (
            <Button
              variant="ghost"
              size="sm"
              onClick={addMetric}
              className="text-muted-foreground hover:text-foreground"
            >
              <Plus className="mr-1" />
              Add Metric
            </Button>
          )}
        </>
      )}
    </div>
  )
}
