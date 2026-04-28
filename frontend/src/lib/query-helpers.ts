import type { FormDataConvertible } from "@inertiajs/core"
import type {
  FilterExpr,
  SimpleFilter,
  QueryBuilderState,
  QueryMode,
  Visualization,
} from "@/types"

/** Coerce a visualization to one valid for the given mode.
 *  Metrics mode only supports time_series; preserve extra fields (e.g. bucket_ms,
 *  overlay) when type already matches, otherwise replace. Events mode falls back
 *  to table when missing. */
function visualizationForMode(
  mode: QueryMode,
  viz: Visualization | undefined,
): Visualization {
  if (mode === "metrics") {
    return viz?.type === "time_series" ? viz : { type: "time_series" }
  }
  return viz ?? { type: "table" }
}

// ============================================================================
// Defaults
// ============================================================================

export const DEFAULT_QUERY_STATE: QueryBuilderState = {
  mode: "events",
  filters: [],
  aggregations: [],
  groupBy: [],
  metrics: [],
  formulas: [],
  visualization: { type: "table" },
}

// ============================================================================
// Filter conversions
// ============================================================================

/** Convert a backend FilterExpr to a flat SimpleFilter array. */
export function filtersFromExpr(expr: unknown): SimpleFilter[] {
  if (!expr || typeof expr !== "object") return []
  if ("and" in expr) return (expr as { and: SimpleFilter[] }).and
  return [expr as SimpleFilter]
}

/** Convert a SimpleFilter array to a backend FilterExpr. */
export function buildFilterExpr(
  filters: SimpleFilter[],
): FilterExpr | undefined {
  const valid = filters.filter((f) => f.field && f.value !== "")
  if (valid.length === 0) return undefined
  if (valid.length === 1) return valid[0]
  return { and: valid }
}

/** Wrap a filter expression with a service equality constraint. */
export function withServiceFilter(
  filter: FilterExpr | undefined,
  service: string | undefined,
): FilterExpr | undefined {
  if (!service) return filter
  const serviceFilter: SimpleFilter = { field: "service", op: "=", value: service }
  if (!filter) return serviceFilter
  if ("and" in filter) return { and: [...filter.and, serviceFilter] }
  return { and: [filter as SimpleFilter, serviceFilter] }
}

// ============================================================================
// State <-> Payload conversion
// ============================================================================

/** Convert QueryBuilderState to the backend query payload shape. */
export function queryStateToPayload(
  state: QueryBuilderState,
): Record<string, FormDataConvertible> {
  const filter = withServiceFilter(buildFilterExpr(state.filters), state.service)
  const visualization = visualizationForMode(state.mode, state.visualization)
  const full: Record<string, unknown> =
    state.mode === "events"
      ? {
          filter,
          aggregations: state.aggregations,
          group_by: state.groupBy,
          having: state.having,
          limit: state.limit,
          visualization,
        }
      : {
          filter,
          metrics: state.metrics,
          formulas: state.formulas,
          group_by: state.groupBy,
          having: state.having,
          visualization,
        }

  const isPresent = (v: unknown) =>
    v != null && !(Array.isArray(v) && v.length === 0)

  return Object.fromEntries(
    Object.entries(full).filter(
      ([k, v]) => k === "visualization" || isPresent(v),
    ),
  ) as Record<string, FormDataConvertible>
}

// ============================================================================
// Entity -> QueryBuilderState
// ============================================================================

/** Any persisted entity that carries a query (AlertRule, NotebookCell, etc.) */
interface QueryEntity {
  query_mode: QueryMode
  query: Record<string, unknown>
}

/** Derive QueryBuilderState from a persisted entity. */
export function queryStateFromEntity(entity: QueryEntity): QueryBuilderState {
  const q = entity.query
  const mode = entity.query_mode
  return {
    mode,
    filters: filtersFromExpr(q.filter),
    aggregations:
      (q.aggregations as QueryBuilderState["aggregations"]) ?? [],
    groupBy: (q.group_by as string[]) ?? [],
    having: q.having as QueryBuilderState["having"],
    limit: q.limit as number | undefined,
    metrics: (q.metrics as QueryBuilderState["metrics"]) ?? [],
    formulas: (q.formulas as QueryBuilderState["formulas"]) ?? [],
    visualization: visualizationForMode(
      mode,
      q.visualization as Visualization | undefined,
    ),
  }
}
