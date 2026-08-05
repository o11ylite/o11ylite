import { Plus, X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type { SimpleHaving, HavingOp } from "@/types"
import { useState } from "react"

const HAVING_OPERATORS: HavingOp[] = [">", "<", ">=", "<=", "=", "!="]

export interface RefOption {
  id: string
  label: string
}

export function HavingSection({
  having,
  refs,
  onChange,
}: {
  having: SimpleHaving | undefined
  refs: RefOption[]
  onChange: (having: SimpleHaving | undefined) => void
}) {
  const [localValue, setLocalValue] = useState<string>(
    having?.value?.toString() ?? ""
  )

  const addHaving = () => {
    if (refs.length === 0) return
    onChange({
      ref: refs[0].id,
      op: ">",
      value: 0,
    })
  }

  const removeHaving = () => {
    onChange(undefined)
  }

  const updateRef = (ref: string) => {
    if (having) {
      onChange({ ...having, ref })
    }
  }

  const updateOp = (op: HavingOp) => {
    if (having) {
      onChange({ ...having, op })
    }
  }

  const updateValue = (valueStr: string) => {
    const numValue = parseFloat(valueStr)
    if (having && !isNaN(numValue)) {
      onChange({ ...having, value: numValue })
    }
  }

  if (!having) {
    return (
      <div className="bg-muted/50 rounded-lg p-2">
        <div className="flex items-center gap-1.5">
          <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
            Having
          </span>
          <Button
            variant="ghost"
            size="sm"
            onClick={addHaving}
            disabled={refs.length === 0}
            className="text-muted-foreground hover:text-foreground h-7 px-2"
          >
            <Plus className="mr-1" size={14} />
            Add condition
          </Button>
        </div>
      </div>
    )
  }

  return (
    <div className="bg-muted/50 rounded-lg p-2">
      <div className="flex items-center gap-1.5">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Having
        </span>

        <Select
          value={having.ref}
          onValueChange={(ref) => ref && updateRef(ref)}
        >
          <SelectTrigger size="sm" className="w-auto min-w-[120px]">
            <SelectValue>
              {(v) => refs.find((r) => r.id === v)?.label ?? ""}
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            {refs.map((ref) => (
              <SelectItem key={ref.id} value={ref.id}>
                {ref.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Select
          value={having.op}
          onValueChange={(op) => updateOp(op as HavingOp)}
        >
          <SelectTrigger size="sm" className="w-auto min-w-[60px]">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {HAVING_OPERATORS.map((op) => (
              <SelectItem key={op} value={op}>
                {op}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <Input
          type="number"
          value={localValue}
          onChange={(e) => setLocalValue(e.target.value)}
          onBlur={() => updateValue(localValue)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              updateValue(localValue)
            }
          }}
          placeholder="value"
          className="h-7 w-24 text-sm"
        />

        <Button
          variant="ghost"
          size="icon-sm"
          onClick={removeHaving}
          className="text-muted-foreground hover:text-foreground"
        >
          <X size={14} />
        </Button>
      </div>
    </div>
  )
}
