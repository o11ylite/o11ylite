import { useState } from "react"
import { Link, router, usePage } from "@inertiajs/react"
import { ArrowLeft } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"
import { AlertRuleForm } from "@/components/alert-rules/alert-rule-form"
import {
  DEFAULT_QUERY_STATE,
  queryStateFromRule,
} from "@/components/alert-rules/query-helpers"
import type { AlertRule } from "@/types"

export default function AlertRuleEdit() {
  const { alertRule, errors } = usePage<{
    alertRule: AlertRule | null
    errors: Partial<Record<string, string>>
  }>().props

  const [submitting, setSubmitting] = useState(false)

  const isEditing = alertRule !== null
  const initialQueryState = alertRule
    ? queryStateFromRule(alertRule)
    : DEFAULT_QUERY_STATE

  const initialValues = {
    name: alertRule?.name ?? "",
    description: alertRule?.description ?? "",
    enabled: alertRule?.enabled ?? true,
    queryState: initialQueryState,
    evalWindowMs: alertRule?.evalWindowMs ?? 300000,
    evalIntervalMs: alertRule?.evalIntervalMs ?? 60000,
  }

  const handleSubmit: React.ComponentProps<typeof AlertRuleForm>["onSubmit"] = (data) => {
    const payload = { ...data }
    const options = {
      onBefore: () => setSubmitting(true),
      onFinish: () => setSubmitting(false),
    }
    if (isEditing) {
      router.put(`/alert-rules/${alertRule.id}`, payload, options)
    } else {
      router.post("/alert-rules", payload, options)
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
          initialValues={initialValues}
          errors={errors}
          submitting={submitting}
          submitLabel={isEditing ? "Update Rule" : "Create Rule"}
          onSubmit={handleSubmit}
        />
      </div>
    </ApplicationLayout>
  )
}
