import { Input } from "@/components/ui/input"
import { type Field } from "@/types"
import { FieldTypeBadge } from "@/components/field-type-badge"

export function FieldsPanel({
  fields,
  onFieldClick,
}: {
  fields: Field[]
  onFieldClick: (fieldName: string) => void
}) {
  return (
    <div className="space-y-2">
      <Input
        type="text"
        placeholder="Search fields..."
        className="h-8 text-xs"
      />
      <div className="space-y-0.5">
        {fields.map((field) => (
          <button
            key={field.name}
            onClick={() => onFieldClick(field.name)}
            className="w-full flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-accent text-left"
          >
            <FieldTypeBadge type={field.type} />
            <span className="text-xs text-muted-foreground hover:text-foreground truncate">
              {field.name}
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}
