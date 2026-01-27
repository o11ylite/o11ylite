import { useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Check, ChevronsUpDown } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
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
  const [open, setOpen] = useState(false)

  const { data: metrics = [], isLoading } = useQuery({
    queryKey: ["metrics-list"],
    queryFn: fetchMetricsList,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })

  const selectedMetric = metrics.find((m) => m.name === value)

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          role="combobox"
          aria-expanded={open}
          className="min-w-[160px] justify-between font-normal"
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
          <ChevronsUpDown className="opacity-50 shrink-0" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-[480px] p-0" align="start">
        <Command>
          <CommandInput placeholder="Search metrics..." />
          <CommandList>
            {isLoading ? (
              <div className="py-6 text-center text-sm text-muted-foreground">
                Loading metrics...
              </div>
            ) : (
              <>
                <CommandEmpty>No metric found.</CommandEmpty>
                <CommandGroup>
                  {metrics.map((metric) => (
                    <CommandItem
                      key={metric.name}
                      value={metric.name}
                      onSelect={() => {
                        onSelect(metric)
                        setOpen(false)
                      }}
                    >
                      <MetricTypeBadge type={metric.metric_type} />
                      <span className="flex-1 break-all">{metric.name}</span>
                      {metric.unit && (
                        <span className="text-muted-foreground text-xs shrink-0">
                          {metric.unit}
                        </span>
                      )}
                      <Check
                        className={cn(
                          "ml-auto shrink-0",
                          value === metric.name ? "opacity-100" : "opacity-0"
                        )}
                      />
                    </CommandItem>
                  ))}
                </CommandGroup>
              </>
            )}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}


