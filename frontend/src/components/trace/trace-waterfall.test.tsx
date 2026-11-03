import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"

import { TraceWaterfall } from "./trace-waterfall"
import type { TraceSpan } from "@/types"

function makeSpan({
  spanId,
  parentSpanId = null,
  name,
  timestamp,
  durationMs = 10,
}: {
  spanId: string
  parentSpanId?: string | null
  name: string
  timestamp: number
  durationMs?: number
}): TraceSpan {
  return {
    span_id: spanId,
    parent_span_id: parentSpanId,
    name,
    service: "svc-a",
    "meta.signal_type": "span",
    "span.status_code": "OK",
    "span.duration_ms": durationMs,
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

describe("TraceWaterfall focus", () => {
  const spans = [
    makeSpan({ spanId: "root", name: "root", timestamp: 1000 }),
    makeSpan({
      spanId: "child",
      parentSpanId: "root",
      name: "child",
      timestamp: 1010,
    }),
    makeSpan({
      spanId: "grandchild",
      parentSpanId: "child",
      name: "grandchild",
      timestamp: 1015,
    }),
    makeSpan({
      spanId: "sibling",
      parentSpanId: "root",
      name: "sibling",
      timestamp: 1010,
    }),
  ]

  it("renders the focused span as the root of the subtree", () => {
    render(
      <TraceWaterfall
        spans={spans}
        focusSpanId="child"
        onZoomIn={vi.fn()}
        onZoomOut={vi.fn()}
      />
    )

    const childLabel = screen.getByText("child", { selector: "span.min-w-0" })

    expect(childLabel).toBeInTheDocument()
    expect(screen.getByText("grandchild")).toBeInTheDocument()
    expect(screen.queryByText("root")).not.toBeInTheDocument()
    expect(screen.queryByText("sibling")).not.toBeInTheDocument()
    expect(screen.getByText("Focused on")).toBeInTheDocument()
    expect(screen.getByText("2 of 2 spans visible")).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Show full trace" })).toBeInTheDocument()

    // The focused span is the new root, so it has no indent and no zoom action.
    expect(childLabel.parentElement).toHaveStyle({
      paddingLeft: "0px",
    })
    expect(
      screen.queryByRole("button", { name: "Zoom into child" })
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole("button", { name: "Zoom into grandchild" })
    ).toBeInTheDocument()
  })

  it("clips bars that cross the focused timeline edges", () => {
    const spans = [
      makeSpan({
        spanId: "focus",
        name: "focus",
        timestamp: 1000,
        durationMs: 100,
      }),
      // Starts before the focus and ends inside it.
      makeSpan({
        spanId: "early",
        parentSpanId: "focus",
        name: "early",
        timestamp: 950,
        durationMs: 70,
      }),
      // Starts inside the focus and ends after it.
      makeSpan({
        spanId: "late",
        parentSpanId: "focus",
        name: "late",
        timestamp: 1070,
        durationMs: 60,
      }),
      // Covers the whole focus range.
      makeSpan({
        spanId: "long",
        parentSpanId: "focus",
        name: "long",
        timestamp: 950,
        durationMs: 300,
      }),
    ]

    render(
      <TraceWaterfall spans={spans} focusSpanId="focus" onZoomIn={vi.fn()} />
    )

    const barFor = (name: string) =>
      screen
        .getByText(name, { selector: "span.min-w-0" })
        .closest("div.group")!
        .querySelector(".absolute.h-6")!

    expect(barFor("early")).toHaveStyle({ left: "0%", width: "20%" })
    expect(barFor("late")).toHaveStyle({ left: "70%", width: "30%" })
    expect(barFor("long")).toHaveStyle({ left: "0%", width: "100%" })
  })
})
