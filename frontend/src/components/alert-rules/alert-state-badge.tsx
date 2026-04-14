import type { AlertState } from "@/types"

const STATE_CONFIG = {
  ok: { color: "bg-green-500", label: "OK" },
  firing: { color: "bg-red-500", label: "Firing" },
}

export function AlertStateBadge({ state }: { state: AlertState }) {
  const { color, label } = STATE_CONFIG[state]
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className={`inline-block h-2 w-2 rounded-full ${color}`} />
      <span className="text-sm">{label}</span>
    </span>
  )
}
