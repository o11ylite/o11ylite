import { useQuery } from "@tanstack/react-query"
import type { MetricSummary } from "@/types"

async function fetchMetricsList(): Promise<MetricSummary[]> {
  const response = await fetch("/api/metrics")
  if (!response.ok) {
    throw new Error("Failed to fetch metrics")
  }
  return response.json() as Promise<MetricSummary[]>
}

export function useMetricsListQuery() {
  const { data: metrics, isLoading, error } = useQuery({
    queryKey: ["metrics-list"],
    queryFn: fetchMetricsList,
    staleTime: 5 * 60 * 1000,
  })

  return {
    metrics: metrics ?? [],
    isLoading,
    error,
  }
}
