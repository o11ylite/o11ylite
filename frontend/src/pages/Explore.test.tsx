import { render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { QueryClient, QueryClientProvider } from "@tanstack/react-query"
import { http, HttpResponse } from "msw"
import { setupServer } from "msw/node"
import { describe, it, expect, vi, beforeAll, afterAll, afterEach } from "vitest"
import * as React from "react"

import type { QueryResponse, EventsQuery } from "@/types"

// ============================================================================
// MSW Server Setup
// ============================================================================

const mockQueryResponse: QueryResponse = {
  data: {
    rows: [{ service: "api-gateway", count: 42 }],
    total_count: 1,
    truncated: false,
  },
  metadata: {
    query_time_ms: 15,
    truncated: false,
  },
}

const server = setupServer(
  http.post("/api/query/events", () => {
    return HttpResponse.json(mockQueryResponse)
  })
)

beforeAll(() => server.listen())
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

// ============================================================================
// Inertia Mocks
// ============================================================================

// Must use vi.hoisted to define variables used in vi.mock factory
const { mockState, mockRouterPush } = vi.hoisted(() => ({
  mockState: {
    url: "https://localhost/explore",
    // Callback to notify React of URL changes (set by test wrapper)
    onUrlChange: null as (() => void) | null,
  },
  mockRouterPush: vi.fn(),
}))

// Mock props that Inertia would pass from the backend
const mockInertiaProps = {
  fields: [
    { name: "timestamp", type: "instant" },
    { name: "service", type: "string" },
    { name: "message", type: "string" },
  ],
  services: [
    { name: "api-gateway", first_seen_at: 1700000000, updated_at: 1700000000 },
  ],
}

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: mockState.url, props: mockInertiaProps }),
  router: {
    push: (opts: { url: string }) => {
      mockRouterPush(opts)
      // Update URL and trigger re-render
      mockState.url = `https://localhost${opts.url}`
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
    mockState.url = "https://localhost/explore"
  })

  it("sends API request and renders results when Run is clicked", async () => {
    const user = userEvent.setup()

    let requestBody: EventsQuery | null = null

    server.use(
      http.post("/api/query/events", async ({ request }) => {
        requestBody = (await request.json()) as EventsQuery
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
    expect(requestBody!.visualization).toEqual({ type: "table", limit: 100 })
  })
})
