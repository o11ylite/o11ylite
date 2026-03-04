import { useState } from "react"
import { router, usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import type { ApiKeyScope } from "@/types"

const SCOPE_DESCRIPTIONS: Record<ApiKeyScope, string> = {
  ingest: "OTLP ingestion only (traces, logs, metrics)",
  read: "Query APIs (read-only access to data)",
  write: "Ingest + Read + entity mutations (alert rules, notebooks)",
  admin: "Full access including API key management",
}

export default function ApiKeyCreate() {
  const { errors } = usePage<{
    errors: Partial<Record<string, string>>
  }>().props

  const [submitting, setSubmitting] = useState(false)
  const [name, setName] = useState("")
  const [scope, setScope] = useState<ApiKeyScope>("ingest")

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    router.post(
      "/system/api-keys",
      { name, scope },
      {
        onBefore: () => setSubmitting(true),
        onFinish: () => setSubmitting(false),
      }
    )
  }

  const breadcrumb = [
    { label: "System" },
    { label: "API Keys", href: "/system/api-keys" },
    { label: "New" },
  ]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="mx-auto max-w-lg space-y-6">
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              placeholder="e.g. Production Ingest"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
            {errors.name && (
              <p className="text-sm text-destructive">{errors.name}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="scope">Scope</Label>
            <Select value={scope} onValueChange={(v) => setScope(v as ApiKeyScope)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {(Object.keys(SCOPE_DESCRIPTIONS) as ApiKeyScope[]).map((s) => (
                  <SelectItem key={s} value={s}>
                    {s}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-sm text-muted-foreground">
              {SCOPE_DESCRIPTIONS[scope]}
            </p>
            {errors.scope && (
              <p className="text-sm text-destructive">{errors.scope}</p>
            )}
          </div>

          <div className="flex justify-end gap-2 pt-4">
            <Button
              type="button"
              variant="outline"
              onClick={() => window.history.back()}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={submitting}>
              {submitting ? "Creating..." : "Create API Key"}
            </Button>
          </div>
        </form>
      </div>
    </ApplicationLayout>
  )
}
