import { describe, expect, it } from "vitest"
import { resolveChartUnit } from "./format-metric-value"

describe("resolveChartUnit", () => {
  it("returns undefined when series is empty", () => {
    expect(resolveChartUnit([])).toBeUndefined()
  })

  it("returns the unit when a single series carries one", () => {
    expect(resolveChartUnit([{ unit: "%" }])).toBe("%")
  })

  it("returns undefined when no series carries a unit", () => {
    expect(resolveChartUnit([{ unit: undefined }, { unit: null }])).toBeUndefined()
  })

  it("returns the common unit when multiple series share the same unit", () => {
    expect(
      resolveChartUnit([{ unit: "By" }, { unit: "By" }, { unit: "By" }]),
    ).toBe("By")
  })

  it("returns undefined when series have different units", () => {
    expect(resolveChartUnit([{ unit: "%" }, { unit: "By" }])).toBeUndefined()
  })

  it("ignores series with no unit when others share one", () => {
    expect(
      resolveChartUnit([{ unit: "By" }, { unit: undefined }, { unit: "By" }]),
    ).toBe("By")
  })

  it("treats null and undefined the same (no unit)", () => {
    expect(resolveChartUnit([{ unit: null }, { unit: "ms" }])).toBe("ms")
  })
})
