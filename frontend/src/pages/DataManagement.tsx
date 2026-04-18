import { useEffect } from "react"
import { usePage } from "@inertiajs/react"
import { toast } from "sonner"

import { EventFieldsTab } from "@/components/data-management/event-fields-tab"
import { MetricAttributesTab } from "@/components/data-management/metric-attributes-tab"
import { MetricsListTab } from "@/components/data-management/metrics-list-tab"
import { ServicesTab } from "@/components/data-management/services-tab"
import ApplicationLayout from "@/components/layouts/application-layout"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import type {
  ManagedField,
  ManagedMetric,
  ManagedMetricAttribute,
  ManagedService,
} from "@/types"

export default function DataManagement() {
  const props = usePage<{
    event_fields: ManagedField[]
    metrics: ManagedMetric[]
    metric_attributes: ManagedMetricAttribute[]
    services: ManagedService[]
    flash: { message?: string }
  }>().props

  const event_fields = props.event_fields ?? []
  const metrics = props.metrics ?? []
  const metric_attributes = props.metric_attributes ?? []
  const services = props.services ?? []
  const flash = props.flash

  useEffect(() => {
    if (flash?.message) {
      toast.success(flash.message)
    }
  }, [flash?.message])

  const breadcrumb = [
    { label: "System" },
    { label: "Data Management" },
  ]

  return (
    <ApplicationLayout title={breadcrumb}>
      <div className="space-y-4">
        <p className="text-sm text-muted-foreground">
          View and manage event fields, metrics, metric attributes, and services.
          Block fields to hide them from the UI and skip them during ingestion.
          Deleting a field drops its column and auto-blocks re-creation.
        </p>

        <Tabs defaultValue="events">
          <TabsList>
            <TabsTrigger value="events">Events ({event_fields.length})</TabsTrigger>
            <TabsTrigger value="metrics">Metrics</TabsTrigger>
            <TabsTrigger value="services">Services ({services.length})</TabsTrigger>
          </TabsList>

          <TabsContent value="events">
            <EventFieldsTab fields={event_fields} />
          </TabsContent>

          <TabsContent value="metrics">
            <Tabs defaultValue="catalog">
              <TabsList>
                <TabsTrigger value="catalog">Metrics ({metrics.length})</TabsTrigger>
                <TabsTrigger value="attributes">Metric Attributes ({metric_attributes.length})</TabsTrigger>
              </TabsList>

              <TabsContent value="catalog">
                <MetricsListTab metrics={metrics} />
              </TabsContent>

              <TabsContent value="attributes">
                <MetricAttributesTab attributes={metric_attributes} />
              </TabsContent>
            </Tabs>
          </TabsContent>

          <TabsContent value="services">
            <ServicesTab services={services} />
          </TabsContent>
        </Tabs>
      </div>
    </ApplicationLayout>
  )
}
