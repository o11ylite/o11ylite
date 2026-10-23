import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { beforeEach, describe, expect, it } from "vitest"

import { SidebarProvider, SidebarTrigger, useSidebar } from "./sidebar"

const STORAGE_KEY = "sidebar_open"

function SidebarState() {
  const { open } = useSidebar()
  return (
    <span data-testid="sidebar-state">
      {open ? "expanded" : "collapsed"}
    </span>
  )
}

function renderSidebar() {
  return render(
    <SidebarProvider>
      <SidebarTrigger />
      <SidebarState />
    </SidebarProvider>
  )
}

function sidebarState() {
  return screen.getByTestId("sidebar-state").textContent
}

describe("SidebarProvider — open state persistence", () => {
  beforeEach(() => {
    window.localStorage.clear()
  })

  it("restores the collapsed state from localStorage on mount", () => {
    window.localStorage.setItem(STORAGE_KEY, "false")

    renderSidebar()

    expect(sidebarState()).toBe("collapsed")
  })

  it("persists the collapsed state across remounts", async () => {
    const browser = userEvent.setup()
    const { unmount } = renderSidebar()

    expect(sidebarState()).toBe("expanded")

    await browser.click(
      screen.getByRole("button", { name: /toggle sidebar/i })
    )

    expect(sidebarState()).toBe("collapsed")
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("false")

    unmount()
    renderSidebar()

    expect(sidebarState()).toBe("collapsed")
  })
})
