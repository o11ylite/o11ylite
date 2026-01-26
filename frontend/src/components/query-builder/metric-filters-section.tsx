import { Plus } from "lucide-react"
import { useQuery } from "@tanstack/react-query"

import { Button } from "@/components/ui/button"
import { type SimpleFilter, type MetricDefinition, type Field, type MetricDetail } from "@/types"
import { FilterChip } from "./filter-chip"

async function fetchMetricDetail(metricName: string): Promise<MetricDetail> {
  const response = await fetch(`/api/metrics/${encodeURIComponent(metricName)}`)
  if (!response.ok) {
    throw new Error("Failed to fetch metric details")
  }
  return response.json() as Promise<MetricDetail>
}

export function MetricFiltersSection({
  filters,
  onFiltersChange,
  metrics,
}: {
  filters: SimpleFilter[]
  onFiltersChange: (filters: SimpleFilter[]) => void
  metrics: MetricDefinition[]
}) {
  const firstMetricName = metrics.find((m) => m.name)?.name

  const { data: metricDetail, isLoading } = useQuery({
    queryKey: ["metric-detail", firstMetricName],
    queryFn: () => fetchMetricDetail(firstMetricName!),
    enabled: !!firstMetricName,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })

  const fields: Field[] = (metricDetail?.attributes ?? []).map((attr) => ({
    name: `attr.${attr}`,
    type: "string" as const,
  }))

  const addFilter = () => {
    onFiltersChange([...filters, { field: "", op: "=", value: "" }])
  }

  const updateFilter = (index: number, filter: SimpleFilter) => {
    const newFilters = [...filters]
    newFilters[index] = filter
    onFiltersChange(newFilters)
  }

  const removeFilter = (index: number) => {
    onFiltersChange(filters.filter((_: unknown, i: number) => i !== index))
  }

  const renderContent = () => {
    if (!firstMetricName) {
      return (
        <span className="text-xs text-muted-foreground italic">
          Select a metric to see available attributes
        </span>
      )
    }

    if (isLoading) {
      return (
        <span className="text-xs text-muted-foreground italic">
          Loading...
        </span>
      )
    }

    return (
      <>
        {filters.map((filter, i) => (
          <div key={i} className="flex items-center">
            {i > 0 && (
              <span className="text-[10px] text-muted-foreground px-1.5">
                and
              </span>
            )}
            <FilterChip
              filter={filter}
              fields={fields}
              onUpdate={(f: SimpleFilter) => updateFilter(i, f)}
              onRemove={() => removeFilter(i)}
            />
          </div>
        ))}
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={addFilter}
          className="text-muted-foreground hover:text-foreground"
        >
          <Plus />
        </Button>
      </>
    )
  }

  return (
    <div className="bg-muted/50 rounded-lg p-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Where
        </span>
        {renderContent()}
      </div>
    </div>
  )
}
