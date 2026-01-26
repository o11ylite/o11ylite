import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Input } from "@/components/ui/input"
import { type MetricSummary } from "@/types"
import { MetricTypeBadge } from "./metric-type-badge"

async function fetchMetricsList(): Promise<MetricSummary[]> {
  const response = await fetch("/api/metrics")
  if (!response.ok) {
    throw new Error("Failed to fetch metrics")
  }
  return response.json() as Promise<MetricSummary[]>
}

export function MetricSidePanel({
  onMetricClick,
}: {
  onMetricClick: (metricName: string) => void
}) {
  const [searchQuery, setSearchQuery] = useState("")

  const { data: metrics, isLoading, error } = useQuery({
    queryKey: ["metrics-list"],
    queryFn: fetchMetricsList,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })

  const filteredMetrics = metrics?.filter((metric) =>
    !searchQuery || metric.name.toLowerCase().includes(searchQuery.toLowerCase())
  )

  if (isLoading) {
    return (
      <div className="space-y-2">
        <div className="text-xs text-muted-foreground italic">Loading metrics...</div>
      </div>
    )
  }

  if (error instanceof Error) {
    return (
      <div className="space-y-2">
        <div className="text-xs text-destructive">{error.message}</div>
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <Input
        type="text"
        placeholder="Search metrics..."
        className="h-8 text-xs"
        value={searchQuery}
        onChange={(e) => setSearchQuery(e.target.value)}
      />
      <div className="space-y-0.5">
        {filteredMetrics?.map((metric) => (
          <button
            key={metric.name}
            onClick={() => onMetricClick(metric.name)}
            className="w-full flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-accent text-left"
          >
            <MetricTypeBadge type={metric.metric_type} />
            <span className="text-xs text-muted-foreground hover:text-foreground truncate">
              {metric.name}
            </span>
            {metric.unit && (
              <span className="text-[10px] text-muted-foreground shrink-0">
                ({metric.unit})
              </span>
            )}
          </button>
        ))}
      </div>
    </div>
  )
}
