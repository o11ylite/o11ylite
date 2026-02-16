import { useState } from "react"
import { Link, router, usePage } from "@inertiajs/react"
import { Save } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import type { Notebook } from "@/types"

export default function NotebookEdit() {
  const { notebook, errors } = usePage<{
    notebook: Notebook | null
    errors: Partial<Record<string, string>>
  }>().props

  const isEditing = notebook !== null
  const [submitting, setSubmitting] = useState(false)

  const [name, setName] = useState(notebook?.name ?? "")
  const [description, setDescription] = useState(notebook?.description ?? "")
  const [globalFrom, setGlobalFrom] = useState(notebook?.globalFrom ?? "now-1h")
  const [globalTo, setGlobalTo] = useState(notebook?.globalTo ?? "now")

  const handleSubmit = () => {
    const payload = {
      name,
      description: description || null,
      global_from: globalFrom,
      global_to: globalTo,
    }
    const options = {
      onBefore: () => setSubmitting(true),
      onFinish: () => setSubmitting(false),
    }
    if (isEditing) {
      router.put(`/notebooks/${notebook.id}`, payload, options)
    } else {
      router.post("/notebooks", payload, options)
    }
  }

  const pageLabel = isEditing ? `Edit: ${notebook.name}` : "New Notebook"
  const breadcrumb = [
    { label: "Notebooks", href: "/notebooks" },
    { label: pageLabel },
  ]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="mx-auto max-w-2xl space-y-6">
        <div className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="name">Name</Label>
            <Input
              id="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="e.g., Latency Investigation"
              aria-invalid={!!errors.name}
            />
            {errors.name && (
              <p className="text-xs text-destructive">{errors.name}</p>
            )}
          </div>

          <div className="space-y-2">
            <Label htmlFor="description">Description</Label>
            <Input
              id="description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Optional description"
            />
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div className="space-y-2">
              <Label htmlFor="global-from">Default Time From</Label>
              <Input
                id="global-from"
                value={globalFrom}
                onChange={(e) => setGlobalFrom(e.target.value)}
                placeholder="now-1h"
              />
              <p className="text-xs text-muted-foreground">
                Relative (now-1h) or absolute time
              </p>
            </div>
            <div className="space-y-2">
              <Label htmlFor="global-to">Default Time To</Label>
              <Input
                id="global-to"
                value={globalTo}
                onChange={(e) => setGlobalTo(e.target.value)}
                placeholder="now"
              />
            </div>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <Button onClick={handleSubmit} disabled={submitting || !name.trim()}>
            <Save className="mr-2" size={16} />
            {isEditing ? "Update Notebook" : "Create Notebook"}
          </Button>
          <Button variant="outline" asChild>
            <Link href={isEditing ? `/notebooks/${notebook.id}` : "/notebooks"}>
              Cancel
            </Link>
          </Button>
        </div>
      </div>
    </ApplicationLayout>
  )
}
