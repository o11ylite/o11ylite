import type { ReactNode } from "react"

import { cn } from "@/lib/utils"

export function CollapsiblePanel({
  children,
  open,
  width,
  className,
}: {
  children: ReactNode
  open: boolean
  width: string
  className?: string
}) {
  return (
    <aside
      className={cn(
        "overflow-hidden transition-[width] duration-200 ease-linear",
        className
      )}
      style={
        {
          "--panel-width": width,
          width: open ? "var(--panel-width)" : 0,
        } as React.CSSProperties
      }
    >
      <div
        className="h-full overflow-auto p-4"
        style={{ width: "var(--panel-width)" } as React.CSSProperties}
      >
        {children}
      </div>
    </aside>
  )
}
