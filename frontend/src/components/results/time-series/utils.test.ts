import { describe, it, expect } from "vitest"

import type { TimeSeriesQueryResult } from "@/types"
import { transformData } from "./utils"

function makeResult(): TimeSeriesQueryResult {
  // Two series over three buckets. Series A has a gap at t=1; series B is full.
  return {
    start_ms: 0,
    end_ms: 30,
    bucket_ms: 10,
    series: [
      {
        name: "hits",
        labels: { host: "a" },
        data: [
          { timestamp: 0, value: 1 },
          { timestamp: 20, value: 3 },
        ],
      },
      {
        name: "hits",
        labels: { host: "b" },
        data: [
          { timestamp: 0, value: 2 },
          { timestamp: 10, value: 4 },
          { timestamp: 20, value: 6 },
        ],
      },
    ],
  }
}

describe("transformData", () => {
  it("leaves gaps as nulls by default", () => {
    const { chartData, seriesMeta } = transformData(makeResult())
    expect(chartData).toHaveLength(3)
    const keyA = seriesMeta[0].key
    expect(chartData.map((row) => row[keyA])).toEqual([1, null, 3])
  })

  it("zero-fills gaps when zeroFillNulls is true (stacked-area mode)", () => {
    const { chartData, seriesMeta } = transformData(makeResult(), {
      zeroFillNulls: true,
    })
    const keyA = seriesMeta[0].key
    const keyB = seriesMeta[1].key
    expect(chartData.map((row) => row[keyA])).toEqual([1, 0, 3])
    expect(chartData.map((row) => row[keyB])).toEqual([2, 4, 6])
  })

  it("assigns colors deterministically from sorted series keys, independent of input order", () => {
    // Capture the (key → color) mapping in the natural input order.
    const { seriesMeta } = transformData(makeResult())
    const colorByKey = new Map(seriesMeta.map((m) => [m.key, m.color]))

    // The lowest-sorting key should get the first palette slot. This is the
    // guarantee that lets us use the most distinct colors first.
    const sortedKeys = [...colorByKey.keys()].sort()
    expect(colorByKey.get(sortedKeys[0])).toBe("var(--chart-1)")

    // Reversing the series order in the response must not change any series'
    // color -- this is the bug the fix prevents.
    const reversed = makeResult()
    reversed.series = [...reversed.series].reverse()
    const { seriesMeta: reversedMeta } = transformData(reversed)
    for (const meta of reversedMeta) {
      expect(meta.color).toBe(colorByKey.get(meta.key))
    }
  })
})
