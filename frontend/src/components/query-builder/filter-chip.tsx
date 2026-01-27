import { useState } from "react"
import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { type Field, type SimpleFilter, type FilterOp } from "@/types"
import { FieldPicker } from "./field-picker"

const FILTER_OPERATORS: { value: FilterOp; label: string }[] = [
  { value: "=", label: "=" },
  { value: "!=", label: "!=" },
  { value: ">", label: ">" },
  { value: "<", label: "<" },
  { value: ">=", label: ">=" },
  { value: "<=", label: "<=" },
  { value: "contains", label: "contains" },
]

export function FilterChip({
  filter,
  fields,
  onUpdate,
  onRemove,
}: {
  filter: SimpleFilter
  fields: Field[]
  onUpdate: (filter: SimpleFilter) => void
  onRemove: () => void
}) {
  // Only buffer text input to avoid per-keystroke queries
  const [localValue, setLocalValue] = useState(String(filter.value ?? ""))
  const [prevValue, setPrevValue] = useState(filter.value)

  // Sync when parent changes (e.g., browser back/forward)
  if (prevValue !== filter.value) {
    setPrevValue(filter.value)
    setLocalValue(String(filter.value ?? ""))
  }

  const commitValue = () => {
    if (localValue !== String(filter.value ?? "")) {
      onUpdate({ ...filter, value: localValue })
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      e.preventDefault()
      commitValue()
    }
  }

  return (
    <div className="flex items-center gap-1">
      <FieldPicker
        fields={fields}
        value={filter.field}
        onSelect={(field) => onUpdate({ ...filter, field })}
        placeholder="field..."
      />
      <Select
        value={filter.op}
        onValueChange={(op: FilterOp) => onUpdate({ ...filter, op })}
      >
        <SelectTrigger size="sm" className="w-auto min-w-[60px]">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {FILTER_OPERATORS.map((op) => (
            <SelectItem key={op.value} value={op.value}>
              {op.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      <Input
        type="text"
        value={localValue}
        onChange={(e) => setLocalValue(e.target.value)}
        onBlur={commitValue}
        onKeyDown={handleKeyDown}
        placeholder="value"
        className="h-8 w-[250px] text-sm"
      />
      <Button
        variant="ghost"
        size="icon-sm"
        onClick={onRemove}
        className="text-muted-foreground hover:text-foreground"
      >
        <X />
      </Button>
    </div>
  )
}
