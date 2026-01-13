import { X } from "lucide-react"
import { useQuery } from "@tanstack/react-query"

import type { MetricDefinition, MetricDetail, Field } from "@/types"
import { FieldPicker } from "./field-picker"

// ============================================================================
// API
// ============================================================================

async function fetchMetricDetail(metricName: string): Promise<MetricDetail> {
  const response = await fetch(`/api/metrics/${encodeURIComponent(metricName)}`)
  if (!response.ok) {
    throw new Error("Failed to fetch metric details")
  }
  return response.json() as Promise<MetricDetail>
}

// ============================================================================
// Component
// ============================================================================

/**
 * GroupBy section for metrics mode.
 * Fetches attributes from the first selected metric and presents them as fields.
 */
export function MetricGroupBySection({
  metrics,
  groupBy,
  onChange,
}: {
  metrics: MetricDefinition[]
  groupBy: string[]
  onChange: (groupBy: string[]) => void
}) {
  // Find the first metric with a name selected
  const firstMetricName = metrics.find((m) => m.name)?.name

  // Fetch detailed metadata for that metric to get attributes
  const { data: metricDetail, isLoading } = useQuery({
    queryKey: ["metric-detail", firstMetricName],
    queryFn: () => fetchMetricDetail(firstMetricName!),
    enabled: !!firstMetricName,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })

  // Convert metric attributes to Field[] format
  // Metric attributes are stored as bare names (e.g., "host.name")
  // but query uses "attr." prefix (e.g., "attr.host.name")
  const fields: Field[] = (metricDetail?.attributes ?? []).map((attr) => ({
    name: `attr.${attr}`,
    type: "string" as const,
  }))

  const availableFields = fields.filter((f) => !groupBy.includes(f.name))

  const addGroupBy = (fieldName: string) => {
    if (!groupBy.includes(fieldName)) {
      onChange([...groupBy, fieldName])
    }
  }

  const removeGroupBy = (fieldName: string) => {
    onChange(groupBy.filter((f) => f !== fieldName))
  }

  const renderContent = () => {
    // Show hint when no metric is selected
    if (!firstMetricName) {
      return (
        <span className="text-xs text-muted-foreground italic">
          Select a metric to see available attributes
        </span>
      )
    }

    // Show loading state
    if (isLoading) {
      return (
        <span className="text-xs text-muted-foreground italic">
          Loading...
        </span>
      )
    }

    return (
      <>
        {groupBy.map((fieldName) => (
          <div
            key={fieldName}
            className="flex items-center gap-1 bg-secondary rounded-md px-2 py-1"
          >
            <span className="text-xs">{fieldName}</span>
            <button
              onClick={() => removeGroupBy(fieldName)}
              className="text-muted-foreground hover:text-foreground"
            >
              <X size={12} />
            </button>
          </div>
        ))}
        {availableFields.length > 0 && (
          <FieldPicker
            fields={availableFields}
            value=""
            onSelect={addGroupBy}
            placeholder="+ field"
          />
        )}
      </>
    )
  }

  return (
    <div className="bg-muted/50 rounded-lg p-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Group by
        </span>
        {renderContent()}
      </div>
    </div>
  )
}
