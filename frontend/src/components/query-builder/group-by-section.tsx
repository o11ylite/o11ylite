import { X } from "lucide-react"

import { type Field } from "@/types"
import { FieldPicker } from "./field-picker"

export function GroupBySection({
  groupBy,
  fields,
  onChange,
}: {
  groupBy: string[]
  fields: Field[]
  onChange: (groupBy: string[]) => void
}) {
  const availableFields = fields.filter((f) => !groupBy.includes(f.name))

  const addGroupBy = (fieldName: string) => {
    if (!groupBy.includes(fieldName)) {
      onChange([...groupBy, fieldName])
    }
  }

  const removeGroupBy = (fieldName: string) => {
    onChange(groupBy.filter((f) => f !== fieldName))
  }

  return (
    <div className="flex items-center gap-1.5 flex-wrap">
      <span className="text-[10px] text-muted-foreground">group by</span>
      {groupBy.map((fieldName) => (
        <div
          key={fieldName}
          className="flex items-center gap-1 bg-secondary rounded-md px-2 py-1"
        >
          <span className="text-xs">{fieldName}</span>
          <button
            onClick={() => removeGroupBy(fieldName)}
            className="text-muted-foreground hover:text-foreground"
          >
            <X size={12} />
          </button>
        </div>
      ))}
      {availableFields.length > 0 && (
        <FieldPicker
          fields={availableFields}
          value=""
          onSelect={addGroupBy}
          placeholder="+ field"
        />
      )}
    </div>
  )
}
