import { render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { describe, expect, it, vi } from "vitest"

import type { AuthUser } from "@/types"

vi.mock("@inertiajs/react", () => ({
  router: { post: vi.fn() },
}))

const { NavUser } = await import("./nav-user")
const { SidebarProvider } = await import("./ui/sidebar")

const user: AuthUser = {
  name: "Ada Lovelace",
  email: "ada@example.com",
  sub: "ada",
}

function renderNavUser() {
  return render(
    <SidebarProvider>
      <NavUser user={user} />
    </SidebarProvider>
  )
}

describe("NavUser", () => {
  it("opens the account menu without crashing", async () => {
    const browser = userEvent.setup()
    renderNavUser()

    await browser.click(
      screen.getByRole("button", { name: /Ada Lovelace/ })
    )

    expect(await screen.findByText("Log out")).toBeInTheDocument()
  })
})
