import { useState } from "react"
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
import { queryStateToPayload } from "@/components/alert-rules/query-helpers"
import type { Field, Service, QueryBuilderState } from "@/types"

interface AlertRuleFormPayload {
  name: string
  description: string | null
  enabled: boolean
  query_mode: string
  query: string
  eval_window_ms: number
  eval_interval_ms: number
}

interface AlertRuleFormProps {
  fields: Field[]
  services: Service[]
  initialValues: {
    name: string
    description: string
    enabled: boolean
    queryState: QueryBuilderState
    evalWindowMs: number
    evalIntervalMs: number
  }
  errors?: Partial<Record<string, string>>
  submitting?: boolean
  submitLabel: string
  onSubmit: (data: AlertRuleFormPayload) => void
}

export function AlertRuleForm({
  fields,
  services,
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
  const [queryState, setQueryState] = useState(initialValues.queryState)

  const nameError = errors.name
  const queryError = errors.query

  const handleSubmit = () => {
    onSubmit({
      name,
      description: description || null,
      enabled,
      query_mode: queryState.mode,
      query: JSON.stringify(queryStateToPayload(queryState)),
      eval_window_ms: evalWindowMs,
      eval_interval_ms: evalIntervalMs,
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
      <div className="grid grid-cols-3 gap-4">
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
          Define the query condition. The alert fires when the query returns
          non-empty results.
        </p>
        {queryError && (
          <div className="rounded-md bg-destructive/10 border border-destructive/50 p-3 flex items-start gap-2">
            <AlertCircle className="text-destructive shrink-0 mt-0.5" size={16} />
            <p className="text-sm text-destructive">{queryError}</p>
          </div>
        )}
        <div className={`rounded-lg border p-4 ${queryError ? "border-destructive" : ""}`}>
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
          {submitLabel}
        </Button>
        <Button variant="outline" asChild>
          <Link href="/alert-rules">Cancel</Link>
        </Button>
      </div>
    </div>
  )
}
