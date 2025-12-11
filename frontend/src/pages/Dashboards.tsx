import ApplicationLayout from "@/components/layouts/application-layout"

export default function Dashboards() {
  return (
    <ApplicationLayout title="Dashboards">
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Dashboards</h1>
        <p className="text-muted-foreground">
          View and manage your saved visualizations.
        </p>
      </div>
    </ApplicationLayout>
  )
}
