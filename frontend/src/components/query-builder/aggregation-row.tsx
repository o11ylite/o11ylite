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
  const numericFields = fields.filter((f) => f.type === "num")
  const needsField = item.function !== "count"

  return (
    <div className="flex items-center gap-1">
      <Select
        value={item.function}
        onValueChange={(fn: AggregationFunction) =>
          onUpdate({ ...item, function: fn })
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
            value={item.field}
            onSelect={(field) => onUpdate({ ...item, field })}
            placeholder="field..."
          />
          <span className="text-[10px] text-muted-foreground">)</span>
        </>
      )}

      <span className="text-[10px] text-muted-foreground">as</span>
      <Input
        type="text"
        value={item.alias ?? ""}
        onChange={(e) =>
          onUpdate({ ...item, alias: e.target.value || undefined })
        }
        placeholder="alias"
        className="h-8 w-20 text-sm"
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
