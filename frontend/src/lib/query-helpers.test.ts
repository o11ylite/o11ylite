import { describe, it, expect } from "vitest"
import { queryStateFromEntity, queryStateToPayload } from "./query-helpers"

describe("queryStateFromEntity", () => {
  it("defaults missing visualization to time_series for metrics mode", () => {
    const state = queryStateFromEntity({
      query_mode: "metrics",
      query: {
        metrics: [{ id: "A", name: "x", agg: "last" }],
        group_by: ["attr.tier"],
      },
    })

    expect(state.visualization).toEqual({ type: "time_series" })
  })

  it("preserves extra fields on a valid metrics time_series visualization", () => {
    const state = queryStateFromEntity({
      query_mode: "metrics",
      query: {
        metrics: [{ id: "A", name: "x", agg: "last" }],
        visualization: { type: "time_series", bucket_ms: 60000, overlay: true },
      },
    })

    expect(state.visualization).toEqual({
      type: "time_series",
      bucket_ms: 60000,
      overlay: true,
    })
  })

  it("forces time_series for metrics mode even if stored visualization is table", () => {
    const state = queryStateFromEntity({
      query_mode: "metrics",
      query: {
        metrics: [{ id: "A", name: "x", agg: "last" }],
        visualization: { type: "table" },
      },
    })

    expect(state.visualization).toEqual({ type: "time_series" })
  })

  it("defaults missing visualization to table for events mode", () => {
    const state = queryStateFromEntity({
      query_mode: "events",
      query: {},
    })

    expect(state.visualization).toEqual({ type: "table" })
  })

  it("preserves explicit visualization for events mode", () => {
    const state = queryStateFromEntity({
      query_mode: "events",
      query: { visualization: { type: "time_series" } },
    })

    expect(state.visualization).toEqual({ type: "time_series" })
  })
})

describe("queryStateToPayload", () => {
  it("coerces metrics-mode payload visualization to time_series", () => {
    const payload = queryStateToPayload({
      mode: "metrics",
      filters: [],
      aggregations: [],
      groupBy: [],
      metrics: [{ id: "A", name: "x", agg: "last" }],
      formulas: [],
      visualization: { type: "table" },
    })

    expect(payload.visualization).toEqual({ type: "time_series" })
  })

  it("preserves events-mode visualization in payload", () => {
    const payload = queryStateToPayload({
      mode: "events",
      filters: [],
      aggregations: [{ id: "A", field: "*", function: "count" }],
      groupBy: [],
      metrics: [],
      formulas: [],
      visualization: { type: "table" },
    })

    expect(payload.visualization).toEqual({ type: "table" })
  })

  it("persists formulas in metrics-mode payload (so notebook cells round-trip)", () => {
    const payload = queryStateToPayload({
      mode: "metrics",
      filters: [],
      aggregations: [],
      groupBy: [],
      metrics: [{ id: "A", name: "mem.free", agg: "last" }],
      formulas: [{ id: "F1", expr: "A * 100", name: "scaled" }],
      visualization: { type: "time_series" },
    })

    expect(payload.formulas).toEqual([
      { id: "F1", expr: "A * 100", name: "scaled" },
    ])
  })

  it("omits empty formulas array from payload", () => {
    const payload = queryStateToPayload({
      mode: "metrics",
      filters: [],
      aggregations: [],
      groupBy: [],
      metrics: [{ id: "A", name: "mem.free", agg: "last" }],
      formulas: [],
      visualization: { type: "time_series" },
    })

    expect(payload).not.toHaveProperty("formulas")
  })

  it("persists hidden_metrics in visualization (round-trips through notebook cells)", () => {
    const payload = queryStateToPayload({
      mode: "metrics",
      filters: [],
      aggregations: [],
      groupBy: [],
      metrics: [{ id: "A", name: "mem.free", agg: "last" }],
      formulas: [],
      visualization: { type: "time_series", hidden_metrics: ["A"] },
    })

    expect(payload.visualization).toEqual({
      type: "time_series",
      hidden_metrics: ["A"],
    })
  })
})
