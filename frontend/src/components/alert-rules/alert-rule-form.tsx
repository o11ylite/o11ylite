import { useState } from "react"
import { Link } from "@inertiajs/react"
import { Save } from "lucide-react"

import { QueryBuilder } from "@/components/query-builder"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Switch } from "@/components/ui/switch"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import {
  EVAL_WINDOW_PRESETS,
  EVAL_INTERVAL_PRESETS,
} from "@/components/alert-rules/eval-presets"
import type {
  AlertRule,
  Field,
  FilterExpr,
  Service,
  SimpleFilter,
  QueryBuilderState,
} from "@/types"

const DEFAULT_QUERY_STATE: QueryBuilderState = {
  mode: "events",
  filters: [],
  aggregations: [],
  groupBy: [],
  metrics: [],
  visualization: { type: "table" },
}

// The backend stores filters as a single filter expression (:filter),
// which is either a SimpleFilter or {and: SimpleFilter[]}.
// The QueryBuilder UI works with a flat SimpleFilter[].
// These two functions convert between the two representations.

function filtersFromExpr(expr: FilterExpr | undefined): SimpleFilter[] {
  if (!expr) return []
  if ("and" in expr) return expr.and as SimpleFilter[]
  return [expr as SimpleFilter]
}

function buildFilterExpr(filters: SimpleFilter[]): FilterExpr | undefined {
  const valid = filters.filter((f) => f.field && f.value !== "")
  if (valid.length === 0) return undefined
  if (valid.length === 1) return valid[0]
  return { and: valid }
}

function queryBuilderStateFromRule(rule: AlertRule): QueryBuilderState {
  const q = rule.query
  return {
    mode: rule.queryMode,
    filters: filtersFromExpr(q.filter as FilterExpr | undefined),
    aggregations: (q.aggregations as QueryBuilderState["aggregations"]) ?? [],
    groupBy: (q.groupBy as string[]) ?? [],
    having: q.having as QueryBuilderState["having"],
    limit: q.limit as number | undefined,
    metrics: (q.metrics as QueryBuilderState["metrics"]) ?? [],
    visualization: (q.visualization as QueryBuilderState["visualization"]) ?? {
      type: "table",
    },
  }
}

function queryStateToPayload(state: QueryBuilderState): Record<string, unknown> {
  const payload: Record<string, unknown> = {}
  const filterExpr = buildFilterExpr(state.filters)
  if (filterExpr) payload.filter = filterExpr
  if (state.aggregations.length > 0) payload.aggregations = state.aggregations
  if (state.groupBy.length > 0) payload.group_by = state.groupBy
  if (state.having) payload.having = state.having
  if (state.limit) payload.limit = state.limit
  if (state.metrics.length > 0) payload.metrics = state.metrics
  payload.visualization = state.visualization
  return payload
}

export function AlertRuleForm({
  alertRule,
  fields,
  services,
  onSubmit,
  submitting,
}: {
  alertRule: AlertRule | null
  fields: Field[]
  services: Service[]
  onSubmit: (data: Record<string, string | number | boolean | null>) => void
  submitting: boolean
}) {
  const isEditing = alertRule !== null

  const [name, setName] = useState(alertRule?.name ?? "")
  const [description, setDescription] = useState(alertRule?.description ?? "")
  const [enabled, setEnabled] = useState(alertRule?.enabled ?? true)
  const [evalWindowMs, setEvalWindowMs] = useState(
    String(alertRule?.evalWindowMs ?? 300000)
  )
  const [evalIntervalMs, setEvalIntervalMs] = useState(
    String(alertRule?.evalIntervalMs ?? 60000)
  )
  const [queryState, setQueryState] = useState<QueryBuilderState>(
    alertRule ? queryBuilderStateFromRule(alertRule) : DEFAULT_QUERY_STATE
  )

  const handleSubmit = () => {
    if (!name.trim()) return

    onSubmit({
      name: name.trim(),
      description: description.trim() || null,
      enabled,
      query_mode: queryState.mode,
      query: JSON.stringify(queryStateToPayload(queryState)),
      eval_window_ms: parseInt(evalWindowMs, 10),
      eval_interval_ms: parseInt(evalIntervalMs, 10),
    })
  }

  return (
    <div className="space-y-6">
      {/* Name + Description */}
      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-2">
          <Label htmlFor="name">Name</Label>
          <Input
            id="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="e.g., High error rate"
          />
        </div>
        <div className="space-y-2">
          <Label htmlFor="description">Description</Label>
          <Input
            id="description"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Optional description"
          />
        </div>
      </div>

      {/* Evaluation settings */}
      <div className="grid grid-cols-3 gap-4">
        <div className="space-y-2">
          <Label>Evaluation Window</Label>
          <Select value={evalWindowMs} onValueChange={setEvalWindowMs}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {EVAL_WINDOW_PRESETS.map((p) => (
                <SelectItem key={p.value} value={p.value}>
                  {p.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            How far back to look when evaluating
          </p>
        </div>
        <div className="space-y-2">
          <Label>Evaluation Interval</Label>
          <Select value={evalIntervalMs} onValueChange={setEvalIntervalMs}>
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {EVAL_INTERVAL_PRESETS.map((p) => (
                <SelectItem key={p.value} value={p.value}>
                  {p.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            How often to evaluate this rule
          </p>
        </div>
        <div className="flex items-center gap-3 pt-6">
          <Switch
            id="enabled"
            checked={enabled}
            onCheckedChange={setEnabled}
          />
          <Label htmlFor="enabled">Enabled</Label>
        </div>
      </div>

      {/* Query Builder */}
      <div className="space-y-2">
        <Label>Query</Label>
        <p className="text-xs text-muted-foreground">
          Define the query condition. The alert fires when the query returns
          non-empty results.
        </p>
        <div className="rounded-lg border p-4">
          <QueryBuilder
            fields={fields}
            services={services}
            initialState={queryState}
            onSubmit={setQueryState}
            onChange={setQueryState}
            autoSubmit={false}
            alertRuleMode
          />
        </div>
      </div>

      {/* Actions */}
      <div className="flex items-center gap-3">
        <Button onClick={handleSubmit} disabled={submitting || !name.trim()}>
          <Save className="mr-2" size={16} />
          {isEditing ? "Update Rule" : "Create Rule"}
        </Button>
        <Button variant="outline" asChild>
          <Link href="/alert-rules">Cancel</Link>
        </Button>
      </div>
    </div>
  )
}
