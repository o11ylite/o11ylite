import { describe, it, expect } from "vitest"
import type { Field } from "@/types"
import { groupAttributeFields } from "./group-fields"

const f = (name: string): Field => ({ name, type: "string" })

describe("groupAttributeFields", () => {
  it("keeps non-attr fields ungrouped, sorted alphabetically", () => {
    const result = groupAttributeFields([
      f("timestamp"),
      f("body"),
      f("event.name"),
      f("service.name"),
    ])
    expect(result.groups).toEqual([])
    expect(result.ungrouped.map((x) => x.name)).toEqual([
      "body",
      "event.name",
      "service.name",
      "timestamp",
    ])
  })

  it("groups attr.<ns>.* with 2+ siblings under attr.<ns>", () => {
    const result = groupAttributeFields([
      f("attr.buildkite.build.id"),
      f("attr.buildkite.pipeline.slug"),
      f("attr.buildkite.organization.slug"),
      f("attr.host.name"),
      f("attr.host.id"),
      f("event.name"),
    ])

    expect(result.ungrouped.map((x) => x.name)).toEqual(["event.name"])
    expect(result.groups).toHaveLength(2)

    const [bk, host] = result.groups
    expect(bk.prefix).toBe("attr.buildkite")
    expect(bk.fields.map((x) => x.name)).toEqual([
      "attr.buildkite.build.id",
      "attr.buildkite.organization.slug",
      "attr.buildkite.pipeline.slug",
    ])
    expect(host.prefix).toBe("attr.host")
    expect(host.fields.map((x) => x.name)).toEqual([
      "attr.host.id",
      "attr.host.name",
    ])
  })

  it("leaves singleton attr.<ns>.x ungrouped (no folding for one item)", () => {
    const result = groupAttributeFields([
      f("attr.region.us"),
      f("attr.host.id"),
      f("attr.host.name"),
    ])
    expect(result.ungrouped.map((x) => x.name)).toEqual(["attr.region.us"])
    expect(result.groups).toHaveLength(1)
    expect(result.groups[0].prefix).toBe("attr.host")
  })

  it("treats attr.foo (no second segment) as ungrouped", () => {
    const result = groupAttributeFields([
      f("attr.foo"),
      f("attr.bar"),
      f("attr.host.id"),
      f("attr.host.name"),
    ])
    expect(result.ungrouped.map((x) => x.name)).toEqual([
      "attr.bar",
      "attr.foo",
    ])
    expect(result.groups.map((g) => g.prefix)).toEqual(["attr.host"])
  })

  it("sorts groups alphabetically by prefix", () => {
    const result = groupAttributeFields([
      f("attr.zoo.a"),
      f("attr.zoo.b"),
      f("attr.alpha.x"),
      f("attr.alpha.y"),
      f("attr.middle.p"),
      f("attr.middle.q"),
    ])
    expect(result.groups.map((g) => g.prefix)).toEqual([
      "attr.alpha",
      "attr.middle",
      "attr.zoo",
    ])
  })

  it("returns empty result for empty input", () => {
    expect(groupAttributeFields([])).toEqual({ ungrouped: [], groups: [] })
  })
})
