import { useState } from "react"
import { MoreVertical } from "lucide-react"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { useMetricsListQuery } from "@/hooks/use-metrics-list-query"
import { useMetricQueryActions } from "@/hooks/use-metric-query-actions"
import { MetricTypeBadge } from "./metric-type-badge"

export function MetricSidePanel() {
  const [searchQuery, setSearchQuery] = useState("")
  const { metrics, isLoading, error } = useMetricsListQuery()
  const { addMetric } = useMetricQueryActions()

  const filteredMetrics = metrics.filter((metric) =>
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
          <div
            key={metric.name}
            className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-accent group"
          >
            <div className="flex items-center gap-2 flex-1 min-w-0">
              <MetricTypeBadge type={metric.metric_type} />
              <span className="text-xs text-muted-foreground group-hover:text-foreground truncate">
                {metric.name}
              </span>
              {metric.unit && (
                <span className="text-[10px] text-muted-foreground shrink-0">
                  ({metric.unit})
                </span>
              )}
            </div>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  className="h-6 w-6 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity"
                  aria-label={`Actions for ${metric.name}`}
                >
                  <MoreVertical className="h-3 w-3" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onClick={() => addMetric(metric.name)}>
                  Add this metric to query
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        ))}
      </div>
    </div>
  )
}
