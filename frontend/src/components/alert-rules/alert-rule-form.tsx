import { useState } from "react"
import type { FormDataConvertible } from "@inertiajs/core"
import { Link } from "@inertiajs/react"
import { Save, AlertCircle } from "lucide-react"

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
import { queryStateToPayload } from "@/lib/query-helpers"
import type { AlertOn, QueryBuilderState } from "@/types"

interface AlertRuleFormPayload {
  name: string
  description: string | null
  enabled: boolean
  query_mode: string
  query: Record<string, FormDataConvertible>
  eval_window_ms: number
  eval_interval_ms: number
  alert_on: AlertOn
  alert_target: string | null
}

interface AlertRuleFormProps {
  initialValues: {
    name: string
    description: string
    enabled: boolean
    queryState: QueryBuilderState
    evalWindowMs: number
    evalIntervalMs: number
    alertOn: AlertOn
    alertTarget: string | null
  }
  errors?: Partial<Record<string, string>>
  submitting?: boolean
  submitLabel: string
  onSubmit: (data: AlertRuleFormPayload) => void
}

export function AlertRuleForm({
  initialValues,
  errors = {},
  submitting = false,
  submitLabel,
  onSubmit,
}: AlertRuleFormProps) {
  const [name, setName] = useState(initialValues.name)
  const [description, setDescription] = useState(initialValues.description)
  const [enabled, setEnabled] = useState(initialValues.enabled)
  const [evalWindowMs, setEvalWindowMs] = useState(initialValues.evalWindowMs)
  const [evalIntervalMs, setEvalIntervalMs] = useState(initialValues.evalIntervalMs)
  const [alertOn, setAlertOn] = useState<AlertOn>(initialValues.alertOn)
  const [alertTarget, setAlertTarget] = useState<string | null>(
    initialValues.alertTarget,
  )
  const [queryState, setQueryState] = useState(initialValues.queryState)

  const nameError = errors.name
  const queryError = errors.query
  const alertTargetError = errors.alert_target

  const isMetricsMode = queryState.mode === "metrics"
  const targetCandidates = isMetricsMode
    ? [
        ...queryState.metrics.map((m) => ({
          id: m.id,
          label: `${m.id}: ${m.agg}(${m.name || "?"})`,
        })),
        ...(queryState.formulas ?? []).map((f) => ({
          id: f.id,
          label: f.name ? `${f.id}: ${f.name}` : f.id,
        })),
      ]
    : []
  const showAlertTarget = isMetricsMode && targetCandidates.length > 1

  const handleSubmit = () => {
    onSubmit({
      name,
      description: description || null,
      enabled,
      query_mode: queryState.mode,
      query: queryStateToPayload(queryState),
      eval_window_ms: evalWindowMs,
      eval_interval_ms: evalIntervalMs,
      alert_on: alertOn,
      alert_target: showAlertTarget ? alertTarget : null,
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
            aria-invalid={!!nameError}
          />
          {nameError && (
            <p className="text-xs text-destructive">{nameError}</p>
          )}
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
      <div className="grid grid-cols-4 gap-4">
        <div className="space-y-2">
          <Label>Alert fires when</Label>
          <Select
            value={alertOn}
            onValueChange={(v) => setAlertOn(v as AlertOn)}
          >
            <SelectTrigger>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="result">Query returns results</SelectItem>
              <SelectItem value="no_result">Query returns no results</SelectItem>
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            {alertOn === "no_result"
              ? "Fires when the query finds nothing (absence detection)"
              : "Fires when the query finds matching data"}
          </p>
        </div>
        <div className="space-y-2">
          <Label>Evaluation Window</Label>
          <Select
            value={String(evalWindowMs)}
            onValueChange={(v) => setEvalWindowMs(parseInt(v, 10))}
          >
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
          <Select
            value={String(evalIntervalMs)}
            onValueChange={(v) => setEvalIntervalMs(parseInt(v, 10))}
          >
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
          {alertOn === "no_result"
            ? "Define the query condition. The alert fires when the query returns no results (absence detection)."
            : "Define the query condition. The alert fires when the query returns non-empty results."}
        </p>
        {alertOn === "no_result" && queryState.groupBy.length > 0 && (
          <div className="rounded-md bg-muted/50 border p-3 flex items-start gap-2">
            <AlertCircle className="text-muted-foreground shrink-0 mt-0.5" size={16} />
            <p className="text-sm text-muted-foreground">
              This alerts when a group it has already seen disappears — it
              won&apos;t fire for a group it has never observed. To alert when
              the query returns nothing at all, remove the group-by.
            </p>
          </div>
        )}
        {queryError && (
          <div className="rounded-md bg-destructive/10 border border-destructive/50 p-3 flex items-start gap-2">
            <AlertCircle className="text-destructive shrink-0 mt-0.5" size={16} />
            <p className="text-sm text-destructive">{queryError}</p>
          </div>
        )}
        <div className={`rounded-lg border p-4 ${queryError ? "border-destructive" : ""}`}>
          <QueryBuilder
            initialState={queryState}
            onSubmit={setQueryState}
            onChange={setQueryState}
            autoSubmit={false}
            embeddedMode
          />
        </div>
      </div>

      {/* Alert target — required when query has more than one metric/formula */}
      {showAlertTarget && (
        <div className="space-y-2">
          <Label>Alert target</Label>
          <Select
            value={alertTarget ?? ""}
            onValueChange={(v) => setAlertTarget(v)}
          >
            <SelectTrigger
              className="w-auto min-w-[240px]"
              aria-invalid={!!alertTargetError}
            >
              <SelectValue placeholder="Select metric or formula..." />
            </SelectTrigger>
            <SelectContent>
              {targetCandidates.map((c) => (
                <SelectItem key={c.id} value={c.id}>
                  {c.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-xs text-muted-foreground">
            Multiple metrics/formulas declared. Pick which one this alert
            watches; only that series decides firing.
          </p>
          {alertTargetError && (
            <p className="text-xs text-destructive">{alertTargetError}</p>
          )}
        </div>
      )}

      {/* Actions */}
      <div className="flex items-center gap-3">
        <Button onClick={handleSubmit} disabled={submitting || !name.trim()}>
          <Save className="mr-2" size={16} />
          {submitLabel}
        </Button>
        <Button variant="outline" asChild>
          <Link href="/alert-rules">Cancel</Link>
        </Button>
      </div>
    </div>
  )
}
