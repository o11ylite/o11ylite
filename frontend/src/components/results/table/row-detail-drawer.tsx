import {
  Drawer,
  DrawerContent,
  DrawerHeader,
  DrawerTitle,
} from "@/components/ui/drawer"

import type { RowData } from "./columns"

const COLLAPSE_THRESHOLD = 100

function JsonValue({ value }: { value: unknown }) {
  if (value === null) {
    return <span className="text-muted-foreground">null</span>
  }
  if (typeof value === "string") {
    return <span className="text-green-600 dark:text-green-400">"{value}"</span>
  }
  if (typeof value === "number") {
    return <span className="text-blue-600 dark:text-blue-400">{value}</span>
  }
  if (typeof value === "boolean") {
    return (
      <span className="text-purple-600 dark:text-purple-400">
        {String(value)}
      </span>
    )
  }
  // Fallback for arrays/objects
  return <span>{JSON.stringify(value)}</span>
}

function JsonEntry({
  fieldKey,
  value,
  isLast,
}: {
  fieldKey: string
  value: unknown
  isLast: boolean
}) {
  const stringified = typeof value === "string" ? value : JSON.stringify(value)
  const isLong = stringified.length > COLLAPSE_THRESHOLD
  const comma = isLast ? "" : ","

  const keyElement = (
    <span className="text-red-600 dark:text-red-400">"{fieldKey}"</span>
  )

  if (!isLong) {
    return (
      <div className="pl-4">
        {keyElement}
        <span>: </span>
        <JsonValue value={value} />
        {comma}
      </div>
    )
  }

  return (
    <details className="pl-4">
      <summary className="cursor-pointer list-none">
        {keyElement}
        <span>: </span>
        <span className="text-muted-foreground">
          ({stringified.length} chars)
        </span>
        {comma}
      </summary>
      <div className="pl-4">
        <JsonValue value={value} />
      </div>
    </details>
  )
}

export function RowDetailDrawer({
  row,
  onClose,
}: {
  row: RowData | null
  onClose: () => void
}) {
  const sortedEntries = row
    ? Object.entries(row)
        .filter(([, v]) => v !== null && v !== undefined)
        .sort(([a], [b]) => {
          const aIsAttr = a.startsWith("attr.")
          const bIsAttr = b.startsWith("attr.")
          if (aIsAttr !== bIsAttr) return aIsAttr ? 1 : -1
          return a.localeCompare(b)
        })
    : []

  return (
    <Drawer open={row !== null} onOpenChange={(open) => !open && onClose()}>
      <DrawerContent>
        <div className="mx-auto w-full max-w-2xl select-text">
          <DrawerHeader>
            <DrawerTitle>Row Details</DrawerTitle>
          </DrawerHeader>
          <div className="max-h-[60vh] overflow-auto p-4">
            <pre className="rounded-lg bg-muted/50 p-4 text-xs whitespace-pre-wrap break-all">
              {"{"}
              {sortedEntries.map(([key, value], index) => (
                <JsonEntry
                  key={key}
                  fieldKey={key}
                  value={value}
                  isLast={index === sortedEntries.length - 1}
                />
              ))}
              {"}"}
            </pre>
          </div>
        </div>
      </DrawerContent>
    </Drawer>
  )
}
