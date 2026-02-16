import type {
  NotebookCell,
  FilterExpr,
  SimpleFilter,
  QueryBuilderState,
} from "@/types"

export const DEFAULT_CELL_QUERY_STATE: QueryBuilderState = {
  mode: "events",
  filters: [],
  aggregations: [],
  groupBy: [],
  metrics: [],
  visualization: { type: "table" },
}

/** Convert a backend FilterExpr to a flat SimpleFilter array. */
function filtersFromExpr(expr: unknown): SimpleFilter[] {
  if (!expr || typeof expr !== "object") return []
  if ("and" in expr) return (expr as { and: SimpleFilter[] }).and
  return [expr as SimpleFilter]
}

/** Convert a SimpleFilter array to a backend FilterExpr. */
function buildFilterExpr(
  filters: SimpleFilter[],
): FilterExpr | undefined {
  const valid = filters.filter((f) => f.field && f.value !== "")
  if (valid.length === 0) return undefined
  if (valid.length === 1) return valid[0]
  return { and: valid }
}

/** Convert QueryBuilderState to the backend query payload shape. */
export function queryStateToPayload(
  state: QueryBuilderState,
): Record<string, unknown> {
  const full: Record<string, unknown> =
    state.mode === "events"
      ? {
          filter: buildFilterExpr(state.filters),
          aggregations: state.aggregations,
          group_by: state.groupBy,
          having: state.having,
          limit: state.limit,
          visualization: state.visualization,
        }
      : {
          filter: buildFilterExpr(state.filters),
          metrics: state.metrics,
          group_by: state.groupBy,
          having: state.having,
        }

  const isPresent = (v: unknown) =>
    v != null && !(Array.isArray(v) && v.length === 0)

  return Object.fromEntries(
    Object.entries(full).filter(
      ([k, v]) => k === "visualization" || isPresent(v),
    ),
  )
}

/** Derive QueryBuilderState from an existing NotebookCell (for editing). */
export function queryStateFromCell(cell: NotebookCell): QueryBuilderState {
  const q = cell.query
  return {
    mode: cell.queryMode,
    filters: filtersFromExpr(q.filter),
    aggregations:
      (q.aggregations as QueryBuilderState["aggregations"]) ?? [],
    groupBy: (q.groupBy as string[]) ?? [],
    having: q.having as QueryBuilderState["having"],
    limit: q.limit as number | undefined,
    metrics: (q.metrics as QueryBuilderState["metrics"]) ?? [],
    visualization:
      (q.visualization as QueryBuilderState["visualization"]) ?? {
        type: "table",
      },
  }
}
