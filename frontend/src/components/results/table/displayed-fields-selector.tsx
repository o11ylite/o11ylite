import { useState } from "react"
import type { Table } from "@tanstack/react-table"
import { Check, ChevronDown, RotateCcw } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Command,
  CommandEmpty,
  CommandGroup,
  CommandInput,
  CommandItem,
  CommandList,
  CommandSeparator,
} from "@/components/ui/command"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"

import type { RowData } from "./columns"

interface DisplayedFieldsSelectorProps {
  table: Table<RowData>
  onReset?: () => void
}

export function DisplayedFieldsSelector({
  table,
  onReset,
}: DisplayedFieldsSelectorProps) {
  const [open, setOpen] = useState(false)

  const columns = table
    .getAllColumns()
    .filter((column) => column.getCanHide())

  if (columns.length === 0) {
    return null
  }

  const visibleColumns = columns.filter((col) => col.getIsVisible())
  const hiddenColumns = columns.filter((col) => !col.getIsVisible())

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="outline" size="sm">
          Displayed fields ({visibleColumns.length}/{columns.length}){" "}
          <ChevronDown className="ml-1 h-4 w-4" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-64 p-0" align="end">
        <Command>
          <CommandInput placeholder="Search fields..." />
          <CommandList>
            <CommandEmpty>No field found.</CommandEmpty>
            {visibleColumns.length > 0 && (
              <CommandGroup heading="Visible">
                {visibleColumns.map((column) => (
                  <FieldItem
                    key={column.id}
                    name={column.id}
                    visible
                    onToggle={() => column.toggleVisibility(false)}
                  />
                ))}
              </CommandGroup>
            )}
            {hiddenColumns.length > 0 && (
              <CommandGroup heading="Hidden">
                {hiddenColumns.map((column) => (
                  <FieldItem
                    key={column.id}
                    name={column.id}
                    visible={false}
                    onToggle={() => column.toggleVisibility(true)}
                  />
                ))}
              </CommandGroup>
            )}
          </CommandList>
          {onReset && (
            <>
              <CommandSeparator />
              <div className="p-1">
                <button
                  className="text-muted-foreground hover:text-foreground flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-sm"
                  onClick={onReset}
                >
                  <RotateCcw className="size-4" />
                  Reset to defaults
                </button>
              </div>
            </>
          )}
        </Command>
      </PopoverContent>
    </Popover>
  )
}

function FieldItem({
  name,
  visible,
  onToggle,
}: {
  name: string
  visible: boolean
  onToggle: () => void
}) {
  return (
    <CommandItem value={name} onSelect={onToggle}>
      <div
        className={cn(
          "flex size-4 shrink-0 items-center justify-center rounded-sm border",
          visible
            ? "border-primary bg-primary text-primary-foreground"
            : "border-muted-foreground/40"
        )}
      >
        {visible && <Check className="size-3" />}
      </div>
      {name}
    </CommandItem>
  )
}
