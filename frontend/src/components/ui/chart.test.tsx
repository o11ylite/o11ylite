import { render, fireEvent } from "@testing-library/react"
import { describe, it, expect } from "vitest"
import * as React from "react"
import type { TooltipPayloadEntry, LegendPayload } from "recharts"

import {
  ChartContext,
  ChartLegendContent,
  ChartTooltipContent,
  type ChartConfig,
} from "./chart"

// Helpers ---------------------------------------------------------------

const config: ChartConfig = {
  alpha: { label: "Alpha", color: "#ff0000" },
  beta: { label: "Beta", color: "#00ff00" },
  gamma: { label: "Gamma", color: "#0000ff" },
  delta: { label: "Delta", color: "#ffff00" },
}

// Recharts v3's Payload<> type adds many internal fields (`graphicalItemId`,
// etc.) that our customisation logic never reads. These helpers build the
// minimal shape our code touches and cast to the real type so tests stay
// readable.
const tooltipPayload = (items: Partial<TooltipPayloadEntry>[]) =>
  items as unknown as TooltipPayloadEntry[]
const legendPayload = (items: Partial<LegendPayload>[]) =>
  items as unknown as LegendPayload[]

// Lightweight ChartContext provider — both ChartTooltipContent and
// ChartLegendContent only call useChart() to read config; we don't need
// the full ChartContainer + ResponsiveContainer + SVG plumbing for unit
// tests, and ResponsiveContainer doesn't render in jsdom anyway.
function withChart(node: React.ReactNode) {
  return render(
    <ChartContext.Provider value={{ config }}>{node}</ChartContext.Provider>,
  )
}

// ChartTooltipContent ---------------------------------------------------

describe("ChartTooltipContent", () => {
  it("sorts visible series by value descending", () => {
    const payload = tooltipPayload([
      { name: "alpha", dataKey: "alpha", value: 10, color: "#ff0000", payload: {} },
      { name: "beta", dataKey: "beta", value: 100, color: "#00ff00", payload: {} },
      { name: "gamma", dataKey: "gamma", value: 50, color: "#0000ff", payload: {} },
    ])

    const { container } = withChart(
      <ChartTooltipContent active payload={payload} hideLabel />,
    )

    const labels = Array.from(
      container.querySelectorAll("span.text-muted-foreground"),
    ).map((el) => el.textContent)

    expect(labels).toEqual(["Beta", "Gamma", "Alpha"])
  })

  it("sinks non-numeric / nullish values to the bottom", () => {
    // Recharts emits `value: undefined` for gaps in a series — verify our
    // sort treats those as -Infinity so they sink rather than scrambling
    // the order.
    const payload = tooltipPayload([
      { name: "alpha", dataKey: "alpha", value: 10, color: "#ff0000", payload: {} },
      { name: "beta", dataKey: "beta", value: undefined, color: "#00ff00", payload: {} },
      { name: "gamma", dataKey: "gamma", value: 50, color: "#0000ff", payload: {} },
      { name: "delta", dataKey: "delta", value: undefined, color: "#ffff00", payload: {} },
    ])

    const { container } = withChart(
      <ChartTooltipContent active payload={payload} hideLabel />,
    )

    const labels = Array.from(
      container.querySelectorAll("span.text-muted-foreground"),
    ).map((el) => el.textContent)

    // Numeric desc first; undefined values last in name order.
    expect(labels).toEqual(["Gamma", "Alpha", "Beta", "Delta"])
  })

  it("breaks ties on name for stable ordering", () => {
    const payload = tooltipPayload([
      { name: "gamma", dataKey: "gamma", value: 5, color: "#0000ff", payload: {} },
      { name: "alpha", dataKey: "alpha", value: 5, color: "#ff0000", payload: {} },
      { name: "beta", dataKey: "beta", value: 5, color: "#00ff00", payload: {} },
    ])

    const { container } = withChart(
      <ChartTooltipContent active payload={payload} hideLabel />,
    )

    const labels = Array.from(
      container.querySelectorAll("span.text-muted-foreground"),
    ).map((el) => el.textContent)

    expect(labels).toEqual(["Alpha", "Beta", "Gamma"])
  })

  it("uses the formatter prop (covers TimeSeriesChart's render path)", () => {
    const payload = tooltipPayload([
      { name: "alpha", dataKey: "alpha", value: 10, color: "#ff0000", payload: {} },
      { name: "beta", dataKey: "beta", value: 100, color: "#00ff00", payload: {} },
      { name: "gamma", dataKey: "gamma", value: 50, color: "#0000ff", payload: {} },
    ])

    const formatter = (value: unknown, name: unknown) => (
      <span data-testid="row">{`${String(name)}=${String(value)}`}</span>
    )

    const { container } = withChart(
      <ChartTooltipContent
        active
        payload={payload}
        hideLabel
        formatter={formatter}
      />,
    )

    const rows = Array.from(
      container.querySelectorAll<HTMLElement>("[data-testid='row']"),
    ).map((el) => el.textContent)

    // Sort happens before formatter is applied.
    expect(rows).toEqual(["beta=100", "gamma=50", "alpha=10"])
  })
})

