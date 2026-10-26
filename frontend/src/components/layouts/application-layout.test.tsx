import { render, screen, within } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"
import type * as React from "react"

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: "/trace/example", props: {} }),
  router: {
    push: vi.fn(),
    on: vi.fn(),
    visit: vi.fn(),
  },
  Link: ({ children, href }: { children: React.ReactNode; href: string }) => (
    <a href={href}>{children}</a>
  ),
}))

import ApplicationLayout from "./application-layout"

beforeEach(() => {
  window.localStorage.clear()
})

describe("ApplicationLayout header", () => {
  it("renders a string title with the time range and right panel controls", () => {
    render(
      <ApplicationLayout
        title="Explore"
        showTimeRange
        rightPanel={<div>Inspector</div>}
      >
        <div>Page content</div>
      </ApplicationLayout>
    )

    expect(screen.getByRole("navigation", { name: "breadcrumb" })).toHaveTextContent(
      "Explore"
    )
    expect(screen.getByRole("button", { name: "Last 15 minutes" })).toBeInTheDocument()
    expect(screen.getByRole("button", { name: "Toggle Right Panel" })).toBeInTheDocument()
  })

  it("renders breadcrumb entries with parent links", () => {
    render(
      <ApplicationLayout
        title={[
          { label: "Notebooks", href: "/notebooks" },
          { label: "Checkout latency investigation" },
        ]}
      >
        <div>Page content</div>
      </ApplicationLayout>
    )

    const breadcrumb = screen.getByRole("navigation", { name: "breadcrumb" })
    expect(breadcrumb).toHaveTextContent("Notebooks")
    expect(breadcrumb).toHaveTextContent("Checkout latency investigation")
    expect(within(breadcrumb).getByRole("link", { name: "Notebooks" })).toHaveAttribute(
      "href",
      "/notebooks"
    )
  })

  it("only renders optional header controls when requested", () => {
    render(
      <ApplicationLayout title="About">
        <div>Page content</div>
      </ApplicationLayout>
    )

    expect(screen.queryByRole("button", { name: "Last 15 minutes" })).not.toBeInTheDocument()
    expect(screen.queryByRole("button", { name: "Toggle Right Panel" })).not.toBeInTheDocument()
  })
})
