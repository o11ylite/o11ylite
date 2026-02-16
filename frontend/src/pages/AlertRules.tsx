import { Link, usePage } from "@inertiajs/react"
import { Plus } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Button } from "@/components/ui/button"
import { AlertRuleEmpty } from "@/components/alert-rules/alert-rule-empty"
import { AlertRuleList } from "@/components/alert-rules/alert-rule-list"
import type { AlertRule } from "@/types"

export default function AlertRules() {
  const { alertRules } = usePage<{
    alertRules: AlertRule[]
  }>().props

  if (alertRules.length === 0) {
    return (
      <ApplicationLayout title="Alert Rules">
        <AlertRuleEmpty />
      </ApplicationLayout>
    )
  }

  return (
    <ApplicationLayout title="Alert Rules">
      <div className="space-y-4">
        <Button asChild>
          <Link href="/alert-rules/new">
            <Plus className="mr-2" size={16} />
            New Rule
          </Link>
        </Button>
        <AlertRuleList alertRules={alertRules} />
      </div>
    </ApplicationLayout>
  )
}
