// Convert an arbitrary error payload from a JSON API into a single
// human-readable string. The backend's `:error` value comes in two flavors:
//
//   1. A string for custom 400s (e.g. "invalid_aggregation")
//   2. A nested map for Malli-humanized validation failures
//      (e.g. {having: ["having ref must reference..."]} or
//      {metrics: {0: {id: ["missing required key"]}}})
//
// We flatten the latter into a path-prefixed message so the user sees
// "having: having ref must reference..." instead of "[object Object]".

type ErrorPayload = unknown

function firstMessage(value: ErrorPayload): string | null {
  if (typeof value === "string") return value
  if (Array.isArray(value)) {
    for (const item of value) {
      const m = firstMessage(item)
      if (m) return m
    }
    return null
  }
  if (value && typeof value === "object") {
    const entries = Object.entries(value as Record<string, ErrorPayload>)
    if (entries.length === 0) return null
    const [k, v] = entries[0]
    const inner = firstMessage(v)
    return inner ? `${k}: ${inner}` : null
  }
  return null
}

/**
 * Flatten a JSON API error response into a single string suitable for a
 * toast or error banner. Returns `fallback` if nothing useful is present.
 */
export function flattenApiError(payload: ErrorPayload, fallback: string): string {
  if (payload == null) return fallback
  return firstMessage(payload) ?? fallback
}
