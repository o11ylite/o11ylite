// Regression test for: hover tooltip rendering UNDERNEATH the legend on
// recharts charts.
//
// Recharts mounts `.recharts-tooltip-wrapper` and `.recharts-legend-
// wrapper` as absolute-positioned siblings inside `.recharts-wrapper`,
// neither with a default z-index. Paint order then follows DOM order —
// the legend is mounted after the tooltip, so it ends up on top and
// covers the bottom of the tooltip popover when they collide.
//
// The supported fix is to pass `wrapperStyle={{ zIndex: ... }}` to the
// Tooltip. We override `ChartTooltip` here to set a default zIndex, so
// every chart in the codebase gets the right stacking automatically.
//
// We render a fixed-size `<LineChart>` directly (skipping the
// ResponsiveContainer that ChartContainer normally wraps) because jsdom
// can't size a ResponsiveContainer and the chart would refuse to render
// its surface/tooltip wrapper. Reading `.style.zIndex` is sufficient —
// Recharts writes `wrapperStyle` as inline CSS on the wrapper div
// (see TooltipBoundingBox in recharts), so the value is directly
// observable without computed-style support.
import { render } from "@testing-library/react"
import { describe, it, expect } from "vitest"
import { Legend, Line, LineChart } from "recharts"

import { ChartTooltip } from "./chart"

describe("ChartTooltip recharts wrapper stacking", () => {
  it("defaults to a z-index that paints above the legend", () => {
    const { container } = render(
      <LineChart width={400} height={200} data={[{ x: 0, a: 1 }]}>
        <Line dataKey="a" />
        <ChartTooltip defaultIndex={0} />
        <Legend />
      </LineChart>,
    )

    const tooltipWrapper = container.querySelector(".recharts-tooltip-wrapper")
    const legendWrapper = container.querySelector(".recharts-legend-wrapper")

    expect(tooltipWrapper).toBeTruthy()
    expect(legendWrapper).toBeTruthy()

    // Recharts writes `wrapperStyle` as inline CSS on the tooltip
    // wrapper. Our ChartTooltip default seeds zIndex=10.
    const tooltipZ = (tooltipWrapper as HTMLElement).style.zIndex
    const legendZ = (legendWrapper as HTMLElement).style.zIndex

    expect(Number(tooltipZ)).toBeGreaterThan(0)
    // Legend stays at recharts' default — no z-index. If a future
    // recharts version starts setting one, we still need to beat it.
    if (legendZ !== "" && legendZ !== "auto") {
      expect(Number(tooltipZ)).toBeGreaterThan(Number(legendZ))
    }
  })

  it("lets callers override the z-index via wrapperStyle prop", () => {
    // Sanity check that we merge, don't clobber, caller-supplied
    // wrapperStyle. Callers reach for this when they need to e.g.
    // raise the tooltip above a sticky page header.
    const { container } = render(
      <LineChart width={400} height={200} data={[{ x: 0, a: 1 }]}>
        <Line dataKey="a" />
        <ChartTooltip defaultIndex={0} wrapperStyle={{ zIndex: 99 }} />
      </LineChart>,
    )
    const tooltipWrapper = container.querySelector(".recharts-tooltip-wrapper")
    expect((tooltipWrapper as HTMLElement).style.zIndex).toBe("99")
  })
})

