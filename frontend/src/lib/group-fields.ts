// ---
// Group OTel-style attribute fields by their semantic-convention namespace.
//
// OTel attribute names use dotted namespaces (e.g. `attr.buildkite.build.id`,
// `attr.host.name`). This panel renders every `attr.*` field under a
// namespace header, advocating the convention that observability attributes
// should be namespaced.
//
// The grouping rule:
//   * Non-`attr.*` fields stay flat at the top (the OTel core fields like
//     `body`, `event.name`, `service.name`, `timestamp`).
//   * `attr.<ns>.*` fields → group keyed by `attr.<ns>`, regardless of how
//     many siblings exist. Even a singleton `attr.foo.x` lives under an
//     `attr.foo` header — consistency reinforces the namespace convention.
//   * `attr.x` with no second segment (no namespace) → bucketed under a
//     special `(unnamespaced)` group, signalling these attributes violate
//     the convention. Always sorted to the bottom so dashboard readers
//     (who can't act on the violation) aren't forced to skim past it.
// ---
import type { Field } from "@/types"

/** Header label for `attr.x` fields that have no namespace. */
export const UNNAMESPACED_GROUP = "(unnamespaced)"

export interface FieldGroup {
  /** Group prefix shown as the collapsible header (e.g. `attr.buildkite`). */
  prefix: string
  /** Fields belonging to this group, sorted alphabetically by name. */
  fields: Field[]
}

export interface GroupedFields {
  /** Non-`attr.*` fields rendered flat above the groups, sorted alphabetically. */
  ungrouped: Field[]
  /** Collapsible groups, sorted alphabetically by prefix. */
  groups: FieldGroup[]
}

/**
 * Classify an `attr.*` field. Returns the group prefix it belongs to, or
 * `null` if the field is not an attribute (and therefore stays in the
 * ungrouped flat list).
 *
 *   `attr.buildkite.build.id` → `"attr.buildkite"`
 *   `attr.host.name`          → `"attr.host"`
 *   `attr.foo`                → UNNAMESPACED_GROUP
 *   `attr.`                   → UNNAMESPACED_GROUP (defensive)
 *   `body`                    → null
 */
function attrGroupKey(name: string): string | null {
  if (!name.startsWith("attr.")) return null
  const rest = name.slice("attr.".length)
  const dotIdx = rest.indexOf(".")
  if (dotIdx <= 0) return UNNAMESPACED_GROUP
  return `attr.${rest.slice(0, dotIdx)}`
}

export function groupAttributeFields(fields: readonly Field[]): GroupedFields {
  const ungrouped: Field[] = []
  const grouped = new Map<string, Field[]>()

  for (const f of fields) {
    const key = attrGroupKey(f.name)
    if (key === null) {
      ungrouped.push(f)
    } else {
      const bucket = grouped.get(key) ?? []
      bucket.push(f)
      grouped.set(key, bucket)
    }
  }

  ungrouped.sort((a, b) => a.name.localeCompare(b.name))
  const groups: FieldGroup[] = Array.from(grouped.entries())
    .map(([prefix, fs]) => ({
      prefix,
      fields: fs.slice().sort((a, b) => a.name.localeCompare(b.name)),
    }))
    .sort((a, b) => {
      // Push the (unnamespaced) bucket to the bottom. Most users are
      // reading dashboards, not fixing instrumentation — the violation
      // shouldn't block their scan of the well-formed namespaces.
      if (a.prefix === UNNAMESPACED_GROUP) return 1
      if (b.prefix === UNNAMESPACED_GROUP) return -1
      return a.prefix.localeCompare(b.prefix)
    })

  return { ungrouped, groups }
}
