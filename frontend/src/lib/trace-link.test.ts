import { describe, it, expect } from "vitest"

import { buildTraceHref, TRACE_LINK_WINDOW_MS } from "./trace-link"

function windowOf(href: string) {
  const url = new URL(href, "https://localhost")
  return {
    path: url.pathname,
    from: url.searchParams.get("from"),
    to: url.searchParams.get("to"),
  }
}

describe("buildTraceHref", () => {
  it("adds an absolute window around the event timestamp", () => {
    const ts = Date.UTC(2026, 10, 3, 10, 30, 0, 123)

    expect(windowOf(buildTraceHref("abc123", ts))).toEqual({
      path: "/trace/abc123",
      from: new Date(ts - TRACE_LINK_WINDOW_MS).toISOString(),
      to: new Date(ts + TRACE_LINK_WINDOW_MS).toISOString(),
    })
  })

  it("accepts sub-millisecond timestamps from the events API", () => {
    const ts = Date.UTC(2026, 10, 3, 10, 30, 0, 123) + 0.456

    expect(windowOf(buildTraceHref("abc123", ts)).from).toBe(
      "2026-11-03T07:30:00.123Z"
    )
  })

  it.each([undefined, null, "2026-11-03T10:30:00Z", Number.NaN])(
    "links without a window when the timestamp is %p",
    (timestamp) => {
      expect(buildTraceHref("abc123", timestamp)).toBe("/trace/abc123")
    }
  )
})
