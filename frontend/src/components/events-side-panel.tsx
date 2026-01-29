import { useState } from "react"
import { MoreVertical } from "lucide-react"
import { Input } from "@/components/ui/input"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { type Field } from "@/types"
import { FieldTypeBadge } from "@/components/field-type-badge"
import { useEventQueryActions } from "@/hooks/use-event-query-actions"

export function EventsSidePanel({ fields }: { fields: Field[] }) {
  const [searchQuery, setSearchQuery] = useState("")
  const { addExistsFilter } = useEventQueryActions()

  const filteredFields = fields
    .filter(
      (field) =>
        !searchQuery ||
        field.name.toLowerCase().includes(searchQuery.toLowerCase())
    )
    .sort((a, b) => {
      const aIsAttr = a.name.startsWith("attr.")
      const bIsAttr = b.name.startsWith("attr.")
      if (aIsAttr !== bIsAttr) return aIsAttr ? 1 : -1
      return a.name.localeCompare(b.name)
    })

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
        {filteredFields.map((field) => (
          <div
            key={field.name}
            className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-accent group"
          >
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
                <DropdownMenuItem onClick={() => addExistsFilter(field.name)}>
                  Filter where field exists
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        ))}
      </div>
    </div>
  )
}
