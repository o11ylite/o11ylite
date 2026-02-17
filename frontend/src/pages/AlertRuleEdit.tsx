import { useState } from "react"
import { router, usePage } from "@inertiajs/react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { AlertRuleForm } from "@/components/alert-rules/alert-rule-form"
import {
  DEFAULT_QUERY_STATE,
  queryStateFromEntity,
} from "@/lib/query-helpers"
import type { AlertRule } from "@/types"

export default function AlertRuleEdit() {
  const { alert_rule, errors } = usePage<{
    alert_rule: AlertRule | null
    errors: Partial<Record<string, string>>
  }>().props

  const [submitting, setSubmitting] = useState(false)

  const isEditing = alert_rule !== null
  const initialQueryState = alert_rule
    ? queryStateFromEntity(alert_rule)
    : DEFAULT_QUERY_STATE

  const initialValues = {
    name: alert_rule?.name ?? "",
    description: alert_rule?.description ?? "",
    enabled: alert_rule?.enabled ?? true,
    queryState: initialQueryState,
    evalWindowMs: alert_rule?.eval_window_ms ?? 300000,
    evalIntervalMs: alert_rule?.eval_interval_ms ?? 60000,
  }

  const handleSubmit: React.ComponentProps<typeof AlertRuleForm>["onSubmit"] = (data) => {
    const payload = { ...data }
    const options = {
      onBefore: () => setSubmitting(true),
      onFinish: () => setSubmitting(false),
    }
    if (isEditing) {
      router.put(`/alert-rules/${alert_rule.id}`, payload, options)
    } else {
      router.post("/alert-rules", payload, options)
    }
  }

  const pageLabel = isEditing ? `Edit: ${alert_rule.name}` : "New Alert Rule"
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
