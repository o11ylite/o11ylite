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
        <div className="space-y-4">
          <div>
            <h1 className="text-2xl font-bold">Alert Rules</h1>
            <p className="text-sm text-muted-foreground">
              Define alert conditions for your telemetry data.
            </p>
          </div>
          <AlertRuleEmpty />
        </div>
      </ApplicationLayout>
    )
  }

  return (
    <ApplicationLayout title="Alert Rules">
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold">Alert Rules</h1>
            <p className="text-sm text-muted-foreground">
              Define alert conditions for your telemetry data.
            </p>
          </div>
          <Button asChild>
            <Link href="/alert-rules/new">
              <Plus className="mr-2" size={16} />
              New Rule
            </Link>
          </Button>
        </div>

        <AlertRuleList alertRules={alertRules} />
      </div>
    </ApplicationLayout>
  )
}
