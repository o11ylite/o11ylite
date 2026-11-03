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

let lastSpanDetailsBody: { filter?: unknown } | null = null

const server = setupServer(
  http.post("/api/query/events", async ({ request }) => {
    const body = (await request.json()) as {
      visualization?: { type?: string }
      filter?: unknown
    }
    if (body.visualization?.type === "trace") {
      return HttpResponse.json({ data: mockTraceResult })
    }
    lastSpanDetailsBody = body
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
  mockRouterPush.mockClear()
  lastSpanDetailsBody = null
  mockState.url = `https://localhost/trace/${TRACE_ID}`
})
afterAll(() => server.close())

// ============================================================================
// Inertia Mocks
// ============================================================================

const { mockState, mockRouterPush } = vi.hoisted(() => ({
  mockState: {
    url: "https://localhost/trace/abc123def456",
    // Callback that makes React re-render when the mocked router changes the URL.
    onUrlChange: null as (() => void) | null,
  },
  mockRouterPush: vi.fn(),
}))

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: mockState.url, props: { trace_id: "abc123def456" } }),
  router: {
    push: (opts: { url: string }) => {
      mockRouterPush(opts)
      const existingUrl = new URL(mockState.url)
      mockState.url = new URL(opts.url, existingUrl.origin).toString()
      mockState.onUrlChange?.()
    },
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

/** Re-render when the mocked router changes the URL, like Inertia does. */
function TraceWithUrlSync() {
  const [, forceUpdate] = React.useReducer((x: number) => x + 1, 0)

  React.useEffect(() => {
    mockState.onUrlChange = forceUpdate
    return () => {
      mockState.onUrlChange = null
    }
  }, [])

  return <Trace />
}

function renderTrace() {
  return render(
    <QueryClientProvider client={createTestQueryClient()}>
      <TraceWithUrlSync />
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

describe("Trace page — zoom into a span", () => {
  it("renders only the focused span subtree when the URL has ?span", async () => {
    mockState.url = `https://localhost/trace/${TRACE_ID}?span=span-2`

    renderTrace()

    expect(await screen.findByText("grandchild")).toBeInTheDocument()
    expect(screen.queryByText("root")).not.toBeInTheDocument()
    expect(screen.getByText("Focused on")).toBeInTheDocument()
    expect(screen.getByText("2 of 2 spans visible")).toBeInTheDocument()
  })

  it("zooms in from a row action and reflects the span in the URL", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Zoom into child" }))

    expect(mockRouterPush).toHaveBeenCalledWith(
      expect.objectContaining({
        url: `/trace/${TRACE_ID}?span=span-2`,
        preserveState: true,
        preserveScroll: true,
      })
    )
    expect(screen.queryByText("root")).not.toBeInTheDocument()
    expect(screen.getByText("Focused on")).toBeInTheDocument()
  })

  it("shows the full trace again from the focus banner", async () => {
    const user = userEvent.setup()
    mockState.url = `https://localhost/trace/${TRACE_ID}?span=span-2`

    renderTrace()

    expect(await screen.findByText("grandchild")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Show full trace" }))

    expect(mockRouterPush).toHaveBeenCalledWith(
      expect.objectContaining({ url: `/trace/${TRACE_ID}` })
    )
    expect(screen.getByText("root")).toBeInTheDocument()
    expect(screen.queryByText("Focused on")).not.toBeInTheDocument()
  })

  it("falls back to the full trace when the span is unknown", async () => {
    mockState.url = `https://localhost/trace/${TRACE_ID}?span=missing`

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    expect(screen.getByText("child")).toBeInTheDocument()
    expect(screen.getByRole("status")).toHaveTextContent(
      /not part of this trace/i
    )
  })

  it("preserves the time range when entering and leaving focus", async () => {
    const user = userEvent.setup()
    mockState.url = `https://localhost/trace/${TRACE_ID}?from=now-1h&to=now`

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    await user.click(screen.getByRole("button", { name: "Zoom into child" }))

    expect(mockRouterPush).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: `/trace/${TRACE_ID}?from=now-1h&to=now&span=span-2`,
      })
    )

    await user.click(screen.getByRole("button", { name: "Show full trace" }))

    expect(mockRouterPush).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: `/trace/${TRACE_ID}?from=now-1h&to=now`,
      })
    )
  })

  it("keeps the details panel when zooming into the selected span", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    await user.click(screen.getByText("child"))
    expect(
      await screen.findByRole("heading", { name: "Span Details" })
    ).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Zoom in" }))

    expect(mockRouterPush).toHaveBeenCalledWith(
      expect.objectContaining({ url: `/trace/${TRACE_ID}?span=span-2` })
    )
    expect(
      screen.getByRole("heading", { name: "Span Details" })
    ).toBeInTheDocument()
  })

  it("closes the details panel when zooming into a different span", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    await user.click(screen.getByText("child"))
    expect(
      await screen.findByRole("heading", { name: "Span Details" })
    ).toBeInTheDocument()

    await user.click(screen.getByRole("button", { name: "Zoom into grandchild" }))

    expect(await screen.findByText("Focused on")).toBeInTheDocument()
    expect(
      screen.queryByRole("heading", { name: "Span Details" })
    ).not.toBeInTheDocument()
  })

  it("scopes span details requests to the trace", async () => {
    const user = userEvent.setup()

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    await user.click(screen.getByText("child"))
    await screen.findByRole("heading", { name: "Span Details" })

    expect(lastSpanDetailsBody?.filter).toEqual({
      and: [
        { field: "trace_id", op: "=", value: TRACE_ID },
        { field: "span_id", op: "=", value: "span-2" },
      ],
    })
  })

  it("treats an empty span param as no focus", async () => {
    mockState.url = `https://localhost/trace/${TRACE_ID}?span=`

    renderTrace()

    expect(await screen.findByText("root")).toBeInTheDocument()
    expect(screen.queryByText("Focused on")).not.toBeInTheDocument()
    expect(screen.queryByRole("status")).not.toBeInTheDocument()
  })

  it("clears focus from the trace breadcrumb", async () => {
    mockState.url = `https://localhost/trace/${TRACE_ID}?from=now-1h&to=now&span=span-2`

    renderTrace()

    expect(await screen.findByText("grandchild")).toBeInTheDocument()
    expect(
      screen.getByRole("link", { name: `Trace: ${TRACE_ID}` })
    ).toHaveAttribute("href", `/trace/${TRACE_ID}?from=now-1h&to=now`)
  })
})
