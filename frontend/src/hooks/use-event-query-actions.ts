import { useCallback } from "react"
import { useQueryState } from "./use-query-state"
import type { SimpleFilter } from "@/types"

export function useEventQueryActions() {
  const { state, setState } = useQueryState()

  const addExistsFilter = useCallback((fieldName: string) => {
    const newFilter: SimpleFilter = {
      field: fieldName,
      op: "exists",
      value: true,
    }
    setState({ ...state, filters: [...state.filters, newFilter] })
  }, [state, setState])

  return { addExistsFilter }
}
