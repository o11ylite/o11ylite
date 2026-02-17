import { useMemo, useCallback } from "react"
import type { VisibilityState } from "@tanstack/react-table"

// ============================================================================
// Constants
// ============================================================================

export const DEFAULT_VISIBLE_FIELDS = new Set([
  "timestamp",
  "service",
  "name",
  "log.body",
  "trace_id",
  "meta.signal_type",
])

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
  /** Explicit field selection (from visualization config, URL, etc.) */
  displayedFields?: string[] | null
  /** Called when the user changes which fields are visible */
  onDisplayedFieldsChange?: (fields: string[] | null) => void
}

/**
 * Hook for managing displayed fields as TanStack Table column visibility.
 *
 * Source-agnostic: accepts displayed fields and a change callback from the
 * caller. The caller decides where to store them (URL param, visualization
 * config, etc.).
 *
 * @example
 * ```tsx
 * const { visibility, setVisibility } = useDisplayedFields({
 *   availableFields: fields,
 *   displayedFields: state.visualization.displayed_fields,
 *   onDisplayedFieldsChange: (fields) => updateVisualization({ displayed_fields: fields }),
 * })
 *
 * const table = useReactTable({
 *   state: { columnVisibility: visibility },
 *   onColumnVisibilityChange: setVisibility,
 * })
 * ```
 */
export function useDisplayedFields({
  availableFields,
  displayedFields,
  onDisplayedFieldsChange,
}: UseDisplayedFieldsOptions) {
  const visibility = useMemo((): VisibilityState => {
    if (!displayedFields || displayedFields.length === 0) {
      return buildDefaultVisibility(availableFields)
    }

    // Filter to only include fields that exist in current results
    const validFields = displayedFields.filter((f) => availableFields.includes(f))

    // If no valid fields remain, fall back to defaults
    if (validFields.length === 0) {
      return buildDefaultVisibility(availableFields)
    }

    return toVisibilityState(validFields, availableFields)
  }, [displayedFields, availableFields])

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

      // Report null if selection matches default (allows caller to clear stored value)
      onDisplayedFieldsChange?.(isDefault ? null : newFields)
    },
    [visibility, availableFields, onDisplayedFieldsChange]
  )

  return {
    visibility,
    setVisibility,
    /** Whether there's an explicit field selection (vs using defaults) */
    hasSelection: displayedFields != null && displayedFields.length > 0,
  }
}
