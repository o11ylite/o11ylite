import { useQuery } from "@tanstack/react-query"

import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
  ComboboxTrigger,
} from "@/components/ui/combobox"
import { Button } from "@/components/ui/button"
import { MetricTypeBadge } from "@/components/metric-type-badge"
import type { MetricSummary } from "@/types"

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
// Component
// ============================================================================

export function MetricPicker({
  value,
  onSelect,
  placeholder = "Select metric...",
}: {
  value: string
  onSelect: (metric: MetricSummary) => void
  placeholder?: string
}) {
  const { data: metrics = [], isLoading } = useQuery({
    queryKey: ["metrics-list"],
    queryFn: fetchMetricsList,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })

  const selectedMetric = metrics.find((m) => m.name === value) ?? null

  return (
    <Combobox
      items={metrics}
      value={selectedMetric}
      onValueChange={(metric) => metric && onSelect(metric)}
      itemToStringValue={(metric) => metric.name}
    >
      <ComboboxTrigger
        render={
          <Button
            variant="outline"
            size="sm"
            className="min-w-[160px] justify-between font-normal"
          />
        }
      >
        {selectedMetric ? (
          <span className="flex items-center gap-1.5 truncate">
            <MetricTypeBadge type={selectedMetric.metric_type} />
            <span className="truncate">{selectedMetric.name}</span>
            {selectedMetric.unit && (
              <span className="text-muted-foreground text-xs">
                ({selectedMetric.unit})
              </span>
            )}
          </span>
        ) : (
          <span className="text-muted-foreground">{placeholder}</span>
        )}
      </ComboboxTrigger>
      <ComboboxContent className="w-max">
        <ComboboxInput showTrigger={false} placeholder="Search metrics..." />
        {isLoading ? (
          <div className="py-6 text-center text-sm text-muted-foreground">
            Loading metrics...
          </div>
        ) : (
          <>
            <ComboboxEmpty>No metric found.</ComboboxEmpty>
            <ComboboxList>
              {(metric: MetricSummary) => (
                <ComboboxItem key={metric.name} value={metric}>
                  <MetricTypeBadge type={metric.metric_type} />
                  <span className="min-w-0 flex-1 break-all">{metric.name}</span>
                  {metric.unit && (
                    <span className="text-muted-foreground text-xs shrink-0">
                      {metric.unit}
                    </span>
                  )}
                </ComboboxItem>
              )}
            </ComboboxList>
          </>
        )}
      </ComboboxContent>
    </Combobox>
  )
}
