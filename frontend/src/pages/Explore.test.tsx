import { render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { http, HttpResponse } from "msw"
import { setupServer } from "msw/node"
import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest"
import * as React from "react"

import type { QueryResponse, EventsQuery, MetricsQuery } from "@/types"

// ============================================================================
// MSW Server Setup
// ============================================================================

const mockQueryResponse: QueryResponse = {
  data: {
    rows: [{ service: "api-gateway", count: 42 }],
    total_count: 1,
    has_more: false,
    next_cursor: null,
  },
  metadata: {
    query_time_ms: 15,
    has_more: false,
  },
}

const mockMetricsQueryResponse: QueryResponse = {
  data: {
    bucket_ms: 60000,
    start_ms: 1704067200000,
    end_ms: 1704070800000,
    series: [
      {
        labels: { "attr.host": "server-1" },
        name: "A",
        data: [
          { timestamp: 1704067200000, value: 42.5 },
          { timestamp: 1704067260000, value: 45.2 },
        ],
      },
    ],
  },
  metadata: {
    query_time_ms: 10,
    has_more: false,
  },
}

const mockMetricsList = [
  { name: "cpu.utilization", metric_type: "gauge", unit: "%" },
  { name: "http.requests.total", metric_type: "sum", unit: "1" },
  { name: "http.server.duration", metric_type: "histogram", unit: "ms" },
]

const mockEventFields = [
  { name: "timestamp", type: "instant" },
  { name: "service", type: "string" },
  { name: "message", type: "string" },
]

const mockServices = [
  { name: "api-gateway", first_seen_at: 1700000000, updated_at: 1700000000 },
]

const mockTimeSeriesEventsResponse: QueryResponse = {
  data: {
    bucket_ms: 60000,
    start_ms: 1704067200000,
    end_ms: 1704070800000,
    series: [],
  },
  metadata: {
    query_time_ms: 5,
    has_more: false,
  },
}

const server = setupServer(
  http.post("/api/query/events", async ({ request }) => {
    // The Explore page fires two events queries when in table mode: the
    // primary table query (visualization.type === "table") and the
    // auxiliary count-over-time histogram (visualization.type ===
    // "time_series"). Return the appropriate shape for each — returning
    // table rows for a time_series request crashes the chart renderer.
    const body = (await request.json()) as EventsQuery
    if (body.visualization?.type === "time_series") {
      return HttpResponse.json(mockTimeSeriesEventsResponse)
    }
    return HttpResponse.json(mockQueryResponse)
  }),
  http.post("/api/query/metrics", () => {
    return HttpResponse.json(mockMetricsQueryResponse)
  }),
  http.get("/api/events/fields", () => {
    return HttpResponse.json(mockEventFields)
  }),
  http.get("/api/services", () => {
    return HttpResponse.json(mockServices)
  }),
  http.get("/api/metrics", () => {
    return HttpResponse.json(mockMetricsList)
  }),
  http.get("/api/metrics/:name", ({ params }) => {
    const name = params.name as string
    const metric = mockMetricsList.find((m) => m.name === name)
    if (!metric) {
      return HttpResponse.json({ error: "metric_not_found" }, { status: 404 })
    }
    return HttpResponse.json({
      ...metric,
      description: `${name} metric`,
      temporality: "delta",
      attributes: ["attr.host", "attr.region"],
      hist_boundaries: metric.metric_type === "histogram" ? [0.01, 0.1, 1, 10] : null,
    })
  })
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ============================================================================
// Inertia Mocks
// ============================================================================

// Must use vi.hoisted to define variables used in vi.mock factory
// Use fixed timestamps to ensure stable query keys across renders
const FIXED_FROM = "2024-01-01T00:00:00.000Z"
const FIXED_TO = "2024-01-01T01:00:00.000Z"

const { mockState, mockRouterPush } = vi.hoisted(() => ({
  mockState: {
    url: `https://localhost/explore?from=${encodeURIComponent("2024-01-01T00:00:00.000Z")}&to=${encodeURIComponent("2024-01-01T01:00:00.000Z")}`,
    // Callback to notify React of URL changes (set by test wrapper)
    onUrlChange: null as (() => void) | null,
  },
  mockRouterPush: vi.fn(),
}))

// Mock props that Inertia would pass from the backend (empty now - fields/services fetched via API)
const mockInertiaProps = {}

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: mockState.url, props: mockInertiaProps }),
  router: {
    push: (opts: { url: string }) => {
      mockRouterPush(opts)
      // Merge the new URL params with existing params from mockState.url
      // This simulates how a real URL update would preserve existing params
      const existingUrl = new URL(mockState.url)
      const newUrl = new URL(opts.url, existingUrl.origin)
      // Preserve from/to params from existing URL if not in new URL
      if (!newUrl.searchParams.has("from") && existingUrl.searchParams.has("from")) {
        newUrl.searchParams.set("from", existingUrl.searchParams.get("from")!)
      }
      if (!newUrl.searchParams.has("to") && existingUrl.searchParams.has("to")) {
        newUrl.searchParams.set("to", existingUrl.searchParams.get("to")!)
      }
      mockState.url = newUrl.toString()
      mockState.onUrlChange?.()
    },
    on: vi.fn(),
  },
  Link: ({ children, href }: { children: React.ReactNode; href: string }) =>
    React.createElement("a", { href }, children),
}))

