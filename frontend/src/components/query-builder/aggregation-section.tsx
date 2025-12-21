import { useState } from "react"
import { ChevronDown, ChevronRight, BarChart3, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { type Field } from "@/types"
import { AggregationRow, type AggregationItem } from "./aggregation-row"
import { GroupBySection } from "./group-by-section"

export type { AggregationItem, AggregationFunction } from "./aggregation-row"

export interface AggregationConfig {
  aggregations: AggregationItem[]
  groupBy: string[]
}

export function AggregationSection({
  config,
  fields,
  onConfigChange,
}: {
  config: AggregationConfig
  fields: Field[]
  onConfigChange: (config: AggregationConfig) => void
}) {
  const [isOpen, setIsOpen] = useState(false)

  const addAggregation = () => {
    onConfigChange({
      ...config,
      aggregations: [
        ...config.aggregations,
        { field: "", function: "count" },
      ],
    })
  }

  const updateAggregation = (index: number, item: AggregationItem) => {
    const newAggregations = [...config.aggregations]
    newAggregations[index] = item
    onConfigChange({ ...config, aggregations: newAggregations })
  }

  const removeAggregation = (index: number) => {
    onConfigChange({
      ...config,
      aggregations: config.aggregations.filter((_, i) => i !== index),
    })
  }

  const hasAggregations = config.aggregations.length > 0

  const summaryText = hasAggregations
    ? config.aggregations.map((a) => a.alias || a.function).join(", ")
    : null

  return (
    <Collapsible
      open={isOpen}
      onOpenChange={setIsOpen}
      className="bg-muted/50 rounded-lg"
    >
      <CollapsibleTrigger className="w-full flex items-center gap-2 px-2 py-1.5 text-xs text-muted-foreground hover:text-foreground">
        {isOpen ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        <BarChart3 size={12} />
        <span>Aggregate</span>
        {summaryText && (
          <span className="text-[10px] bg-primary/20 text-primary px-1.5 py-0.5 rounded truncate max-w-[200px]">
            {summaryText}
          </span>
        )}
      </CollapsibleTrigger>

      <CollapsibleContent className="px-2 pb-2 space-y-2">
        <div className="space-y-1.5">
          {config.aggregations.map((item, index) => (
            <AggregationRow
              key={index}
              item={item}
              fields={fields}
              onUpdate={(updated) => updateAggregation(index, updated)}
              onRemove={() => removeAggregation(index)}
            />
          ))}
          <Button
            variant="ghost"
            size="sm"
            onClick={addAggregation}
            className="text-muted-foreground hover:text-foreground gap-1"
          >
            <Plus size={12} />
            Add aggregation
          </Button>
        </div>

        {hasAggregations && (
          <GroupBySection
            groupBy={config.groupBy}
            fields={fields}
            onChange={(groupBy) => onConfigChange({ ...config, groupBy })}
          />
        )}
      </CollapsibleContent>
    </Collapsible>
  )
}
