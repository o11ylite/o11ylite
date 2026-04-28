import { useState } from "react"
import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import type { FormulaDefinition } from "@/types"

// ============================================================================
// Component
// ============================================================================

/**
 * Single formula authoring row.
 *
 * Inputs are buffered locally and only committed to parent state on blur or
 * Enter (mirrors the filter-chip pattern). This avoids triggering a query —
 * and a likely 400 — on every keystroke while the user is mid-edit.
 */
export function FormulaRow({
  formula,
  onUpdate,
  onRemove,
}: {
  formula: FormulaDefinition
  onUpdate: (formula: FormulaDefinition) => void
  onRemove: () => void
}) {
  // Local buffers so each keystroke doesn't push to parent state.
  const [exprDraft, setExprDraft] = useState(formula.expr)
  const [nameDraft, setNameDraft] = useState(formula.name ?? "")
  const [unitDraft, setUnitDraft] = useState(formula.unit ?? "")

  // Sync drafts when parent changes (e.g., browser back/forward, URL load).
  const [prevFormula, setPrevFormula] = useState(formula)
  if (prevFormula !== formula) {
    setPrevFormula(formula)
    setExprDraft(formula.expr)
    setNameDraft(formula.name ?? "")
    setUnitDraft(formula.unit ?? "")
  }

  const commitExpr = () => {
    if (exprDraft !== formula.expr) {
      onUpdate({ ...formula, expr: exprDraft })
    }
  }

  const commitName = () => {
    const next = nameDraft || undefined
    if (next !== formula.name) {
      onUpdate({ ...formula, name: next })
    }
  }

  const commitUnit = () => {
    const next = unitDraft || undefined
    if (next !== formula.unit) {
      onUpdate({ ...formula, unit: next })
    }
  }

  const onEnter = (commit: () => void) => (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      e.preventDefault()
      commit()
    }
  }

  return (
    <div className="flex items-center gap-2">
      {/* Letter Badge */}
      <div className="flex items-center justify-center w-6 h-8 rounded bg-secondary text-xs font-semibold text-muted-foreground">
        {formula.id}
      </div>

      <div className="flex-1 flex items-center gap-2">
        {/* Expression */}
        <Input
          value={exprDraft}
          onChange={(e) => setExprDraft(e.target.value)}
          onBlur={commitExpr}
          onKeyDown={onEnter(commitExpr)}
          placeholder="e.g. A / B * 100"
          className="font-mono text-sm flex-[2]"
          aria-label={`Formula ${formula.id} expression`}
        />

        {/* Display name */}
        <Input
          value={nameDraft}
          onChange={(e) => setNameDraft(e.target.value)}
          onBlur={commitName}
          onKeyDown={onEnter(commitName)}
          placeholder="display name (optional)"
          className="text-sm flex-1"
          aria-label={`Formula ${formula.id} name`}
        />

        {/* Unit */}
        <Input
          value={unitDraft}
          onChange={(e) => setUnitDraft(e.target.value)}
          onBlur={commitUnit}
          onKeyDown={onEnter(commitUnit)}
          placeholder="unit"
          className="text-sm w-20"
          aria-label={`Formula ${formula.id} unit`}
        />

        {/* Remove */}
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={onRemove}
          className="text-muted-foreground hover:text-foreground"
          aria-label={`Remove formula ${formula.id}`}
        >
          <X />
        </Button>
      </div>
    </div>
  )
}
