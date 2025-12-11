import ApplicationLayout from "@/components/layouts/application-layout"

export default function MonitorNotifications() {
  return (
    <ApplicationLayout title="Notifications">
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Notifications</h1>
        <p className="text-muted-foreground">
          Configure notification channels and routing rules.
        </p>
      </div>
    </ApplicationLayout>
  )
}
