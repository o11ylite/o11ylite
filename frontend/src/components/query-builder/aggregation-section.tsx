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
  const nextId = () => String.fromCharCode(65 + aggregations.length) // A=65, B=66, ...

  const addAggregation = () => {
    onAggregationsChange([
      ...aggregations,
      { id: nextId(), field: "*", function: "count" },
    ])
  }

  const updateAggregation = (index: number, item: Aggregation) => {
    const newAggregations = [...aggregations]
    newAggregations[index] = item
    onAggregationsChange(newAggregations)
  }

  // Reassign IDs to maintain A, B, C order after removal
  const reassignIds = (aggs: Aggregation[]) =>
    aggs.map((agg, i) => ({ ...agg, id: String.fromCharCode(65 + i) }))

  const removeAggregation = (index: number) => {
    onAggregationsChange(reassignIds(aggregations.filter((_, i) => i !== index)))
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
