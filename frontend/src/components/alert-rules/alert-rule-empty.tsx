import { Link } from "@inertiajs/react"
import { AlertTriangle, Plus } from "lucide-react"

import { Button } from "@/components/ui/button"

export function AlertRuleEmpty() {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed py-16">
      <AlertTriangle className="mb-4 text-muted-foreground" size={40} />
      <p className="mb-2 text-lg font-medium">No alert rules yet</p>
      <p className="mb-4 text-sm text-muted-foreground">
        Create your first alert rule to start monitoring.
      </p>
      <Button asChild>
        <Link href="/alert-rules/new">
          <Plus className="mr-2" size={16} />
          New Rule
        </Link>
      </Button>
    </div>
  )
}
