import { usePage, router } from "@inertiajs/react"
import { useMemo, useCallback } from "react"

import { urlSafeEncode, urlSafeDecode } from "@/lib/url-codec"
import type { QueryBuilderState } from "@/types"

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

  const setState = useCallback((newState: QueryBuilderState) => {
    updateQueryStateInUrl(newState)
  }, [])

  return {
    state,
    hasQuery,
    setState,
  }
}
