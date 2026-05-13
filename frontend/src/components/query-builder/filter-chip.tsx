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
import { type Field, type SimpleFilter, type FilterOp, type FieldType } from "@/types"
import { FieldPicker } from "./field-picker"

function coerceFilterValue(
  raw: string,
  fieldType: FieldType | undefined,
): string | number | boolean {
  if (!fieldType) return raw
  switch (fieldType) {
    case "boolean": {
      const lower = raw.toLowerCase()
      if (lower === "true") return true
      if (lower === "false") return false
      return raw
    }
    case "integer": {
      const n = parseInt(raw, 10)
      return isNaN(n) ? 0 : n
    }
    case "float": {
      const n = parseFloat(raw)
      return isNaN(n) ? 0 : n
    }
    default:
      return raw
  }
}

const FILTER_OPERATORS: { value: FilterOp; label: string }[] = [
  { value: "=", label: "=" },
  { value: "!=", label: "!=" },
  { value: ">", label: ">" },
  { value: "<", label: "<" },
  { value: ">=", label: ">=" },
  { value: "<=", label: "<=" },
  { value: "contains", label: "contains" },
  { value: "exists", label: "exists" },
  { value: "starts-with", label: "starts-with" },
]

const VALID_OPERATORS_BY_TYPE: Record<
  FieldType,
  Set<FilterOp>
> = {
  string: new Set(["=", "!=", "contains", "exists", "starts-with"]),
  integer: new Set(["=", "!=", ">", "<", ">=", "<=", "exists"]),
  float: new Set(["=", "!=", ">", "<", ">=", "<=", "exists"]),
  boolean: new Set(["=", "!=", "exists"]),
  instant: new Set(["=", "!=", ">", "<", ">=", "<=", "exists"]),
}

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

  const selectedField = fields.find((f) => f.name === filter.field)
  const valueLess = filter.op === "exists"
  const isBoolean = selectedField?.type === "boolean"
  const isNumeric = selectedField?.type === "integer" || selectedField?.type === "float"

  // Sync when parent changes (e.g., browser back/forward)
  if (prevValue !== filter.value) {
    setPrevValue(filter.value)
    setLocalValue(String(filter.value ?? ""))
  }

  const commitValue = () => {
    if (localValue !== String(filter.value ?? "")) {
      onUpdate({ ...filter, value: coerceFilterValue(localValue, selectedField?.type) })
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === "Enter") {
      e.preventDefault()
      commitValue()
    }
  }

  const handleFieldSelect = (fieldName: string) => {
    const newField = fields.find((f) => f.name === fieldName)
    if (!newField) return

    const needsReset = selectedField && newField.type !== selectedField.type

    onUpdate({
      ...filter,
      field: fieldName,
      op: needsReset ? "=" : filter.op,
      value: needsReset ? "" : filter.value,
    })
  }

  const handleOpChange = (op: FilterOp) => {
    onUpdate({ ...filter, op, value: valueLess ? "" : filter.value })
  }

  const validOps = selectedField
    ? VALID_OPERATORS_BY_TYPE[selectedField.type]
    : new Set(FILTER_OPERATORS.map((op) => op.value))
  const filteredOperators = FILTER_OPERATORS.filter((op) =>
    validOps.has(op.value)
  )

  return (
    <div className="flex items-center gap-1">
      <FieldPicker
        fields={fields}
        value={filter.field}
        onSelect={handleFieldSelect}
        placeholder="field..."
      />
      <Select value={filter.op} onValueChange={handleOpChange}>
        <SelectTrigger size="sm" className="w-auto min-w-[60px]">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {filteredOperators.map((op) => (
            <SelectItem key={op.value} value={op.value}>
              {op.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {!valueLess && isBoolean && (
        <Select
          value={String(filter.value ?? "")}
          onValueChange={(v) => onUpdate({ ...filter, value: v === "true" })}
        >
          <SelectTrigger size="sm" className="w-auto min-w-[80px]">
            <SelectValue placeholder="value" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="true">true</SelectItem>
            <SelectItem value="false">false</SelectItem>
          </SelectContent>
        </Select>
      )}
      {!valueLess && !isBoolean && (
        <Input
          type={isNumeric ? "number" : "text"}
          step={selectedField?.type === "float" ? "any" : undefined}
          value={localValue}
          onChange={(e) => setLocalValue(e.target.value)}
          onBlur={commitValue}
          onKeyDown={handleKeyDown}
          placeholder="value"
          className="h-8 w-[250px] text-sm"
        />
      )}
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
