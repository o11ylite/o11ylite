import { render, screen, act } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, it, expect, vi, beforeEach } from "vitest"
import * as React from "react"

// ============================================================================
// Inertia mock — usePage().url controls the active route in the sidebar.
// We expose a mutable holder so individual tests can override the URL before
// rendering.
// ============================================================================

const inertiaState = { url: "/explore" }

vi.mock("@inertiajs/react", () => ({
  usePage: () => ({ url: inertiaState.url, props: {} }),
  Link: ({
    children,
    href,
  }: {
    children: React.ReactNode
    href: string
  }) => React.createElement("a", { href }, children),
}))

// Import after mocks are set up.
const { AppSidebar } = await import("./app-sidebar")
const { SidebarProvider } = await import("./ui/sidebar")

const STORAGE_KEY = "o11ylite.sidebar.system-open"

function renderSidebar() {
  return render(
    <SidebarProvider>
      <AppSidebar />
    </SidebarProvider>
  )
}

// The Collapsible trigger renders the visible "System" toggle. There are
// other "System" strings on the page (group label, tooltip), so scope to the
// button role for the trigger.
function getSystemTrigger() {
  return screen.getByRole("button", { name: /^system$/i })
}

describe("AppSidebar — System submenu persistence", () => {
  beforeEach(() => {
    window.localStorage.clear()
    inertiaState.url = "/explore"
  })

  it("defaults to closed on non-system routes when no stored state exists", () => {
    renderSidebar()
    expect(getSystemTrigger()).toHaveAttribute("aria-expanded", "false")
  })

  it("restores open state from localStorage across remounts", () => {
    window.localStorage.setItem(STORAGE_KEY, "true")

    renderSidebar()

    expect(getSystemTrigger()).toHaveAttribute("aria-expanded", "true")
  })

  it("persists user toggling to localStorage", async () => {
    const user = userEvent.setup()
    renderSidebar()

    expect(getSystemTrigger()).toHaveAttribute("aria-expanded", "false")
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("false")

    await user.click(getSystemTrigger())

    expect(getSystemTrigger()).toHaveAttribute("aria-expanded", "true")
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("true")
  })

  it("forces the submenu open when navigating to a /system/* route", () => {
    inertiaState.url = "/system/api-keys"
    window.localStorage.setItem(STORAGE_KEY, "false")

    renderSidebar()

    // Stored state says "closed" but the active route is under /system, so
    // the submenu should still be open to reveal the active item.
    expect(getSystemTrigger()).toHaveAttribute("aria-expanded", "true")
  })

  it("does not overwrite stored 'true' when the user lands on a /system/* route", () => {
    // Regression guard: forcing open on system routes is a *display* override,
    // not a write. The user's persisted preference (here: open) must survive.
    window.localStorage.setItem(STORAGE_KEY, "true")
    inertiaState.url = "/system/settings"

    renderSidebar()

    expect(getSystemTrigger()).toHaveAttribute("aria-expanded", "true")
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe("true")
  })
})

// Silence "act(...) warnings" from the collapsible animation that
// settles after our assertions complete.
void act
