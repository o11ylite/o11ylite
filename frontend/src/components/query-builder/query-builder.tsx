import { useState, useEffect } from "react"
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
import {
  type Field,
  type Service,
  type QueryBuilderState,
  type Visualization,
  type VisualizationType,
} from "@/types"
import { FiltersSection } from "./filters-section"
import { AggregationSection } from "./aggregation-section"

export function QueryBuilder({
  fields,
  services,
  initialState,
  onSubmit,
}: {
  fields: Field[]
  services: Service[]
  initialState: QueryBuilderState
  onSubmit: (state: QueryBuilderState) => void
}) {
  // Local state for editing - only synced to URL on submit
  const [state, setState] = useState(initialState)

  // Sync local state when URL state changes (e.g., browser back/forward)
  useEffect(() => {
    setState(initialState)
  }, [initialState])

  const vizType = state.visualization.type

  const handleVizTypeChange = (type: VisualizationType) => {
    let visualization: Visualization
    switch (type) {
      case "table":
        visualization = { type: "table", limit: 100 }
        break
      case "time_series":
        visualization = { type: "time_series" }
        break
      case "heatmap":
        visualization = { type: "heatmap" }
        break
      case "trace":
        visualization = { type: "trace" }
        break
    }
    setState({ ...state, visualization })
  }

  const handleRun = () => {
    onSubmit(state)
  }

  return (
    <div className="space-y-2">
      {/* Top Bar */}
      <div className="flex items-center gap-2">
        <Tabs value="events">
          <TabsList>
            <TabsTrigger value="events">Events</TabsTrigger>
            <TabsTrigger value="metrics" disabled>
              Metrics
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <Select defaultValue="all">
          <SelectTrigger size="sm" className="w-auto min-w-[120px]">
            <SelectValue placeholder="All services" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All services</SelectItem>
            {services.map((service) => (
              <SelectItem key={service.name} value={service.name}>
                {service.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <div className="flex-1" />

        <Tabs value={vizType} onValueChange={(v) => handleVizTypeChange(v as VisualizationType)}>
          <TabsList>
            <TabsTrigger value="table" title="Table">
              <Table size={14} />
            </TabsTrigger>
            <TabsTrigger value="time_series" title="Time series">
              <LineChart size={14} />
            </TabsTrigger>
            <TabsTrigger value="heatmap" title="Heatmap">
              <Grid3X3 size={14} />
            </TabsTrigger>
          </TabsList>
        </Tabs>

        <Button size="sm" className="gap-1.5" onClick={handleRun}>
          <Play size={12} />
          Run
        </Button>
      </div>

      {/* Filters */}
      <FiltersSection
        filters={state.filters}
        fields={fields}
        onFiltersChange={(filters) => setState({ ...state, filters })}
      />

      {/* Aggregation */}
      <AggregationSection
        aggregations={state.aggregations}
        groupBy={state.groupBy}
        fields={fields}
        onAggregationsChange={(aggregations) =>
          setState({ ...state, aggregations })
        }
        onGroupByChange={(groupBy) => setState({ ...state, groupBy })}
      />
    </div>
  )
}
