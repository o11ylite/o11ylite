import { Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import { type Field, type Aggregation } from "@/types"
import { AggregationRow } from "./aggregation-row"
import { GroupBySection } from "./group-by-section"

export function AggregationSection({
  aggregations,
  groupBy,
  fields,
  onAggregationsChange,
  onGroupByChange,
}: {
  aggregations: Aggregation[]
  groupBy: string[]
  fields: Field[]
  onAggregationsChange: (aggregations: Aggregation[]) => void
  onGroupByChange: (groupBy: string[]) => void
}) {
  const addAggregation = () => {
    onAggregationsChange([
      ...aggregations,
      { field: "*", function: "count" },
    ])
  }

  const updateAggregation = (index: number, item: Aggregation) => {
    const newAggregations = [...aggregations]
    newAggregations[index] = item
    onAggregationsChange(newAggregations)
  }

  const removeAggregation = (index: number) => {
    onAggregationsChange(aggregations.filter((_, i) => i !== index))
  }

  const hasAggregations = aggregations.length > 0

  return (
    <div className="bg-muted/50 rounded-lg p-2 space-y-2">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-[10px] uppercase tracking-wider text-muted-foreground px-1">
          Aggregate
        </span>
        {aggregations.map((item, index) => (
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
          size="icon-sm"
          onClick={addAggregation}
          className="text-muted-foreground hover:text-foreground"
        >
          <Plus />
        </Button>
      </div>

      {hasAggregations && (
        <GroupBySection
          groupBy={groupBy}
          fields={fields}
          onChange={onGroupByChange}
        />
      )}
    </div>
  )
}
