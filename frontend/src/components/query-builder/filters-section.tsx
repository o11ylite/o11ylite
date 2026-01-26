import { Plus } from "lucide-react"

import { Button } from "@/components/ui/button"
import { type Field, type SimpleFilter } from "@/types"
import { FilterChip } from "./filter-chip"

export function FiltersSection({
  filters,
  fields,
  onFiltersChange,
}: {
  filters: SimpleFilter[]
  fields: Field[]
  onFiltersChange: (filters: SimpleFilter[]) => void
}) {
  const addFilter = () => {
    onFiltersChange([...filters, { field: "", op: "=", value: "" }])
  }

  const updateFilter = (index: number, filter: SimpleFilter) => {
    const newFilters = [...filters]
    newFilters[index] = filter
    onFiltersChange(newFilters)
  }

  const removeFilter = (index: number) => {
    onFiltersChange(filters.filter((_, i) => i !== index))
  }

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
