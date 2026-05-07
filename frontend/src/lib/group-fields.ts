// ---
// Group OTel-style attribute fields by their semantic-convention namespace.
//
// OTel attribute names use dotted namespaces (e.g. `attr.buildkite.build.id`,
// `attr.host.name`). When a panel lists many of these, folding them by the
// shared namespace prefix makes the panel scannable.
//
// The grouping rule:
//   * Non-`attr.*` fields stay flat.
//   * `attr.<ns>.*` fields with **2+ siblings** sharing the same `<ns>` are
//     pulled into a group keyed by `attr.<ns>` (e.g. group "attr.buildkite"
//     contains `attr.buildkite.build.id`, `attr.buildkite.pipeline.slug`, ...).
//   * Singletons (`attr.foo` or a lone `attr.namespace.bar`) stay flat in the
//     ungrouped list — wrapping a single item in a collapsible adds noise.
// ---
import type { Field } from "@/types"

export interface FieldGroup {
  /** Group prefix shown as the collapsible header (e.g. `attr.buildkite`). */
  prefix: string
  /** Fields belonging to this group, sorted alphabetically by name. */
  fields: Field[]
}

export interface GroupedFields {
  /** Fields rendered flat above the groups, sorted alphabetically. */
  ungrouped: Field[]
  /** Collapsible groups, sorted alphabetically by prefix. */
  groups: FieldGroup[]
}

/**
 * Extract the `attr.<ns>` namespace key from a field name, or `null` if the
 * field is not a multi-segment attribute. `attr.foo` (only one segment after
 * `attr.`) returns `null` because there's no namespace to group siblings by.
 */
function attrNamespace(name: string): string | null {
  if (!name.startsWith("attr.")) return null
  const rest = name.slice("attr.".length)
  const dotIdx = rest.indexOf(".")
  if (dotIdx <= 0) return null // `attr.foo` or malformed `attr.`
  return `attr.${rest.slice(0, dotIdx)}`
}

export function groupAttributeFields(fields: readonly Field[]): GroupedFields {
  // First pass: count namespace occurrences so singletons can stay flat.
  const counts = new Map<string, number>()
  for (const f of fields) {
    const ns = attrNamespace(f.name)
    if (ns) counts.set(ns, (counts.get(ns) ?? 0) + 1)
  }

  const ungrouped: Field[] = []
  const grouped = new Map<string, Field[]>()
  for (const f of fields) {
    const ns = attrNamespace(f.name)
    if (ns && (counts.get(ns) ?? 0) >= 2) {
      const bucket = grouped.get(ns) ?? []
      bucket.push(f)
      grouped.set(ns, bucket)
    } else {
      ungrouped.push(f)
    }
  }

  ungrouped.sort((a, b) => a.name.localeCompare(b.name))
  const groups: FieldGroup[] = Array.from(grouped.entries())
    .map(([prefix, fs]) => ({
      prefix,
      fields: fs.slice().sort((a, b) => a.name.localeCompare(b.name)),
    }))
    .sort((a, b) => a.prefix.localeCompare(b.prefix))

  return { ungrouped, groups }
}