// ============================================================================
// Test Helpers
// ============================================================================

// Import Explore after mocks are set up
const { default: Explore } = await import("./Explore")

function createTestQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
      },
    },
  })
}

/**
 * Wrapper that forces re-render when URL changes (simulating Inertia behavior).
 *
 * Problem: Our mock `router.push` updates `mockState.url`, but React doesn't
 * know about this change - no re-render happens, so `usePage()` is never
 * called again and the query never fires.
 *
 * Solution: Register a callback that forces re-render when URL changes.
 *
 * Flow:
 * 1. Click "Run" → router.push() called
 * 2. Mock updates mockState.url and calls mockState.onUrlChange()
 * 3. forceUpdate() triggers re-render
 * 4. Explore re-renders → usePage() returns new URL → query fires
 */
function ExploreWithUrlSync() {
  // useReducer trick to force re-renders: dispatch increments counter, triggering render
  const [, forceUpdate] = React.useReducer((x: number) => x + 1, 0)

  React.useEffect(() => {
    // Register callback so mock's router.push can trigger re-renders
    mockState.onUrlChange = forceUpdate
    return () => {
      mockState.onUrlChange = null
    }
  }, [])

  return <Explore />
}

function renderExplore() {
  const queryClient = createTestQueryClient()
  return {
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <ExploreWithUrlSync />
      </QueryClientProvider>
    ),
  }
}

// ============================================================================
// Tests
// ============================================================================

