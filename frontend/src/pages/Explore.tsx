import ApplicationLayout from "@/components/layouts/application-layout"

export default function Explore() {
  return (
    <ApplicationLayout title="Explore">
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Explore</h1>
        <p className="text-muted-foreground">
          Query and explore your traces, logs, and metrics.
        </p>
      </div>
    </ApplicationLayout>
  )
}
