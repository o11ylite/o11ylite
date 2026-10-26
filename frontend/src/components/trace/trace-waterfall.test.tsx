import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it } from "vitest"

import { TraceWaterfall } from "./trace-waterfall"
import type { TraceSpan } from "@/types"

function makeSpan({
  spanId,
  parentSpanId = null,
  name,
  timestamp,
}: {
  spanId: string
  parentSpanId?: string | null
  name: string
  timestamp: number
}): TraceSpan {
  return {
    span_id: spanId,
    parent_span_id: parentSpanId,
    name,
    service: "svc-a",
    "meta.signal_type": "span",
    "span.status_code": "OK",
    "span.duration_ms": 10,
    timestamp,
  }
}

describe("TraceWaterfall grouping", () => {
  it("starts with all groups expanded for large traces", async () => {
    const user = userEvent.setup()
    const children = Array.from({ length: 80 }, (_, index) =>
      makeSpan({
        spanId: `child-${index}`,
        parentSpanId: "root",
        name: `child ${index}`,
        timestamp: 1000 + index,
      })
    )
    const spans = [
      makeSpan({ spanId: "root", name: "root", timestamp: 1000 }),
      ...children,
    ]

    render(<TraceWaterfall spans={spans} />)

    expect(screen.getByText("root")).toBeInTheDocument()
    expect(screen.getByText("child 0")).toBeInTheDocument()
    expect(screen.getByText("81 of 81 spans visible")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Collapse all span groups" }))

    expect(screen.getByText("root")).toBeInTheDocument()
    expect(screen.queryByText("child 0")).not.toBeInTheDocument()
    expect(screen.getByText("+80")).toBeInTheDocument()
    expect(screen.getByText("1 of 81 spans visible")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Expand all span groups" }))

    expect(screen.getByText("child 0")).toBeInTheDocument()
    expect(screen.getByText("81 of 81 spans visible")).toBeInTheDocument()
  })
})
