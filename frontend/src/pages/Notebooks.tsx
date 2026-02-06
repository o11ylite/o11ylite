import ApplicationLayout from "@/components/layouts/application-layout"

export default function Notebooks() {
  return (
    <ApplicationLayout title="Notebooks">
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Notebooks</h1>
        <p className="text-muted-foreground">
          Multi-query documentation for saved investigations.
        </p>
      </div>
    </ApplicationLayout>
  )
}
