import { useState } from "react"
import { ChevronRight, MoreVertical } from "lucide-react"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { FieldTypeBadge } from "@/components/field-type-badge"
import { useEventFieldsQuery } from "@/hooks/use-event-fields-query"
import { useEventQueryActions } from "@/hooks/use-event-query-actions"
import { groupAttributeFields } from "@/lib/group-fields"
import type { Field } from "@/types"

function FieldRow({
  field,
  onAddExistsFilter,
}: {
  field: Field
  onAddExistsFilter: (name: string) => void
}) {
  return (
    <div className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-accent group">
      <div className="flex items-center gap-2 flex-1 min-w-0">
        <FieldTypeBadge type={field.type} />
        <span className="text-xs text-muted-foreground group-hover:text-foreground truncate">
          {field.name}
        </span>
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="h-6 w-6 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity"
            aria-label={`Actions for ${field.name}`}
          >
            <MoreVertical className="h-3 w-3" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onClick={() => onAddExistsFilter(field.name)}>
            Filter where field exists
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

export function EventsSidePanel() {
  const { fields } = useEventFieldsQuery()
  const [searchQuery, setSearchQuery] = useState("")
  const { addExistsFilter } = useEventQueryActions()

  const isSearching = searchQuery.length > 0
  const matchesSearch = (name: string) =>
    !isSearching || name.toLowerCase().includes(searchQuery.toLowerCase())

  const filteredFields = fields.filter((f) => matchesSearch(f.name))
  const { ungrouped, groups } = groupAttributeFields(filteredFields)

  return (
    <div className="space-y-2">
      <Input
        type="text"
        placeholder="Search fields..."
        className="h-8 text-xs"
        value={searchQuery}
        onChange={(e) => setSearchQuery(e.target.value)}
      />
      <div className="space-y-0.5">
        {ungrouped.map((field) => (
          <FieldRow
            key={field.name}
            field={field}
            onAddExistsFilter={addExistsFilter}
          />
        ))}
        {groups.map((group) => (
          <FieldGroup
            key={group.prefix}
            prefix={group.prefix}
            fields={group.fields}
            // While searching, force-open every surviving group so matching
            // children are visible without an extra click. Otherwise default
            // to collapsed — that's the whole point of the grouping.
            forceOpen={isSearching}
            onAddExistsFilter={addExistsFilter}
          />
        ))}
      </div>
    </div>
  )
}

function FieldGroup({
  prefix,
  fields,
  forceOpen,
  onAddExistsFilter,
}: {
  prefix: string
  fields: Field[]
  forceOpen: boolean
  onAddExistsFilter: (name: string) => void
}) {
  const [open, setOpen] = useState(false)
  const isOpen = forceOpen || open

  return (
    <Collapsible open={isOpen} onOpenChange={setOpen}>
      <CollapsibleTrigger
        className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left hover:bg-accent group"
        aria-label={`Toggle ${prefix} attributes`}
      >
        <ChevronRight
          className={`h-3 w-3 shrink-0 text-muted-foreground transition-transform ${isOpen ? "rotate-90" : ""}`}
        />
        <span className="text-xs font-medium text-foreground truncate flex-1 min-w-0">
          {prefix}
        </span>
        <span className="text-[10px] text-muted-foreground shrink-0">
          {fields.length}
        </span>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="ml-4 space-y-0.5 border-l border-border pl-2">
          {fields.map((field) => (
            <FieldRow
              key={field.name}
              field={field}
              onAddExistsFilter={onAddExistsFilter}
            />
          ))}
        </div>
      </CollapsibleContent>
    </Collapsible>
  )
}
