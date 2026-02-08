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
  type SimpleFilter,
  type Aggregation,
} from "@/types"
import { FiltersSection } from "./filters-section"
import { AggregationSection } from "./aggregation-section"
import { MetricsSection } from "./metrics-section"
import { MetricGroupBySection } from "./metric-group-by-section"
import { MetricFiltersSection } from "./metric-filters-section"
import { LimitSelector } from "./limit-selector"
import { HavingSection } from "./having-section"

const isFilterComplete = (f: SimpleFilter) => f.field !== "" && f.value !== ""

const isAggregationComplete = (a: Aggregation) =>
  a.function === "count" || (a.field !== "" && a.field !== "*")

const isStateComplete = (state: QueryBuilderState) =>
  state.filters.every(isFilterComplete) &&
  state.aggregations.every(isAggregationComplete)

export function QueryBuilder({
  fields,
  services,
  initialState,
  onSubmit,
  autoSubmit = true,
  alertRuleMode = false,
}: {
  fields: Field[]
  services: Service[]
  initialState: QueryBuilderState
  onSubmit: (state: QueryBuilderState) => void,
  autoSubmit?: boolean,
  alertRuleMode?: boolean,
}) {
  const [state, setState] = useState(initialState)

  useEffect(() => {
    setState(initialState)
  }, [initialState])

  // Auto-submit only when state is complete (no incomplete filters/aggregations)
  const updateState = (newState: QueryBuilderState) => {
    setState(newState)
    if (autoSubmit && isStateComplete(newState)) {
      onSubmit(newState)
    }
  }

  const mode = state.mode ?? "events"
  const vizType = state.visualization.type

  // Compute available refs for having (aggregations or metrics)
  const availableRefs =
    mode === "events"
      ? state.aggregations.map((a) => ({
          id: a.id,
          label: `${a.function}(${a.field})`,
        }))
      : state.metrics.map((m) => ({
          id: m.id,
          label: `${m.agg}(${m.name})`,
        }))

  const handleModeChange = (newMode: QueryMode) => {
    updateState({
      ...state,
      mode: newMode,
      filters: [],
      aggregations: [],
      groupBy: [],
      metrics: [],
      having: undefined,
    })
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
    updateState({ ...state, visualization })
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
            onChange={(limit) => updateState({ ...state, limit })}
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
            onFiltersChange={(filters) => updateState({ ...state, filters })}
          />

          {/* Aggregation */}
          <AggregationSection
            aggregations={state.aggregations}
            groupBy={state.groupBy}
            fields={fields}
            onAggregationsChange={(aggregations) =>
              updateState({ ...state, aggregations, having: undefined })
            }
            onGroupByChange={(groupBy) => updateState({ ...state, groupBy })}
          />

          {/* Having - only when aggregations exist */}
          {state.aggregations.length > 0 && (
            <HavingSection
              having={state.having}
              refs={availableRefs}
              onChange={(having) => updateState({ ...state, having })}
            />
          )}
        </>
      )}

      {/* Metrics Mode */}
      {mode === "metrics" && (
        <>
          {/* Metrics */}
          <MetricsSection
            metrics={state.metrics ?? []}
            onMetricsChange={(metrics) =>
              updateState({ ...state, metrics, having: undefined })
            }
          />

          {/* Filters - shows hint when no metric selected, then metric attributes */}
          <MetricFiltersSection
            filters={state.filters}
            onFiltersChange={(filters) => updateState({ ...state, filters })}
            metrics={state.metrics ?? []}
          />

          {/* Group By (uses first selected metric's attributes) */}
          <MetricGroupBySection
            metrics={state.metrics ?? []}
            groupBy={state.groupBy}
            onChange={(groupBy) => updateState({ ...state, groupBy })}
          />

          {/* Having - only in alert rule mode */}
          {alertRuleMode && state.metrics.length > 0 && (
            <HavingSection
              having={state.having}
              refs={availableRefs}
              onChange={(having) => updateState({ ...state, having })}
            />
          )}
        </>
      )}
    </div>
  )
}
