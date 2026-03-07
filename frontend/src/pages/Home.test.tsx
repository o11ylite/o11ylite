import { render, screen } from "@testing-library/react"
import { describe, it, expect, vi } from "vitest"
import * as React from "react"

// ============================================================================
// Inertia Mocks
// ============================================================================

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: "https://localhost/", props: {} }),
  router: {
    push: vi.fn(),
    on: vi.fn(),
  },
  Link: ({ children, href }: { children: React.ReactNode; href: string }) =>
    React.createElement("a", { href }, children),
}))

// ============================================================================
// Tests
// ============================================================================

// Import Home after mocks are set up
const { default: Home } = await import("./Home")

describe("Home", () => {
  it("renders the greeting", () => {
    render(<Home greeting="Welcome to O11yLite" />)

    expect(screen.getByText("Welcome to O11yLite")).toBeInTheDocument()
  })

  it("renders the page title in breadcrumb", () => {
    render(<Home greeting="Hello" />)

    expect(screen.getByText("Home")).toBeInTheDocument()
  })
})
