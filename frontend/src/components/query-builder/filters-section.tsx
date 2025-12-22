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

function FilterChip({
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
  return (
    <div className="flex items-center gap-1">
      <FieldPicker
        fields={fields}
        value={filter.field}
        onSelect={(name) => onUpdate({ ...filter, field: name })}
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
        value={typeof filter.value === "string" ? filter.value : String(filter.value)}
        onChange={(e) => onUpdate({ ...filter, value: e.target.value })}
        placeholder="value"
        className="h-8 w-24 text-sm"
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

export function FiltersSection({
  filters,
  fields,
  onFiltersChange,
}: {
  filters: SimpleFilter[]
  fields: Field[]
  onFiltersChange: (filters: SimpleFilter[]) => void
}) {
  const addFilter = () =>
    onFiltersChange([...filters, { field: "", op: "=", value: "" }])

  const updateFilter = (index: number, filter: SimpleFilter) => {
    const newFilters = [...filters]
    newFilters[index] = filter
    onFiltersChange(newFilters)
  }

  const removeFilter = (index: number) =>
    onFiltersChange(filters.filter((_, i) => i !== index))

  return (
    <div className="bg-muted/50 rounded-lg p-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Where
        </span>
        {filters.map((filter, i) => (
          <div key={i} className="flex items-center">
            {i > 0 && (
              <span className="text-[10px] text-muted-foreground px-1.5">
                and
              </span>
            )}
            <FilterChip
              filter={filter}
              fields={fields}
              onUpdate={(f) => updateFilter(i, f)}
              onRemove={() => removeFilter(i)}
            />
          </div>
        ))}
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={addFilter}
          className="text-muted-foreground hover:text-foreground"
        >
          <Plus />
        </Button>
      </div>
    </div>
  )
}
