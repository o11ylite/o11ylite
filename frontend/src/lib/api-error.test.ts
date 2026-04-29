import { describe, it, expect } from "vitest"
import { flattenApiError } from "./api-error"

describe("flattenApiError", () => {
  it("returns plain string errors as-is", () => {
    expect(flattenApiError("invalid_aggregation", "fallback")).toBe(
      "invalid_aggregation",
    )
  })

  it("flattens a top-level field with vector message (Malli humanize shape)", () => {
    expect(
      flattenApiError(
        { having: ["having ref must reference a declared metric or formula id"] },
        "fallback",
      ),
    ).toBe("having: having ref must reference a declared metric or formula id")
  })

  it("flattens nested map errors with full key path", () => {
    expect(
      flattenApiError(
        { metrics: { 0: { id: ["missing required key"] } } },
        "fallback",
      ),
    ).toBe("metrics: 0: id: missing required key")
  })

  it("returns fallback for null", () => {
    expect(flattenApiError(null, "fallback")).toBe("fallback")
  })

  it("returns fallback for undefined", () => {
    expect(flattenApiError(undefined, "fallback")).toBe("fallback")
  })

  it("returns fallback for empty object", () => {
    expect(flattenApiError({}, "fallback")).toBe("fallback")
  })

  it("returns fallback for empty array", () => {
    expect(flattenApiError([], "fallback")).toBe("fallback")
  })

  it("picks the first message from a vector of strings", () => {
    expect(flattenApiError(["first", "second"], "fallback")).toBe("first")
  })
})
