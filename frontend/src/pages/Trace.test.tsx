import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { http, HttpResponse } from "msw"
import { setupServer } from "msw/node"
import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest"
import * as React from "react"

import type { TraceQueryResult } from "@/types"

// ============================================================================
// MSW Server Setup
// ============================================================================

const TRACE_ID = "abc123def456"

const mockTraceResult: TraceQueryResult = {
  spans: [
    {
      span_id: "span-1",
      parent_span_id: null,
      name: "root",
      service: "svc-a",
      "meta.signal_type": "span",
      "span.status_code": "OK",
      "span.duration_ms": 500,
      timestamp: 1000,
    },
    {
      span_id: "span-2",
      parent_span_id: "span-1",
      name: "child",
      service: "svc-a",
      "meta.signal_type": "span",
      "span.status_code": "OK",
      "span.duration_ms": 100,
      timestamp: 1200,
    },
    {
      span_id: "span-3",
      parent_span_id: "span-2",
      name: "grandchild",
      service: "svc-b",
      "meta.signal_type": "span",
      "span.status_code": "OK",
      "span.duration_ms": 50,
      timestamp: 1250,
    },
  ],
  total_count: 3,
}

const server = setupServer(
  http.post("/api/query/events", async ({ request }) => {
    const body = (await request.json()) as { visualization?: { type?: string } }
    if (body.visualization?.type === "trace") {
      return HttpResponse.json({ data: mockTraceResult })
    }
    // Span details query (visualization.type === "table")
    return HttpResponse.json({
      data: {
        rows: [
          {
            span_id: "span-2",
            name: "child",
            service: "svc-a",
            "meta.signal_type": "span",
            timestamp: 1200,
          },
        ],
        total_count: 1,
      },
    })
  })
)

beforeAll(() => server.listen())
afterEach(() => {
  server.resetHandlers()
  window.localStorage.clear()
})
afterAll(() => server.close())

// ============================================================================
// Inertia Mocks
// ============================================================================

const mockUrl = `https://localhost/trace/${TRACE_ID}`

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: mockUrl, props: { trace_id: TRACE_ID } }),
  router: {
    push: vi.fn(),
    on: vi.fn(),
  },
  Link: ({ children, href }: { children: React.ReactNode; href: string }) =>
    React.createElement("a", { href }, children),
}))

// Import after mocks are set up.
const { default: Trace } = await import("./Trace")

// ============================================================================
// Test Helpers
// ============================================================================

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
}

function renderTrace() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <Trace />
    </QueryClientProvider>
  )
}

function getPanelWidthStyle(): string | null {
  const aside = document.querySelector("aside")
  return aside ? aside.getAttribute("style") : null
}

describe("Trace page — span click opens side panel", () => {
  it("opens the collapsed right panel when a span is selected", async () => {
    const user = userEvent.setup()
    window.localStorage.setItem("right_panel_open", "false")

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    // No selection yet, so no panel is rendered.
    expect(document.querySelector("aside")).not.toBeInTheDocument()

    await user.click(screen.getByText("child"))

    expect(getPanelWidthStyle()).toContain("var(--panel-width)")
    expect(await screen.findByRole("heading", { name: "Span Details" })).toBeInTheDocument()
  })

  it("collapses and expands a span group without selecting the span", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    expect(screen.getByText("child")).toBeInTheDocument()
    expect(screen.getByText("grandchild")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Collapse root" }))

    expect(screen.getByText("root")).toBeInTheDocument()
    expect(screen.queryByText("child")).not.toBeInTheDocument()
    expect(screen.queryByText("grandchild")).not.toBeInTheDocument()
    expect(screen.getByText("+2")).toBeInTheDocument()
    expect(document.querySelector("aside")).not.toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Expand root" }))

    expect(screen.getByText("child")).toBeInTheDocument()
    expect(screen.getByText("grandchild")).toBeInTheDocument()
  })

  it("expands and collapses all span groups from the toolbar", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("grandchild")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Collapse all span groups" }))

    expect(screen.getByText("root")).toBeInTheDocument()
    expect(screen.queryByText("child")).not.toBeInTheDocument()
    expect(screen.getByText("1 of 3 spans visible")).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Expand all span groups" }))

    expect(screen.getByText("grandchild")).toBeInTheDocument()
    expect(screen.getByText("3 of 3 spans visible")).toBeInTheDocument()
  })

  it("respects the toggle when the panel is already open", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()

    await user.click(screen.getByText("child"))
    expect(getPanelWidthStyle()).toContain("var(--panel-width)")

    await user.click(screen.getByRole("button", { name: "Toggle Right Panel" }))

    expect(getPanelWidthStyle()).toContain("width: 0")
  })
})
