import { usePage, router } from "@inertiajs/react"
import { useMemo, useCallback } from "react"

import { urlSafeEncode, urlSafeDecode } from "@/lib/url-codec"
import { DISPLAYED_FIELDS_PARAM } from "@/hooks/use-displayed-fields"
import type { QueryBuilderState, Aggregation } from "@/types"

// ============================================================================
// Constants
// ============================================================================

const QUERY_PARAM = "q"

const DEFAULT_STATE: QueryBuilderState = {
  filters: [],
  aggregations: [],
  groupBy: [],
  visualization: { type: "table", limit: 100 },
}

// ============================================================================
// Result Column Shape
// ============================================================================

/**
 * Computes a fingerprint of the query's "result column shape".
 *
 * The columns returned by a query depend on aggregations and groupBy:
 * - Raw event query: returns all event fields
 * - Aggregation query: returns groupBy fields + aggregation result columns
 *
 * When this fingerprint changes, the user's displayed field selection becomes
 * invalid (the columns no longer exist), so we clear the `fields` URL param.
 *
 * Note: Filters and time range don't affect which columns are returned.
 */
function computeResultColumnFingerprint(state: QueryBuilderState): string {
  const aggKey = state.aggregations
    .map((a: Aggregation) => `${a.function}:${a.field ?? "*"}`)
    .sort()
    .join(",")
  const groupKey = [...state.groupBy].sort().join(",")
  return `agg=${aggKey}|group=${groupKey}`
}

// ============================================================================
// URL Helpers
// ============================================================================

function parseQueryStateFromUrl(url: string): QueryBuilderState | null {
  try {
    const urlObj = new URL(url, window.location.origin)
    const encoded = urlObj.searchParams.get(QUERY_PARAM)

    if (!encoded) {
      return null
    }

    return urlSafeDecode<QueryBuilderState>(encoded)
  } catch {
    return null
  }
}

function updateQueryStateInUrl(
  state: QueryBuilderState,
  clearDisplayedFields: boolean
): void {
  const encoded = urlSafeEncode(state)
  const params = new URLSearchParams(window.location.search)
  params.set(QUERY_PARAM, encoded)

  if (clearDisplayedFields) {
    params.delete(DISPLAYED_FIELDS_PARAM)
  }

  const newUrl = `${window.location.pathname}?${params.toString()}`

  router.push({
    url: newUrl,
    preserveState: true,
    preserveScroll: true,
  })
}

// ============================================================================
// Hook
// ============================================================================

/**
 * Hook for managing query builder state via URL query parameters.
 *
 * The URL is the source of truth. State is encoded using msgpack + base64.
 *
 * @example
 * ```tsx
 * const { state, setState, hasQuery } = useQueryState()
 *
 * // Check if there's a query to run
 * if (hasQuery) {
 *   // Trigger TanStack Query
 * }
 *
 * // Update state on submit (triggers URL update)
 * const handleSubmit = (newState) => setState(newState)
 * ```
 */
export function useQueryState() {
  const { url } = usePage()

  const state = useMemo(
    () => parseQueryStateFromUrl(url) ?? DEFAULT_STATE,
    [url]
  )

  const hasQuery = useMemo(() => {
    const urlObj = new URL(url, window.location.origin)
    return urlObj.searchParams.has(QUERY_PARAM)
  }, [url])

  const setState = useCallback(
    (newState: QueryBuilderState) => {
      // Clear displayed fields selection when result columns will change
      const currentFingerprint = computeResultColumnFingerprint(state)
      const newFingerprint = computeResultColumnFingerprint(newState)
      const resultColumnsChanged = currentFingerprint !== newFingerprint

      updateQueryStateInUrl(newState, resultColumnsChanged)
    },
    [state]
  )

  return {
    state,
    hasQuery,
    setState,
  }
}
