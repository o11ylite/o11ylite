import { usePage, router } from "@inertiajs/react"
import { useMemo, useCallback } from "react"

import { urlSafeEncode, urlSafeDecode } from "@/lib/url-codec"
import { DISPLAYED_FIELDS_PARAM } from "@/hooks/use-displayed-fields"
import type { QueryBuilderState, Aggregation, MetricDefinition } from "@/types"

// ============================================================================
// Constants
// ============================================================================

const QUERY_PARAM = "q"

const DEFAULT_STATE: QueryBuilderState = {
  mode: "events",
  filters: [],
  aggregations: [],
  groupBy: [],
  limit: 100,
  visualization: { type: "table" },
  metrics: [],
}

// ============================================================================
// Result Column Shape
// ============================================================================

/**
 * Computes a fingerprint of the query's "result column shape".
 *
 * The columns returned by a query depend on mode, aggregations, groupBy, and metrics:
 * - Events raw query: returns all event fields
 * - Events aggregation query: returns groupBy fields + aggregation result columns
 * - Metrics query: returns time-series with metric labels
 *
 * When this fingerprint changes, the user's displayed field selection becomes
 * invalid (the columns no longer exist), so we clear the `fields` URL param.
 *
 * Note: Filters and time range don't affect which columns are returned.
 */
function computeResultColumnFingerprint(state: QueryBuilderState): string {
  // Mode change always changes result shape
  const modeKey = state.mode

  if (state.mode === "metrics") {
    // For metrics, result shape depends on selected metrics and groupBy
    const metricsKey = (state.metrics ?? [])
      .map((m: MetricDefinition) => `${m.id}:${m.name}:${m.agg}`)
      .sort()
      .join(",")
    const groupKey = [...state.groupBy].sort().join(",")
    return `mode=${modeKey}|metrics=${metricsKey}|group=${groupKey}`
  }

  // Events mode
  const aggKey = state.aggregations
    .map((a: Aggregation) => `${a.id}:${a.function}:${a.field ?? "*"}`)
    .sort()
    .join(",")
  const groupKey = [...state.groupBy].sort().join(",")
  return `mode=${modeKey}|agg=${aggKey}|group=${groupKey}`
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
    if (!urlObj.searchParams.has(QUERY_PARAM)) {
      return false
    }
    // For metrics mode, require at least one metric with a name
    if (state.mode === "metrics") {
      return (state.metrics ?? []).some((m) => m.name)
    }
    // For events mode, having the param is enough
    return true
  }, [url, state.mode, state.metrics])

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
