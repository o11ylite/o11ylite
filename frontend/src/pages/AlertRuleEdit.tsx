import { useState } from "react"
import { router, usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
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

  const pageLabel = isEditing ? `Edit: ${alertRule.name}` : "New Alert Rule"
  const breadcrumb = [
    { label: "Alert Rules", href: "/alert-rules" },
    { label: pageLabel },
  ]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="mx-auto max-w-4xl space-y-6">
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
