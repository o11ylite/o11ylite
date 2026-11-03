import { render, screen } from "@testing-library/react"
import { describe, it, expect, vi } from "vitest"
import * as React from "react"

import type { QueryResponse } from "@/types"

vi.mock("@inertiajs/react", () => ({
  Link: ({ children, href }: { children: React.ReactNode; href: string }) =>
    React.createElement("a", { href }, children),
}))

const { ResultsTable } = await import("./results-table")

function tableResponse(rows: Record<string, unknown>[]): QueryResponse {
  return {
    data: { rows, total_count: rows.length, has_more: false, next_cursor: null },
    metadata: { query_time_ms: 1 },
  } as unknown as QueryResponse
}

describe("ResultsTable trace links", () => {
  it("links raw event rows to an absolute window around the row", () => {
    const ts = Date.UTC(2026, 10, 3, 10, 30)
    render(
      <ResultsTable
        data={tableResponse([
          { timestamp: ts, service: "api", trace_id: "abc123def456" },
        ])}
        visualization={{ type: "table" }}
      />
    )

    const href = screen.getByRole("link", { name: "abc123de…" }).getAttribute("href")
    const url = new URL(href!, "https://localhost")
    expect(url.pathname).toBe("/trace/abc123def456")
    expect(url.searchParams.get("from")).toBe("2026-11-03T07:30:00.000Z")
    expect(url.searchParams.get("to")).toBe("2026-11-03T13:30:00.000Z")
  })

  it("links aggregated rows without a window", () => {
    render(
      <ResultsTable
        data={tableResponse([{ trace_id: "abc123def456", "count(*)": 3 }])}
        visualization={{ type: "table" }}
      />
    )

    expect(screen.getByRole("link", { name: "abc123de…" })).toHaveAttribute(
      "href",
      "/trace/abc123def456"
    )
  })
})
