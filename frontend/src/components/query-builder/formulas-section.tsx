import { Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import type { FormulaDefinition } from "@/types"
import { FormulaRow } from "./formula-row"

// ============================================================================
// Helpers
// ============================================================================

const MAX_FORMULAS = 10

// Generate next available formula ID (F1..F9)
function getNextFormulaId(formulas: FormulaDefinition[]): string {
  const used = new Set(formulas.map((f) => f.id))
  for (let i = 1; i <= 9; i++) {
    const id = `F${i}`
    if (!used.has(id)) return id
  }
  return "F9"
}

// ============================================================================
// Component
// ============================================================================

export function FormulasSection({
  formulas,
  hasMetrics,
  onFormulasChange,
}: {
  formulas: FormulaDefinition[]
  hasMetrics: boolean
  onFormulasChange: (formulas: FormulaDefinition[]) => void
}) {
  // Hide section entirely when there are no metrics — formulas need refs.
  if (!hasMetrics) return null

  const addFormula = () => {
    onFormulasChange([
      ...formulas,
      { id: getNextFormulaId(formulas), expr: "" },
    ])
  }

  const updateFormula = (index: number, updated: FormulaDefinition) => {
    const next = [...formulas]
    next[index] = updated
    onFormulasChange(next)
  }

  const removeFormula = (index: number) => {
    onFormulasChange(formulas.filter((_, i) => i !== index))
  }

  const canAddMore = formulas.length < MAX_FORMULAS

  return (
    <div className="bg-muted/50 rounded-lg p-2 space-y-2">
      <div className="flex items-center justify-between">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Formulas
        </span>
      </div>

      {formulas.length > 0 && (
        <div className="space-y-2">
          {formulas.map((formula, index) => (
            <FormulaRow
              key={formula.id}
              formula={formula}
              onUpdate={(updated) => updateFormula(index, updated)}
              onRemove={() => removeFormula(index)}
            />
          ))}
        </div>
      )}

      {canAddMore && (
        <Button
          variant="ghost"
          size="sm"
          onClick={addFormula}
          className="text-muted-foreground hover:text-foreground"
        >
          <Plus className="mr-1" />
          Add Formula
        </Button>
      )}
    </div>
  )
}
