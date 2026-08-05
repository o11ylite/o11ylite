import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
  ComboboxTrigger,
} from "@/components/ui/combobox"
import { Button } from "@/components/ui/button"
import { FieldTypeBadge } from "@/components/field-type-badge"
import { type Field } from "@/types"

export function FieldPicker({
  fields,
  value,
  onSelect,
  placeholder = "Select field...",
}: {
  fields: Field[]
  value: string
  onSelect: (fieldName: string) => void
  placeholder?: string
}) {
  const selectedField = fields.find((f) => f.name === value) ?? null

  return (
    <Combobox
      items={fields}
      value={selectedField}
      onValueChange={(field) => field && onSelect(field.name)}
      itemToStringValue={(field) => field.name}
    >
      <ComboboxTrigger
        render={
          <Button
            variant="outline"
            size="sm"
            className="min-w-[100px] justify-between font-normal"
          />
        }
      >
        {selectedField ? (
          <span className="flex items-center gap-1.5">
            <FieldTypeBadge type={selectedField.type} />
            {selectedField.name}
          </span>
        ) : (
          <span className="text-muted-foreground">{placeholder}</span>
        )}
      </ComboboxTrigger>
      <ComboboxContent className="w-max">
        <ComboboxInput showTrigger={false} placeholder="Search fields..." />
        <ComboboxEmpty>No field found.</ComboboxEmpty>
        <ComboboxList>
          {(field: Field) => (
            <ComboboxItem key={field.name} value={field}>
              <FieldTypeBadge type={field.type} />
              <span className="min-w-0 flex-1 break-words">{field.name}</span>
            </ComboboxItem>
          )}
        </ComboboxList>
      </ComboboxContent>
    </Combobox>
  )
}
