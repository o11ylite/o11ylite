import { describe, expect, it } from "vitest"

import { resolveTraceFocus, selectSpanSubtree } from "./trace-subtree"
import type { TraceSpan } from "@/types"

function makeSpan({
  spanId,
  parentSpanId = null,
  name,
  timestamp = 1000,
  signalType = "span",
}: {
  spanId: string
  parentSpanId?: string | null
  name: string
  timestamp?: number
  signalType?: "span" | "span_event"
}): TraceSpan {
  return {
    span_id: spanId,
    parent_span_id: parentSpanId,
    name,
    service: "svc-a",
    "meta.signal_type": signalType,
    "span.status_code": signalType === "span" ? "OK" : null,
    "span.duration_ms": signalType === "span" ? 10 : null,
    timestamp,
  }
}

const spans: TraceSpan[] = [
  makeSpan({ spanId: "root", name: "root" }),
  makeSpan({ spanId: "child", parentSpanId: "root", name: "child" }),
  makeSpan({ spanId: "grandchild", parentSpanId: "child", name: "grandchild" }),
  makeSpan({ spanId: "sibling", parentSpanId: "root", name: "sibling" }),
  makeSpan({
    spanId: "child",
    name: "child event",
    timestamp: 1200,
    signalType: "span_event",
  }),
  makeSpan({
    spanId: "sibling",
    name: "sibling event",
    timestamp: 1200,
    signalType: "span_event",
  }),
]

describe("selectSpanSubtree", () => {
  it("keeps the focused span, its descendants, and their events", () => {
    const result = selectSpanSubtree(spans, "child")

    expect(result?.map((span) => span.name).sort()).toEqual([
      "child",
      "child event",
      "grandchild",
    ])
  })

  it("drops ancestors and siblings", () => {
    const result = selectSpanSubtree(spans, "child")

    expect(result?.some((span) => span.span_id === "root")).toBe(false)
    expect(result?.some((span) => span.span_id === "sibling")).toBe(false)
    expect(result?.some((span) => span.name === "sibling event")).toBe(false)
  })

  it("returns null for a span that is not in the trace", () => {
    expect(selectSpanSubtree(spans, "missing")).toBeNull()
  })
})

describe("resolveTraceFocus", () => {
  it("returns the focus span and its subtree", () => {
    const result = resolveTraceFocus(spans, "child")

    expect(result?.focusSpan.span_id).toBe("child")
    expect(result?.subtree.map((span) => span.name).sort()).toEqual([
      "child",
      "child event",
      "grandchild",
    ])
  })

  it("returns null for an unknown or empty span id", () => {
    expect(resolveTraceFocus(spans, "missing")).toBeNull()
    expect(resolveTraceFocus(spans, null)).toBeNull()
    expect(resolveTraceFocus(spans, "")).toBeNull()
  })
})
