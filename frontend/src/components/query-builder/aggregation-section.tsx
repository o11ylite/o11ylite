import { useState } from "react"
import { ChevronDown, ChevronRight, BarChart3 } from "lucide-react"

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { type Field } from "@/types"

export interface Aggregation {
  method: string
  field?: string
  groupBy?: string
}

const AGGREGATION_METHODS = ["count", "sum", "avg", "p50", "p95", "p99"]

// Use a sentinel value instead of empty string for "none" options
const NONE_VALUE = "__none__"

export function AggregationSection({
  aggregation,
  fields,
  onAggregationChange,
}: {
  aggregation: Aggregation | null
  fields: Field[]
  onAggregationChange: (aggregation: Aggregation | null) => void
}) {
  const [isOpen, setIsOpen] = useState(false)
  const numericFields = fields.filter((f) => f.type === "num")

  const handleMethodChange = (method: string) => {
    if (method === NONE_VALUE) {
      onAggregationChange(null)
    } else {
      onAggregationChange({ method })
    }
  }

  const handleGroupByChange = (groupBy: string) => {
    if (!aggregation) return
    onAggregationChange({
      ...aggregation,
      groupBy: groupBy === NONE_VALUE ? undefined : groupBy,
    })
  }

  return (
    <div className="bg-muted/50 rounded-lg">
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="w-full flex items-center gap-2 px-2 py-1.5 text-xs text-muted-foreground hover:text-foreground"
      >
        {isOpen ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        <BarChart3 size={12} />
        <span>Aggregate</span>
        {aggregation && (
          <span className="text-[10px] bg-primary/20 text-primary px-1.5 py-0.5 rounded">
            {aggregation.method}
          </span>
        )}
      </button>

      {isOpen && (
        <div className="px-2 pb-2 flex items-center gap-2 flex-wrap">
          <Select
            value={aggregation?.method ?? NONE_VALUE}
            onValueChange={handleMethodChange}
          >
            <SelectTrigger size="sm" className="w-auto min-w-[80px]">
              <SelectValue placeholder="None" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={NONE_VALUE}>None</SelectItem>
              {AGGREGATION_METHODS.map((method) => (
                <SelectItem key={method} value={method}>
                  {method}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>

          {aggregation && aggregation.method !== "count" && (
            <>
              <span className="text-[10px] text-muted-foreground">of</span>
              <Select
                value={aggregation.field ?? NONE_VALUE}
                onValueChange={(field) =>
                  onAggregationChange({
                    ...aggregation,
                    field: field === NONE_VALUE ? undefined : field,
                  })
                }
              >
                <SelectTrigger size="sm" className="w-auto min-w-[100px]">
                  <SelectValue placeholder="Select field" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE_VALUE}>Select field</SelectItem>
                  {numericFields.map((f) => (
                    <SelectItem key={f.name} value={f.name}>
                      {f.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </>
          )}

          {aggregation && (
            <>
              <span className="text-[10px] text-muted-foreground">
                group by
              </span>
              <Select
                value={aggregation.groupBy ?? NONE_VALUE}
                onValueChange={handleGroupByChange}
              >
                <SelectTrigger size="sm" className="w-auto min-w-[80px]">
                  <SelectValue placeholder="none" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE_VALUE}>none</SelectItem>
                  {fields.map((f) => (
                    <SelectItem key={f.name} value={f.name}>
                      {f.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </>
          )}
        </div>
      )}
    </div>
  )
}