// ChartLegendContent ----------------------------------------------------

describe("ChartLegendContent", () => {
  it("renders inline when at or below the collapse threshold", () => {
    // 8 series — exactly at the threshold, no toggle expected.
    const payload = legendPayload(
      Array.from({ length: 8 }, (_, i) => ({
        value: `series-${i}`,
        dataKey: `series-${i}`,
        type: "line" as const,
        color: "#ff0000",
      })),
    )

    const { container, queryByRole } = withChart(
      <ChartLegendContent payload={payload} />,
    )

    const wrapper = container.firstChild as HTMLElement
    expect(wrapper?.className).toContain("flex-wrap")
    // No expanded styling since not collapsible.
    expect(wrapper?.className).not.toContain("max-h-24")
    // No toggle button.
    expect(queryByRole("button")).toBeNull()
    // All swatches render.
    const swatches = wrapper.querySelectorAll("div[style*='background']")
    expect(swatches.length).toBe(8)
  })

  it("collapses long legends with a 'Show all (N)' toggle", () => {
    // 16 series mirrors the bug repro (DuckDB memory by tag).
    const payload = legendPayload(
      Array.from({ length: 16 }, (_, i) => ({
        value: `series-${i}`,
        dataKey: `series-${i}`,
        type: "line" as const,
        color: "#ff0000",
      })),
    )

    const { container, getByRole } = withChart(
      <ChartLegendContent payload={payload} />,
    )

    const wrapper = container.firstChild as HTMLElement
    expect(wrapper?.className).toContain("flex-wrap")

    // Only the first 8 swatches render initially.
    const swatches = wrapper.querySelectorAll("div[style*='background']")
    expect(swatches.length).toBe(8)

    // A toggle button is present and reports the total count.
    const toggle = getByRole("button")
    expect(toggle.textContent).toContain("Show all (16)")
    expect(toggle.getAttribute("aria-expanded")).toBe("false")
  })

  it("shows all series when the toggle is clicked", () => {
    const payload = legendPayload(
      Array.from({ length: 16 }, (_, i) => ({
        value: `series-${i}`,
        dataKey: `series-${i}`,
        type: "line" as const,
        color: "#ff0000",
      })),
    )

    const { container, getByRole } = withChart(
      <ChartLegendContent payload={payload} />,
    )

    const toggle = getByRole("button")
    fireEvent.click(toggle)

    const wrapper = container.firstChild as HTMLElement
    // Expanded — all 16 swatches render and the height cap kicks in
    // so the legend doesn't take over the chart.
    const swatches = wrapper.querySelectorAll("div[style*='background']")
    expect(swatches.length).toBe(16)
    expect(wrapper.className).toContain("max-h-24")
    expect(wrapper.className).toContain("overflow-y-auto")

    // Toggle now collapses again.
    expect(toggle.textContent).toContain("Show less")
    expect(toggle.getAttribute("aria-expanded")).toBe("true")
  })
})
