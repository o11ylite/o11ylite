import { useQuery } from "@tanstack/react-query"
import type { Service } from "@/types"

async function fetchServices(): Promise<Service[]> {
  const response = await fetch("/api/services")
  if (!response.ok) {
    throw new Error("Failed to fetch services")
  }
  return response.json() as Promise<Service[]>
}

export function useServicesQuery() {
  const { data: services, isLoading, error } = useQuery({
    queryKey: ["services"],
    queryFn: fetchServices,
    staleTime: 5 * 60 * 1000,
  })

  return {
    services: services ?? [],
    isLoading,
    error,
  }
}
