import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it, vi } from "vitest"

// ============================================================================
// Inertia Mocks
// ============================================================================

const post = vi.fn<(...args: unknown[]) => void>()

vi.mock("@inertiajs/react", () => ({
  router: {
    post: (...args: unknown[]) => {
      post(...args)
    },
    on: vi.fn(),
  },
}))

const { default: OAuthConsent } = await import("./OAuthConsent")

// ============================================================================
// Fixtures
// ============================================================================

const authorizationParams = {
  response_type: "code",
  client_id: "https://client.example.com/oauth/metadata.json",
  redirect_uri: "https://client.example.com/callback",
  code_challenge: "abc",
  code_challenge_method: "S256",
  scope: "read write",
  state: "xyz",
}

const baseProps = {
  client: {
    name: "Example MCP Client",
    id: "https://client.example.com/oauth/metadata.json",
    host: "client.example.com",
    uri: null,
  },
  redirect_uri: "https://client.example.com/callback",
  redirect_host: "client.example.com",
  localhost_redirect: false,
  scope: "write",
  resource: "https://o11ylite.example.com/mcp",
  authorization_params: authorizationParams,
}

// ============================================================================
// Tests
// ============================================================================

describe("OAuthConsent", () => {
  beforeEach(() => post.mockReset())

  it("shows the client, requested access, and redirect host", () => {
    render(<OAuthConsent {...baseProps} />)

    expect(screen.getByText("Authorize Example MCP Client")).toBeInTheDocument()
    expect(screen.getByText("write")).toBeInTheDocument()
    expect(screen.getAllByText("client.example.com")).toHaveLength(2)
    expect(screen.queryByRole("alert")).not.toBeInTheDocument()
  })

  it("warns when the code goes to a program on this computer", () => {
    render(
      <OAuthConsent
        {...baseProps}
        redirect_host="127.0.0.1"
        localhost_redirect={true}
      />
    )

    expect(screen.getByRole("alert")).toHaveTextContent(
      "a program running on this computer"
    )
  })

  it("posts the original parameters with the decision", () => {
    render(<OAuthConsent {...baseProps} />)

    fireEvent.click(screen.getByRole("button", { name: "Approve" }))
    expect(post).toHaveBeenCalledWith(
      "/oauth/authorize",
      { ...authorizationParams, decision: "approve" },
      expect.any(Object)
    )

    fireEvent.click(screen.getByRole("button", { name: "Deny" }))
    expect(post).toHaveBeenLastCalledWith(
      "/oauth/authorize",
      { ...authorizationParams, decision: "deny" },
      expect.any(Object)
    )
  })
})
