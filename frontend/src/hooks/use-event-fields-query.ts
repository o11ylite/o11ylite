import { useQuery } from "@tanstack/react-query"
import type { Field } from "@/types"

async function fetchEventFields(): Promise<Field[]> {
  const response = await fetch("/api/events/fields")
  if (!response.ok) {
    throw new Error("Failed to fetch event fields")
  }
  return response.json() as Promise<Field[]>
}

export function useEventFieldsQuery() {
  const { data: fields, isLoading, error } = useQuery({
    queryKey: ["event-fields"],
    queryFn: fetchEventFields,
    staleTime: 5 * 60 * 1000,
  })

  return {
    fields: fields ?? [],
    isLoading,
    error,
  }
}
