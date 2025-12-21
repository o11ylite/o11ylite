import { useState } from "react"
import { Check, ChevronsUpDown } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
} from "@/components/ui/command"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { type Field } from "@/types"
import { FieldTypeBadge } from "@/components/field-type-badge"

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
  const [open, setOpen] = useState(false)
  const selectedField = fields.find((f) => f.name === value)

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button
          variant="outline"
          size="sm"
          role="combobox"
          aria-expanded={open}
          className="min-w-[100px] justify-between font-normal"
        >
          {selectedField ? (
            <span className="flex items-center gap-1.5">
              <FieldTypeBadge type={selectedField.type} />
              {selectedField.name}
            </span>
          ) : (
            <span className="text-muted-foreground">{placeholder}</span>
          )}
          <ChevronsUpDown className="opacity-50" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-56 p-0" align="start">
        <Command>
          <CommandInput placeholder="Search fields..." />
          <CommandList>
            <CommandEmpty>No field found.</CommandEmpty>
            <CommandGroup>
              {fields.map((field) => (
                <CommandItem
                  key={field.name}
                  value={field.name}
                  onSelect={(name) => {
                    onSelect(name)
                    setOpen(false)
                  }}
                >
                  <FieldTypeBadge type={field.type} />
                  {field.name}
                  <Check
                    className={cn(
                      "ml-auto",
                      value === field.name ? "opacity-100" : "opacity-0"
                    )}
                  />
                </CommandItem>
              ))}
            </CommandGroup>
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
