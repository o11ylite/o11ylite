import { describe, it, expect } from "vitest"
import type { Field } from "@/types"
import {
  groupAttributeFields,
  UNNAMESPACED_GROUP,
} from "./group-fields"

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

  it("groups attr.<ns>.* under attr.<ns>, sorted within each group", () => {
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

  it("groups singleton attr.<ns>.x under attr.<ns> (consistent namespace headers)", () => {
    const result = groupAttributeFields([
      f("attr.region.us"),
      f("attr.host.id"),
      f("attr.host.name"),
    ])
    expect(result.ungrouped).toEqual([])
    expect(result.groups).toHaveLength(2)

    const [host, region] = result.groups
    expect(host.prefix).toBe("attr.host")
    expect(host.fields.map((x) => x.name)).toEqual([
      "attr.host.id",
      "attr.host.name",
    ])
    expect(region.prefix).toBe("attr.region")
    expect(region.fields.map((x) => x.name)).toEqual(["attr.region.us"])
  })

  it("buckets attr.<x> (no namespace) into the (unnamespaced) group", () => {
    const result = groupAttributeFields([
      f("attr.foo"),
      f("attr.bar"),
      f("attr.host.id"),
      f("attr.host.name"),
    ])
    expect(result.ungrouped).toEqual([])
    // (unnamespaced) sorts to the bottom — readers see well-formed
    // namespaces first, the violation bucket last.
    expect(result.groups.map((g) => g.prefix)).toEqual([
      "attr.host",
      UNNAMESPACED_GROUP,
    ])
    const unns = result.groups.find((g) => g.prefix === UNNAMESPACED_GROUP)!
    expect(unns.fields.map((x) => x.name)).toEqual(["attr.bar", "attr.foo"])
  })

  it("groups even a single unnamespaced attr.x", () => {
    const result = groupAttributeFields([
      f("attr.environment"),
      f("attr.host.id"),
      f("attr.host.name"),
    ])
    expect(result.ungrouped).toEqual([])
    const unns = result.groups.find((g) => g.prefix === UNNAMESPACED_GROUP)!
    expect(unns.fields.map((x) => x.name)).toEqual(["attr.environment"])
  })

  it("sorts groups alphabetically; (unnamespaced) sinks to the bottom", () => {
    const result = groupAttributeFields([
      f("attr.zoo.a"),
      f("attr.zoo.b"),
      f("attr.alpha.x"),
      f("attr.alpha.y"),
      f("attr.middle.p"),
      f("attr.middle.q"),
      f("attr.orphan"),
    ])
    expect(result.groups.map((g) => g.prefix)).toEqual([
      "attr.alpha",
      "attr.middle",
      "attr.zoo",
      UNNAMESPACED_GROUP,
    ])
  })

  it("returns empty result for empty input", () => {
    expect(groupAttributeFields([])).toEqual({ ungrouped: [], groups: [] })
  })
})
