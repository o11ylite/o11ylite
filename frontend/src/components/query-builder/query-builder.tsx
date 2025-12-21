import { useState } from "react"
import { Play, Table, LineChart, Grid3X3 } from "lucide-react"

import { Button } from "@/components/ui/button"
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { type Field } from "@/types"
import { FiltersSection, type Filter } from "./filters-section"
import { AggregationSection, type Aggregation } from "./aggregation-section"

type SignalType = "events" | "metrics"
type VisualizationType = "table" | "timeseries" | "heatmap"

export function QueryBuilder({ fields }: { fields: Field[] }) {
  const [signalType, setSignalType] = useState<SignalType>("events")
  const [filters, setFilters] = useState<Filter[]>([
    { field: "service", op: "=", value: "api-gateway" },
  ])
  const [aggregation, setAggregation] = useState<Aggregation | null>(null)
  const [vizType, setVizType] = useState<VisualizationType>("table")

  return (
    <div className="space-y-2">
      {/* Top Bar */}
      <div className="flex items-center gap-2">
        <Tabs
          value={signalType}
          onValueChange={(v) => setSignalType(v as SignalType)}
        >
          <TabsList>
            <TabsTrigger value="events">Events</TabsTrigger>
            <TabsTrigger value="metrics">Metrics</TabsTrigger>
          </TabsList>
        </Tabs>

        <Select defaultValue="all">
          <SelectTrigger size="sm" className="w-auto min-w-[120px]">
            <SelectValue placeholder="All services" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All services</SelectItem>
            <SelectItem value="api-gateway">api-gateway</SelectItem>
            <SelectItem value="user-service">user-service</SelectItem>
          </SelectContent>
        </Select>

        <div className="flex-1" />

        <Tabs
          value={vizType}
          onValueChange={(v) => setVizType(v as VisualizationType)}
        >
          <TabsList>
            <TabsTrigger value="table">
              <Table size={14} />
            </TabsTrigger>
            <TabsTrigger value="timeseries">
              <LineChart size={14} />
            </TabsTrigger>
            <TabsTrigger value="heatmap">
              <Grid3X3 size={14} />
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <Button size="sm" className="gap-1.5">
          <Play size={12} />
          Run
        </Button>
      </div>

      {/* Filters */}
      <FiltersSection
        filters={filters}
        fields={fields}
        onFiltersChange={setFilters}
      />

      {/* Aggregation */}
      <AggregationSection
        aggregation={aggregation}
        fields={fields}
        onAggregationChange={setAggregation}
      />
    </div>
  )
}
