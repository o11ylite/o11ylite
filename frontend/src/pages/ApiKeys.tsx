import { useState } from "react"
import { Link, router, usePage } from "@inertiajs/react"
import { Copy, Check, Plus, Trash2 } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatDateTime } from "@/lib/datetime"
import type { ApiKey } from "@/types"

const scopeColors: Record<string, string> = {
  ingest: "bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200",
  read: "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200",
  write: "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200",
  admin: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200",
}

function CreatedKeyDialog({
  keyValue,
  keyName,
  onClose,
}: {
  keyValue: string
  keyName: string
  onClose: () => void
}) {
  const [copied, setCopied] = useState(false)

  const handleCopy = () => {
    void navigator.clipboard.writeText(keyValue).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <Dialog open onOpenChange={onClose}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>API Key Created</DialogTitle>
          <DialogDescription>
            Copy the key for <strong>{keyName}</strong> now. It will not be shown again.
          </DialogDescription>
        </DialogHeader>
        <div className="flex items-center gap-2">
          <code className="flex-1 rounded-md bg-muted px-3 py-2 text-sm font-mono break-all">
            {keyValue}
          </code>
          <Button variant="outline" size="icon" onClick={handleCopy}>
            {copied ? <Check size={16} /> : <Copy size={16} />}
          </Button>
        </div>
        <DialogFooter>
          <Button onClick={onClose}>Done</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function ApiKeyTable({
  apiKeys,
  onDelete,
}: {
  apiKeys: ApiKey[]
  onDelete: (id: string) => void
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Name</TableHead>
          <TableHead>Key Prefix</TableHead>
          <TableHead>Scope</TableHead>
          <TableHead>Created</TableHead>
          <TableHead>Last Used</TableHead>
          <TableHead className="w-12" />
        </TableRow>
      </TableHeader>
      <TableBody>
        {apiKeys.map((key) => (
          <TableRow key={key.id}>
            <TableCell className="font-medium">{key.name}</TableCell>
            <TableCell>
              <code className="text-xs">{key.prefix}...</code>
            </TableCell>
            <TableCell>
              <Badge variant="outline" className={scopeColors[key.scope] ?? ""}>
                {key.scope}
              </Badge>
            </TableCell>
            <TableCell className="text-muted-foreground text-sm">
              {formatDateTime(key.created_at)}
            </TableCell>
            <TableCell className="text-muted-foreground text-sm">
              {formatDateTime(key.last_used_at)}
            </TableCell>
            <TableCell>
              <Button
                variant="ghost"
                size="icon"
                onClick={() => onDelete(key.id)}
              >
                <Trash2 size={16} />
              </Button>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

export default function ApiKeys() {
  const { api_keys, flash } = usePage<{
    api_keys: ApiKey[]
    flash: {
      "created-key"?: string
      "created-key-name"?: string
    }
  }>().props

  const [showCreatedKey, setShowCreatedKey] = useState(!!flash?.["created-key"])

  const handleDelete = (id: string) => {
    if (confirm("Delete this API key? This cannot be undone.")) {
      router.delete(`/system/api-keys/${id}`)
    }
  }

  const breadcrumb = [
    { label: "System" },
    { label: "API Keys" },
  ]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">
            API keys authenticate OTLP ingestion and programmatic API access.
            Creating the first key enables token authentication for these endpoints.
          </p>
          <Button render={<Link href="/system/api-keys/new" />}>
            <Plus className="mr-2" size={16} />
            New API Key
          </Button>
        </div>

        {api_keys.length === 0 ? (
          <div className="rounded-lg border border-dashed p-8 text-center text-muted-foreground">
            <p className="text-lg font-medium">No API keys</p>
            <p className="mt-1 text-sm">
              OTLP ingestion is currently open. Create an API key to require token authentication.
            </p>
          </div>
        ) : (
          <ApiKeyTable apiKeys={api_keys} onDelete={handleDelete} />
        )}
      </div>

      {showCreatedKey && flash?.["created-key"] && (
        <CreatedKeyDialog
          keyValue={flash["created-key"]}
          keyName={flash["created-key-name"] ?? "API Key"}
          onClose={() => setShowCreatedKey(false)}
        />
      )}
    </ApplicationLayout>
  )
}
