import { useState } from "react"
import { Link, router, usePage } from "@inertiajs/react"
import { ArrowLeft } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"
import { AlertRuleForm } from "@/components/alert-rules/alert-rule-form"
import type { AlertRule, Field, Service } from "@/types"

export default function AlertRuleEdit() {
  const { alertRule, fields, services } = usePage<{
    alertRule: AlertRule | null
    fields: Field[]
    services: Service[]
  }>().props

  const isEditing = alertRule !== null
  const [submitting, setSubmitting] = useState(false)

  const handleSubmit = (data: Record<string, string | number | boolean | null>) => {
    setSubmitting(true)
    const options = { onFinish: () => setSubmitting(false) }

    if (isEditing) {
      router.put(`/alert-rules/${alertRule.id}`, data, options)
    } else {
      router.post("/alert-rules", data, options)
    }
  }

  const title = isEditing ? `Edit: ${alertRule.name}` : "New Alert Rule"

  return (
    <ApplicationLayout title={title}>
      <div className="mx-auto max-w-4xl space-y-6">
        <div className="flex items-center gap-4">
          <Button variant="ghost" size="icon" asChild>
            <Link href="/alert-rules">
              <ArrowLeft size={18} />
            </Link>
          </Button>
          <h1 className="text-2xl font-bold">{title}</h1>
        </div>

        <AlertRuleForm
          alertRule={alertRule}
          fields={fields}
          services={services}
          onSubmit={handleSubmit}
          submitting={submitting}
        />
      </div>
    </ApplicationLayout>
  )
}
