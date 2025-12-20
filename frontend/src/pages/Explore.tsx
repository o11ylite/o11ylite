import ApplicationLayout from "@/components/layouts/application-layout"
import { useTimeRange } from "@/hooks/use-time-range"

export default function Explore() {
  const { from } = useTimeRange()
  const rightPanel = (
    <>
      Hello
    </>
  )
  return (
    <ApplicationLayout title="Explore" showTimeRange rightPanel={rightPanel}>
      <div className="space-y-4">
        <h1 className="text-2xl font-bold">Explore</h1>
        <p className="text-muted-foreground">
          Query and explore your traces, logs, and metrics.
        </p>

        <p> from: {from} </p>
      </div>
    </ApplicationLayout>
  )
}
