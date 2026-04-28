import { usePage, router } from "@inertiajs/react"
import { useMemo, useCallback } from "react"

import { urlSafeEncode, urlSafeDecode } from "@/lib/url-codec"
import type { QueryBuilderState, Aggregation, MetricDefinition, FormulaDefinition } from "@/types"

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
  formulas: [],
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
 * When this fingerprint changes, displayed_fields becomes invalid (the columns
 * no longer exist), so we clear it from the visualization config.
 *
 * Note: Filters and time range don't affect which columns are returned.
 */
function computeResultColumnFingerprint(state: QueryBuilderState): string {
  const modeKey = state.mode

  if (state.mode === "metrics") {
    const metricsKey = (state.metrics ?? [])
      .map((m: MetricDefinition) => `${m.id}:${m.name}:${m.agg}`)
      .sort()
      .join(",")
    const formulasKey = (state.formulas ?? [])
      .map((f: FormulaDefinition) => `${f.id}:${f.expr}:${f.name ?? ""}:${f.unit ?? ""}`)
      .sort()
      .join(",")
    const groupKey = [...state.groupBy].sort().join(",")
    return `mode=${modeKey}|metrics=${metricsKey}|formulas=${formulasKey}|group=${groupKey}`
  }

  // Events mode
  const aggKey = state.aggregations
    .map((a: Aggregation) => `${a.id}:${a.function}:${a.field ?? "*"}`)
    .sort()
    .join(",")
  const groupKey = [...state.groupBy].sort().join(",")
  return `mode=${modeKey}|agg=${aggKey}|group=${groupKey}`
}

/**
 * Strip displayed_fields from visualization config.
 * Returns a new state with displayed_fields removed if present.
 */
function clearDisplayedFields(state: QueryBuilderState): QueryBuilderState {
  if (state.visualization.type !== "table" || !("displayed_fields" in state.visualization)) {
    return state
  }
  const { displayed_fields, ...vizWithoutFields } = state.visualization
  void displayed_fields // Explicitly discard
  return { ...state, visualization: vizWithoutFields }
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

function updateQueryStateInUrl(state: QueryBuilderState): void {
  const encoded = urlSafeEncode(state)
  const params = new URLSearchParams(window.location.search)
  params.set(QUERY_PARAM, encoded)

  // Clean up legacy ?fields= param if present
  params.delete("fields")

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
      // Clear displayed_fields when result columns will change
      const currentFingerprint = computeResultColumnFingerprint(state)
      const newFingerprint = computeResultColumnFingerprint(newState)
      const resultColumnsChanged = currentFingerprint !== newFingerprint

      const effectiveState = resultColumnsChanged
        ? clearDisplayedFields(newState)
        : newState

      updateQueryStateInUrl(effectiveState)
    },
    [state]
  )

  return {
    state,
    hasQuery,
    setState,
  }
}
