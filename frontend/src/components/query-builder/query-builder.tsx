import { useState, useEffect } from "react"
import { Play, Table, LineChart } from "lucide-react"

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
  type QueryMode,
  type Visualization,
  type VisualizationType,
} from "@/types"
import { FiltersSection } from "./filters-section"
import { AggregationSection } from "./aggregation-section"
import { MetricsSection } from "./metrics-section"
import { MetricGroupBySection } from "./metric-group-by-section"
import { LimitSelector } from "./limit-selector"

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

  const mode = state.mode ?? "events"
  const vizType = state.visualization.type

  const handleModeChange = (newMode: QueryMode) => {
    setState({ ...state, mode: newMode })
  }

  const handleVizTypeChange = (type: VisualizationType) => {
    let visualization: Visualization
    switch (type) {
      case "table":
        visualization = { type: "table" }
        break
      case "time_series":
        visualization = { type: "time_series" }
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
        <Tabs value={mode} onValueChange={(v) => handleModeChange(v as QueryMode)}>
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
            {services.map((service) => (
              <SelectItem key={service.name} value={service.name}>
                {service.name}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>

        <div className="flex-1" />

        {/* Visualization toggle - only show for events mode */}
        {mode === "events" && (
          <Tabs value={vizType} onValueChange={(v) => handleVizTypeChange(v as VisualizationType)}>
            <TabsList>
              <TabsTrigger value="table" title="Table">
                <Table size={14} />
              </TabsTrigger>
              <TabsTrigger value="time_series" title="Time series">
                <LineChart size={14} />
              </TabsTrigger>
            </TabsList>
          </Tabs>
        )}

        {/* Limit selector - for events mode */}
        {mode === "events" && (
          <LimitSelector
            value={state.limit ?? 100}
            onChange={(limit) => setState({ ...state, limit })}
          />
        )}

        <Button size="sm" className="gap-1.5" onClick={handleRun}>
          <Play size={12} />
          Run
        </Button>
      </div>

      {/* Events Mode */}
      {mode === "events" && (
        <>
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
        </>
      )}

      {/* Metrics Mode */}
      {mode === "metrics" && (
        <>
          {/* Metrics */}
          <MetricsSection
            metrics={state.metrics ?? []}
            onMetricsChange={(metrics) => setState({ ...state, metrics })}
          />

          {/* Filters (global, apply to all metrics) */}
          <FiltersSection
            filters={state.filters}
            fields={fields}
            onFiltersChange={(filters) => setState({ ...state, filters })}
          />

          {/* Group By (uses first selected metric's attributes) */}
          <MetricGroupBySection
            metrics={state.metrics ?? []}
            groupBy={state.groupBy}
            onChange={(groupBy) => setState({ ...state, groupBy })}
          />

          {/* TODO: Formula section for metric arithmetic (deferred) */}
          {/* e.g., A / B * 100 */}
        </>
      )}
    </div>
  )
}
