import type { TraceSpan } from "@/types"

// ============================================================================
// Subtree Selection
// ============================================================================

/**
 * Returns the focused span, its descendants, and the span events attached to
 * any of them. Returns null when the span is not part of this trace.
 *
 * Span events store their parent span in `span_id`, so the same id set keeps
 * the events that belong to the visible part of the tree.
 */
export function selectSpanSubtree(
  spans: TraceSpan[],
  focusSpanId: string
): TraceSpan[] | null {
  const isSpan = (span: TraceSpan) => span["meta.signal_type"] === "span"

  if (!spans.some((span) => isSpan(span) && span.span_id === focusSpanId)) {
    return null
  }

  const childrenByParent = new Map<string, string[]>()
  for (const span of spans) {
    if (!isSpan(span) || !span.parent_span_id) continue
    const children = childrenByParent.get(span.parent_span_id)
    if (children) {
      children.push(span.span_id)
    } else {
      childrenByParent.set(span.parent_span_id, [span.span_id])
    }
  }

  // Walk down from the focused span. The set doubles as the cycle guard.
  const included = new Set<string>([focusSpanId])
  const queue = [focusSpanId]
  while (queue.length > 0) {
    const parentSpanId = queue.pop()!
    for (const childId of childrenByParent.get(parentSpanId) ?? []) {
      if (!included.has(childId)) {
        included.add(childId)
        queue.push(childId)
      }
    }
  }

  return spans.filter((span) => included.has(span.span_id))
}

// ============================================================================
// Focus Resolution
// ============================================================================

export interface ResolvedTraceFocus {
  focusSpan: TraceSpan
  subtree: TraceSpan[]
}

/**
 * Resolves a focus span id against a trace. Returns null when the id is empty
 * or does not identify a span in this trace, so callers can fall back to the
 * full trace without duplicating the validation rules.
 */
export function resolveTraceFocus(
  spans: TraceSpan[],
  focusSpanId: string | null | undefined
): ResolvedTraceFocus | null {
  if (!focusSpanId) return null

  const focusSpan = spans.find(
    (span) =>
      span.span_id === focusSpanId && span["meta.signal_type"] === "span"
  )
  if (!focusSpan) return null

  return {
    focusSpan,
    subtree: selectSpanSubtree(spans, focusSpan.span_id) ?? [focusSpan],
  }
}
