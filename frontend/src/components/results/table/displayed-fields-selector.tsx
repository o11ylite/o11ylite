import type { Column, Table } from "@tanstack/react-table"
import { Check, RotateCcw } from "lucide-react"

import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import {
  Combobox,
  ComboboxCollection,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxGroup,
  ComboboxInput,
  ComboboxItem,
  ComboboxLabel,
  ComboboxList,
  ComboboxSeparator,
  ComboboxTrigger,
} from "@/components/ui/combobox"

import type { RowData } from "./columns"

interface DisplayedFieldsSelectorProps {
  table: Table<RowData>
  onReset?: () => void
}

interface FieldGroup {
  label: string
  items: Column<RowData, unknown>[]
}

export function DisplayedFieldsSelector({
  table,
  onReset,
}: DisplayedFieldsSelectorProps) {
  const columns = table
    .getAllColumns()
    .filter((column) => column.getCanHide())

  if (columns.length === 0) {
    return null
  }

  const visibleColumns = columns.filter((col) => col.getIsVisible())
  const hiddenColumns = columns.filter((col) => !col.getIsVisible())
  const groups: FieldGroup[] = [
    { label: "Visible", items: visibleColumns },
    { label: "Hidden", items: hiddenColumns },
  ].filter((group) => group.items.length > 0)

  return (
    <Combobox<Column<RowData, unknown>>
      items={groups}
      value={null}
      onValueChange={(column, eventDetails) => {
        if (column) {
          column.toggleVisibility(!column.getIsVisible())
          eventDetails.cancel()
        }
      }}
      itemToStringValue={(column) => column.id}
      itemToStringLabel={(column) => column.id}
    >
      <ComboboxTrigger render={<Button variant="outline" size="sm" />}>
        Displayed fields ({visibleColumns.length}/{columns.length})
      </ComboboxTrigger>
      <ComboboxContent className="w-max" align="end">
        <ComboboxInput showTrigger={false} placeholder="Search fields..." />
        <ComboboxEmpty>No field found.</ComboboxEmpty>
        <ComboboxList>
          {(group: FieldGroup) => (
            <ComboboxGroup
              key={group.label}
              items={group.items}
              className="pb-2 last:pb-0"
            >
              <ComboboxLabel>{group.label}</ComboboxLabel>
              <ComboboxCollection>
                {(column: Column<RowData, unknown>) => (
                  <ComboboxItem key={column.id} value={column}>
                    <VisibilityCheckbox visible={column.getIsVisible()} />
                    <span className="min-w-0 flex-1 break-words">
                      {column.id}
                    </span>
                  </ComboboxItem>
                )}
              </ComboboxCollection>
            </ComboboxGroup>
          )}
        </ComboboxList>
        {onReset && (
          <>
            <ComboboxSeparator />
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
      </ComboboxContent>
    </Combobox>
  )
}

function VisibilityCheckbox({ visible }: { visible: boolean }) {
  return (
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
  )
}
