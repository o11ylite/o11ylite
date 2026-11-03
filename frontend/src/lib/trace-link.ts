// ============================================================================
// Trace Links
// ============================================================================

/**
 * Half-width of the time window a trace link searches around the linked event.
 *
 * Every span of a trace starts within the trace's duration of every other span,
 * so a window around any one event covers the whole trace unless the trace runs
 * longer than this. People can still widen the window on the trace page.
 */
export const TRACE_LINK_WINDOW_MS = 3 * 60 * 60 * 1000

/**
 * Builds a link to the trace page.
 *
 * With the epoch-ms timestamp of an event in the trace, the link carries an
 * absolute `from`/`to` window around it, so it keeps working until retention
 * deletes the data. Without one, the trace page falls back to its default
 * relative range.
 */
export function buildTraceHref(traceId: string, timestamp?: unknown): string {
  const path = `/trace/${encodeURIComponent(traceId)}`
  if (typeof timestamp !== "number" || !Number.isFinite(timestamp)) return path

  const params = new URLSearchParams({
    from: new Date(timestamp - TRACE_LINK_WINDOW_MS).toISOString(),
    to: new Date(timestamp + TRACE_LINK_WINDOW_MS).toISOString(),
  })
  return `${path}?${params.toString()}`
}
