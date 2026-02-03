import ApplicationLayout from "@/components/layouts/application-layout"

export default function AlertRules() {
  return (
    <ApplicationLayout title="Alert Rules">
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Alert Rules</h1>
        <p className="text-muted-foreground">
          Define alert conditions for your telemetry data.
        </p>
      </div>
    </ApplicationLayout>
  )
}
