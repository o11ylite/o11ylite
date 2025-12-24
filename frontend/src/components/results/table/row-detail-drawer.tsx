import {
  Drawer,
  DrawerContent,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"

import type { RowData } from "./columns"

export function RowDetailDrawer({
  row,
  onClose,
}: {
  row: RowData | null
  onClose: () => void
}) {
  const nonNilEntries = row
    ? Object.entries(row).filter(([, v]) => v !== null && v !== undefined)
    : []

  return (
    <Drawer open={row !== null} onOpenChange={(open) => !open && onClose()}>
      <DrawerContent>
        <div className="mx-auto w-full max-w-2xl">
          <DrawerHeader>
            <DrawerTitle>Row Details</DrawerTitle>
          </DrawerHeader>
          <div className="p-4 max-h-[60vh] overflow-auto">
            <pre className="text-xs bg-muted/50 p-4 rounded-lg overflow-auto">
              {JSON.stringify(Object.fromEntries(nonNilEntries), null, 2)}
            </pre>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
