import { X } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { type Field, type Aggregation, type AggregationFunction } from "@/types"
import { FieldPicker } from "./field-picker"

const AGGREGATION_FUNCTIONS: AggregationFunction[] = [
  "count",
  "sum",
  "avg",
  "min",
  "max",
  "p50",
  "p90",
  "p99",
]

export function AggregationRow({
  item,
  fields,
  onUpdate,
  onRemove,
}: {
  item: Aggregation
  fields: Field[]
  onUpdate: (item: Aggregation) => void
  onRemove: () => void
}) {
  const numericFields = fields.filter((f) => f.type === "integer" || f.type === "float")
  const needsField = item.function !== "count"

  return (
    <div className="flex items-center gap-1">
      <Select
        value={item.function}
        onValueChange={(fn) =>
          fn &&
          onUpdate({
            ...item,
            function: fn,
            field: fn === "count" ? "*" : item.field,
          })
        }
      >
        <SelectTrigger size="sm" className="w-auto min-w-[80px]">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {AGGREGATION_FUNCTIONS.map((fn) => (
            <SelectItem key={fn} value={fn}>
              {fn}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>

      {needsField && (
        <>
          <span className="text-[10px] text-muted-foreground">(</span>
          <FieldPicker
            fields={numericFields}
            value={item.field === "*" ? "" : item.field}
            onSelect={(field) => onUpdate({ ...item, field })}
            placeholder="field..."
          />
          <span className="text-[10px] text-muted-foreground">)</span>
        </>
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
