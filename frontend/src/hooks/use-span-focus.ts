import { usePage, router } from "@inertiajs/react"
import { useCallback, useMemo } from "react"

// ============================================================================
// Constants
// ============================================================================

/** Query parameter holding the span that the trace page is focused on. */
export const SPAN_FOCUS_PARAM = "span"

// ============================================================================
// URL Helpers
// ============================================================================

function parsePageUrl(url: string): URL {
  return new URL(url, window.location.origin)
}

function pageUrlWithFocus(url: string, spanId: string | null): string {
  const parsed = parsePageUrl(url)
  if (spanId) {
    parsed.searchParams.set(SPAN_FOCUS_PARAM, spanId)
  } else {
    parsed.searchParams.delete(SPAN_FOCUS_PARAM)
  }
  return `${parsed.pathname}${parsed.search}${parsed.hash}`
}

// ============================================================================
// Hook
// ============================================================================

/**
 * Manages the focused (zoomed-in) span via the `span` query parameter.
 *
 * The URL is the source of truth, so reloading and sharing keeps the focus and
 * browser back/forward walks through the zoom levels.
 */
export function useSpanFocus() {
  const { url } = usePage()

  const focusedSpanId = useMemo(
    () => parsePageUrl(url).searchParams.get(SPAN_FOCUS_PARAM),
    [url]
  )

  const setFocusedSpanId = useCallback(
    (spanId: string | null) => {
      router.push({
        url: pageUrlWithFocus(url, spanId),
        preserveState: true,
        preserveScroll: true,
      })
    },
    [url]
  )

  const clearFocusHref = useMemo(() => pageUrlWithFocus(url, null), [url])

  return { focusedSpanId, setFocusedSpanId, clearFocusHref }
}
