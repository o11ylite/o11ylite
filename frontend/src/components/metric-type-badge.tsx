import { cn } from "@/lib/utils"
import { type MetricType } from "@/types"

const METRIC_TYPE_COLORS: Record<MetricType, string> = {
  gauge: "bg-blue-500/20 text-blue-700 dark:text-blue-400",
  sum: "bg-green-500/20 text-green-700 dark:text-green-400",
  histogram: "bg-purple-500/20 text-purple-700 dark:text-purple-400",
}

const METRIC_TYPE_LABELS: Record<MetricType, string> = {
  gauge: "G",
  sum: "S",
  histogram: "H",
}

export function MetricTypeBadge({ type }: { type: MetricType }) {
  return (
    <span
      className={cn(
        "inline-flex items-center justify-center w-4 h-4 rounded text-[10px] font-medium",
        METRIC_TYPE_COLORS[type]
      )}
      title={type}
    >
      {METRIC_TYPE_LABELS[type]}
    </span>
  )
}
