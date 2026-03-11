import { router, usePage } from "@inertiajs/react"
import { Play } from "lucide-react"

import ApplicationLayout from "@/components/layouts/application-layout"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import {
  formatDateTime,
  formatInterval,
  formatRelativeTime,
  useNow,
} from "@/lib/datetime"
import type { ScheduledJob } from "@/types"

function formatJobName(name: string) {
  return name
    .replace(/-/g, " ")
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

const statusConfig = {
  disabled: { label: "Disabled", className: "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400" },
  error:    { label: "Error",    className: "bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-200" },
  healthy:  { label: "Healthy",  className: "bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200" },
  pending:  { label: "Pending",  className: "bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200" },
} as const

function jobStatus(job: ScheduledJob): keyof typeof statusConfig {
  if (!job.enabled) return "disabled"
  if (job.last_error) return "error"
  if (job.last_success_at) return "healthy"
  return "pending"
}

export default function ScheduledJobs() {
  const { jobs, flash } = usePage<{
    jobs: ScheduledJob[]
    flash: { message?: string }
  }>().props
  const now = useNow(10_000)

  const breadcrumb = [
    { label: "System" },
    { label: "Scheduled Jobs" },
  ]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="space-y-4">
        <p className="text-sm text-muted-foreground">
          Background jobs that run on a schedule to maintain data and evaluate alerts.
        </p>

        {flash?.message && (
          <div className="rounded-md bg-muted px-4 py-3 text-sm">
            {flash.message}
          </div>
        )}

        {jobs.length === 0 ? (
          <div className="rounded-lg border border-dashed p-8 text-center text-muted-foreground">
            <p className="text-lg font-medium">No scheduled jobs</p>
            <p className="mt-1 text-sm">Jobs will appear here once the scheduler starts.</p>
          </div>
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Job</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Interval</TableHead>
                <TableHead>Last Run</TableHead>
                <TableHead>Next Run</TableHead>
                <TableHead>Last Error</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {jobs.map((job) => {
                const status = statusConfig[jobStatus(job)]
                return (
                  <TableRow key={job.job_name}>
                    <TableCell>
                      <div className="flex items-center gap-1">
                        <span className="font-medium">{formatJobName(job.job_name)}</span>
                        <Button
                          variant="ghost"
                          size="icon"
                          className="h-6 w-6 shrink-0"
                          title="Trigger job now"
                          onClick={() => router.post(`/system/jobs/${job.job_name}/trigger`)}
                        >
                          <Play size={14} />
                        </Button>
                      </div>
                      {job.description && (
                        <p className="text-xs text-muted-foreground mt-0.5">{job.description}</p>
                      )}
                    </TableCell>
                    <TableCell>
                      <Badge variant="outline" className={status.className}>
                        {status.label}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground">
                      {formatInterval(job.interval_ms)}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground" title={formatDateTime(job.last_run_at)}>
                      {formatRelativeTime(job.last_run_at, now)}
                    </TableCell>
                    <TableCell className="text-sm text-muted-foreground" title={formatDateTime(job.next_run_at)}>
                      {formatRelativeTime(job.next_run_at, now)}
                    </TableCell>
                    <TableCell className="max-w-xs text-sm">
                      {job.last_error ? (
                        <span className="text-red-600 dark:text-red-400 truncate block" title={job.last_error}>
                          {job.last_error}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">--</span>
                      )}
                    </TableCell>

                  </TableRow>
                )
              })}
            </TableBody>
          </Table>
        )}
      </div>
    </ApplicationLayout>
  )
}
