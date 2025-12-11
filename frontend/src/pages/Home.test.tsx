import { render, screen } from "@testing-library/react"
import { describe, it, expect } from "vitest"
import Home from "./Home"

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
