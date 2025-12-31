import { usePage, router } from "@inertiajs/react"
import { useMemo, useCallback } from "react"
import type { VisibilityState } from "@tanstack/react-table"

// ============================================================================
// Constants
// ============================================================================

/** URL parameter name for displayed fields. Exported for use by useQueryState. */
export const DISPLAYED_FIELDS_PARAM = "fields"

const DEFAULT_VISIBLE_FIELDS = new Set([
  "timestamp",
  "service",
  "name",
  "log.body",
  "trace_id",
  "meta.signal_type",
])

// ============================================================================
// URL Helpers
// ============================================================================

function parseDisplayedFieldsFromUrl(url: string): string[] | null {
  try {
    const urlObj = new URL(url, window.location.origin)
    const encoded = urlObj.searchParams.get(DISPLAYED_FIELDS_PARAM)

    if (!encoded) {
      return null
    }

    return encoded.split(",").filter(Boolean)
  } catch {
    return null
  }
}

function updateDisplayedFieldsInUrl(fields: string[] | null): void {
  const params = new URLSearchParams(window.location.search)

  if (fields === null || fields.length === 0) {
    params.delete(DISPLAYED_FIELDS_PARAM)
  } else {
    params.set(DISPLAYED_FIELDS_PARAM, fields.join(","))
  }

  const newUrl = `${window.location.pathname}?${params.toString()}`

  router.push({
    url: newUrl,
    preserveState: true,
    preserveScroll: true,
  })
}

// ============================================================================
// Visibility Conversion
// ============================================================================

/**
 * Builds default visibility when no fields are explicitly selected.
 * - Aggregation queries (≤6 fields): show all
 * - Raw event queries: show only DEFAULT_VISIBLE_FIELDS
 */
function buildDefaultVisibility(availableFields: string[]): VisibilityState {
  if (availableFields.length <= 6) return {}

  const defaultFieldCount = availableFields.filter((f) =>
    DEFAULT_VISIBLE_FIELDS.has(f)
  ).length

  // Show all fields if fewer than 3 default fields are available
  if (defaultFieldCount < 3) return {}

  return Object.fromEntries(
    availableFields.map((field) => [field, DEFAULT_VISIBLE_FIELDS.has(field)])
  )
}

/**
 * Converts selected field names to TanStack Table VisibilityState.
 */
function toVisibilityState(
  selectedFields: string[],
  availableFields: string[]
): VisibilityState {
  const selectedSet = new Set(selectedFields)
  return Object.fromEntries(
    availableFields.map((field) => [field, selectedSet.has(field)])
  )
}

/**
 * Converts TanStack Table VisibilityState to selected field names.
 */
function fromVisibilityState(
  visibility: VisibilityState,
  availableFields: string[]
): string[] {
  return availableFields.filter((field) => visibility[field] !== false)
}

// ============================================================================
// Hook
// ============================================================================

interface UseDisplayedFieldsOptions {
  /** Available fields from the current query result */
  availableFields: string[]
}

/**
 * Hook for managing displayed fields state via URL query parameters.
 *
 * The URL is the source of truth. Fields are stored as comma-separated values.
 *
 * @example
 * ```tsx
 * const fields = rows.length > 0 ? Object.keys(rows[0]) : []
 * const { visibility, setVisibility } = useDisplayedFields({
 *   availableFields: fields,
 * })
 *
 * // Pass to TanStack Table
 * const table = useReactTable({
 *   state: { columnVisibility: visibility },
 *   onColumnVisibilityChange: setVisibility,
 * })
 * ```
 */
export function useDisplayedFields({ availableFields }: UseDisplayedFieldsOptions) {
  const { url } = usePage()

  const selectedFields = useMemo(
    () => parseDisplayedFieldsFromUrl(url),
    [url]
  )

  const visibility = useMemo((): VisibilityState => {
    if (selectedFields === null) {
      return buildDefaultVisibility(availableFields)
    }

    // Filter to only include fields that exist in current results
    const validFields = selectedFields.filter((f) => availableFields.includes(f))

    // If no valid fields remain, fall back to defaults
    if (validFields.length === 0) {
      return buildDefaultVisibility(availableFields)
    }

    return toVisibilityState(validFields, availableFields)
  }, [selectedFields, availableFields])

  const setVisibility = useCallback(
    (updater: VisibilityState | ((prev: VisibilityState) => VisibilityState)) => {
      const newVisibility =
        typeof updater === "function" ? updater(visibility) : updater

      const newFields = fromVisibilityState(newVisibility, availableFields)

      // Check if this matches the default visibility
      const defaultVis = buildDefaultVisibility(availableFields)
      const defaultFields = fromVisibilityState(defaultVis, availableFields)

      const isDefault =
        newFields.length === defaultFields.length &&
        newFields.every((f) => defaultFields.includes(f))

      // Clear URL param if matches default
      updateDisplayedFieldsInUrl(isDefault ? null : newFields)
    },
    [visibility, availableFields]
  )

  const reset = useCallback(() => {
    updateDisplayedFieldsInUrl(null)
  }, [])

  return {
    visibility,
    setVisibility,
    reset,
    /** Whether there's an explicit field selection (vs using defaults) */
    hasSelection: selectedFields !== null,
  }
}

/**
 * Clears the displayed fields URL parameter.
 * Call this when the query shape changes (e.g., aggregations added/removed).
 */
export function clearDisplayedFieldsParam(): void {
  updateDisplayedFieldsInUrl(null)
}
