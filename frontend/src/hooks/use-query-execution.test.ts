import { describe, it, expect } from "vitest"

import { buildMetricsPayload } from "./use-query-execution"
import type { QueryBuilderState } from "@/types"

const baseMetricsState: QueryBuilderState = {
  mode: "metrics",
  filters: [],
  aggregations: [],
  groupBy: [],
  metrics: [
    { id: "A", name: "mem.free", agg: "last" },
    { id: "B", name: "mem.total", agg: "last" },
  ],
  formulas: [],
  visualization: { type: "time_series" },
}

describe("buildMetricsPayload", () => {
  it("omits formulas when empty", () => {
    const payload = buildMetricsPayload(baseMetricsState)
    expect(payload).not.toBeNull()
    expect(payload!).not.toHaveProperty("formulas")
  })

  it("includes formulas when present", () => {
    const payload = buildMetricsPayload({
      ...baseMetricsState,
      formulas: [{ id: "F1", expr: "A / B * 100", name: "free %", unit: "%" }],
    })
    expect(payload!.formulas).toEqual([
      { id: "F1", expr: "A / B * 100", name: "free %", unit: "%" },
    ])
  })

  it("drops formulas with empty/whitespace-only expr (don't 400 mid-typing)", () => {
    const payload = buildMetricsPayload({
      ...baseMetricsState,
      formulas: [
        { id: "F1", expr: "" },
        { id: "F2", expr: "   " },
        { id: "F3", expr: "A / B" },
      ],
    })
    expect(payload!.formulas).toEqual([{ id: "F3", expr: "A / B" }])
  })

  it("omits formulas key entirely when all formulas are empty", () => {
    const payload = buildMetricsPayload({
      ...baseMetricsState,
      formulas: [{ id: "F1", expr: "" }],
    })
    expect(payload!).not.toHaveProperty("formulas")
  })

  it("returns null when no valid metrics even if formulas present", () => {
    expect(
      buildMetricsPayload({
        ...baseMetricsState,
        metrics: [],
        formulas: [{ id: "F1", expr: "1 + 2" }],
      })
    ).toBeNull()
  })
})