describe("Explore", () => {
  afterEach(() => {
    mockRouterPush.mockClear()
    // Reset to URL with fixed timestamps for stable query keys
    mockState.url = `https://localhost/explore?from=${encodeURIComponent(FIXED_FROM)}&to=${encodeURIComponent(FIXED_TO)}`
  })

  describe("Events Mode", () => {
    it("sends API request and renders results when Run is clicked", async () => {
      const user = userEvent.setup()

      let requestBody: EventsQuery | null = null

      server.use(
        http.post("/api/query/events", async ({ request }) => {
          const body = (await request.json()) as EventsQuery
          // Skip the auxiliary count(*) chart's own request — only
          // capture the primary table query so existing assertions
          // on `requestBody.visualization` stay accurate.
          if (body.visualization?.type === "time_series") {
            return HttpResponse.json(mockTimeSeriesEventsResponse)
          }
          requestBody = body
          return HttpResponse.json(mockQueryResponse)
        })
      )

      renderExplore()

      // Click the Run button
      const runButton = screen.getByRole("button", { name: /run/i })
      await user.click(runButton)

      // Verify URL was updated with query state
      expect(mockRouterPush).toHaveBeenCalledTimes(1)
      const pushArg = mockRouterPush.mock.calls[0][0] as { url: string }
      expect(pushArg.url).toContain("?q=")

      // Wait for API request and results
      await waitFor(() => {
        expect(screen.getByText("api-gateway")).toBeInTheDocument()
      })

      // Verify the request body structure
      expect(requestBody).not.toBeNull()
      expect(requestBody!.time_range.start).toBeTypeOf("number")
      expect(requestBody!.time_range.end).toBeTypeOf("number")
      expect(requestBody!.limit).toBe(100)
      expect(requestBody!.visualization).toEqual({ type: "table" })
    })

    it("fires an auxiliary count(*) time_series request in table-without-aggregations mode", async () => {
      // Capture every events request — the page fires two: the main
      // table query and the auxiliary count-over-time histogram. We
      // identify the auxiliary one by its visualization.type.
      const requests: EventsQuery[] = []
      const auxResponse: QueryResponse = {
        data: {
          bucket_ms: 60000,
          start_ms: 1704067200000,
          end_ms: 1704070800000,
          series: [
            {
              labels: {},
              name: "count(*)",
              data: [
                { timestamp: 1704067200000, value: 7 },
                { timestamp: 1704067260000, value: 3 },
              ],
            },
          ],
        },
        metadata: { query_time_ms: 4, has_more: false },
      }

      server.use(
        http.post("/api/query/events", async ({ request }) => {
          const body = (await request.json()) as EventsQuery
          requests.push(body)
          if (body.visualization?.type === "time_series") {
            return HttpResponse.json(auxResponse)
          }
          return HttpResponse.json(mockQueryResponse)
        }),
      )

      renderExplore()

      // The auxiliary chart fires on mount (events mode + table viz +
      // no aggregations is the default state). No need to click Run.
      await waitFor(() => {
        expect(
          requests.find((r) => r.visualization?.type === "time_series"),
        ).toBeDefined()
      })

      const auxRequest = requests.find(
        (r) => r.visualization?.type === "time_series",
      )!
      expect(auxRequest.aggregations).toEqual([
        { id: "A", field: "*", function: "count" },
      ])

      // The count summary should render once data arrives (10 events in
      // the fixture).
      await waitFor(() => {
        expect(screen.getByText(/10 events/)).toBeInTheDocument()
      })
    })
  })

  describe("Metrics Mode", () => {
    it("switches to metrics tab and shows metrics section", async () => {
      const user = userEvent.setup()
      renderExplore()

      // Find and click the Metrics tab
      const metricsTab = screen.getByRole("tab", { name: /metrics/i })
      await user.click(metricsTab)

      // Should show "Add a metric" prompt when no metrics are added
      await waitFor(() => {
        expect(screen.getByText(/add a metric/i)).toBeInTheDocument()
      })

      // Should show Add Metric button
      expect(screen.getByRole("button", { name: /add metric/i })).toBeInTheDocument()
    })

    it("adds a metric and shows metric picker", async () => {
      const user = userEvent.setup()
      renderExplore()

      // Switch to metrics tab
      const metricsTab = screen.getByRole("tab", { name: /metrics/i })
      await user.click(metricsTab)

      // Click Add Metric button
      const addButton = await screen.findByRole("button", { name: /add metric/i })
      await user.click(addButton)

      // Should show a metric row with letter "A"
      await waitFor(() => {
        expect(screen.getByText("A")).toBeInTheDocument()
      })

      // Should show metric picker placeholder
      expect(screen.getByText(/select metric/i)).toBeInTheDocument()
    })

    it("sends metrics API request when metric is selected and Run is clicked", async () => {
      const user = userEvent.setup()

      let requestBody: MetricsQuery | null = null

      server.use(
        http.post("/api/query/metrics", async ({ request }) => {
          requestBody = (await request.json()) as MetricsQuery
          return HttpResponse.json(mockMetricsQueryResponse)
        })
      )

      renderExplore()

      // Switch to metrics tab
      const metricsTab = screen.getByRole("tab", { name: /metrics/i })
      await user.click(metricsTab)

      // Add a metric
      const addButton = await screen.findByRole("button", { name: /add metric/i })
      await user.click(addButton)

      // Open metric picker - find by text content
      const metricPickerButton = await screen.findByText(/select metric/i)
      await user.click(metricPickerButton)

      // Wait for metrics to load and select one
      await waitFor(() => {
        expect(screen.getByRole("option", { name: /cpu\.utilization/ })).toBeInTheDocument()
      })
      await user.click(screen.getByRole("option", { name: /cpu\.utilization/ }))

      // Click Run
      const runButton = screen.getByRole("button", { name: /run/i })
      await user.click(runButton)

      // Verify URL was updated
      expect(mockRouterPush).toHaveBeenCalled()

      // Wait for API request
      await waitFor(() => {
        expect(requestBody).not.toBeNull()
      })

      // Verify the request body structure
      expect(requestBody!.time_range.start).toBeTypeOf("number")
      expect(requestBody!.time_range.end).toBeTypeOf("number")
      expect(requestBody!.metrics).toHaveLength(1)
      expect(requestBody!.metrics[0].id).toBe("A")
      expect(requestBody!.metrics[0].name).toBe("cpu.utilization")
      expect(requestBody!.metrics[0].agg).toBe("avg") // default for gauge
    })

    it("sends metrics API request with formulas when formula is added", async () => {
      const user = userEvent.setup()

      let requestBody: MetricsQuery | null = null

      server.use(
        http.post("/api/query/metrics", async ({ request }) => {
          requestBody = (await request.json()) as MetricsQuery
          return HttpResponse.json(mockMetricsQueryResponse)
        })
      )

      renderExplore()

      // Switch to metrics tab
      const metricsTab = screen.getByRole("tab", { name: /metrics/i })
      await user.click(metricsTab)

      // Add a metric
      const addMetricButton = await screen.findByRole("button", { name: /add metric/i })
      await user.click(addMetricButton)

      // Open metric picker and select cpu.utilization
      const metricPickerButton = await screen.findByText(/select metric/i)
      await user.click(metricPickerButton)
      await waitFor(() => {
        expect(screen.getByRole("option", { name: /cpu\.utilization/ })).toBeInTheDocument()
      })
      await user.click(screen.getByRole("option", { name: /cpu\.utilization/ }))

      // Now that a metric is named, FormulasSection renders. Click Add Formula.
      const addFormulaButton = await screen.findByRole("button", { name: /add formula/i })
      await user.click(addFormulaButton)

      // Type expression into the formula F1 expression input
      const formulaExprInput = await screen.findByLabelText("Formula F1 expression")
      await user.type(formulaExprInput, "A * 2")

      // Click Run
      const runButton = screen.getByRole("button", { name: /run/i })
      await user.click(runButton)

      // Wait for API request
      await waitFor(() => {
        expect(requestBody).not.toBeNull()
      })

      // Verify metric in request body
      expect(requestBody!.metrics).toHaveLength(1)
      expect(requestBody!.metrics[0].id).toBe("A")
      expect(requestBody!.metrics[0].name).toBe("cpu.utilization")

      // Verify formulas array
      expect(requestBody!.formulas).toBeDefined()
      expect(requestBody!.formulas).toHaveLength(1)
      expect(requestBody!.formulas![0].id).toBe("F1")
      expect(requestBody!.formulas![0].expr).toBe("A * 2")
    })

    it("hides visualization toggle in metrics mode", async () => {
      const user = userEvent.setup()
      renderExplore()

      // In events mode, should see table/time_series toggle
      expect(screen.getByTitle("Table")).toBeInTheDocument()
      expect(screen.getByTitle("Time series")).toBeInTheDocument()

      // Switch to metrics tab
      const metricsTab = screen.getByRole("tab", { name: /metrics/i })
      await user.click(metricsTab)

      // Visualization toggle should be hidden
      await waitFor(() => {
        expect(screen.queryByTitle("Table")).not.toBeInTheDocument()
      })
      expect(screen.queryByTitle("Time series")).not.toBeInTheDocument()
    })
  })
})
