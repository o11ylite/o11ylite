import { cn } from "@/lib/utils"
import { type FieldType } from "@/types"

const TYPE_CONFIG: Record<FieldType, { label: string; className: string }> = {
  string: { label: "S", className: "bg-emerald-500/20 text-emerald-400" },
  instant: { label: "T", className: "bg-blue-500/20 text-blue-400" },
  integer: { label: "N", className: "bg-amber-500/20 text-amber-400" },
  float: { label: "N", className: "bg-amber-500/20 text-amber-400" },
  boolean: { label: "B", className: "bg-purple-500/20 text-purple-400" },
}

export function FieldTypeBadge({ type }: { type: FieldType }) {
  const config = TYPE_CONFIG[type]
  return (
    <span
      className={cn(
        "text-[9px] font-medium px-1 py-0.5 rounded",
        config.className
      )}
    >
      {config.label}
    </span>
  )
}
