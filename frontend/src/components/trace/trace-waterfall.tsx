import { useEffect, useMemo, useRef, useState } from "react"
import { ChevronRight, ZoomIn } from "lucide-react"

import { Button } from "@/components/ui/button"
import { cn } from "@/lib/utils"
import type { TraceSpan } from "@/types"
import { resolveTraceFocus } from "./trace-subtree"

// ============================================================================
// Types
// ============================================================================

interface SpanNode extends TraceSpan {
  depth: number
  children: SpanNode[]
  descendantCount: number
  spanEvents: TraceSpan[] // Span events belonging to this span (rendered as markers)
}

// ============================================================================
// Tree Building
// ============================================================================

function buildSpanTree(spans: TraceSpan[]): SpanNode[] {
  // Separate spans from span_events
  const actualSpans = spans.filter((s) => s["meta.signal_type"] === "span")
  const spanEvents = spans.filter((s) => s["meta.signal_type"] === "span_event")

  // Create a map of span_id to SpanNode (for actual spans only)
  const nodeMap = new Map<string, SpanNode>()
  for (const span of actualSpans) {
    nodeMap.set(span.span_id, {
      ...span,
      depth: 0,
      children: [],
      descendantCount: 0,
      spanEvents: [],
    })
  }

  // Attach span_events to their parent span (for rendering as markers on the same row)
  for (const event of spanEvents) {
    const parentSpan = nodeMap.get(event.span_id)
    if (parentSpan) {
      parentSpan.spanEvents.push(event)
    }
  }

  // Sort span events by timestamp within each span
  for (const node of nodeMap.values()) {
    node.spanEvents.sort((a, b) => a.timestamp - b.timestamp)
  }

  // Build tree structure for spans
  const roots: SpanNode[] = []
  for (const node of nodeMap.values()) {
    if (node.parent_span_id && nodeMap.has(node.parent_span_id)) {
      const parent = nodeMap.get(node.parent_span_id)!
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }

  // Calculate depths and descendant counts via DFS
  function setDepths(node: SpanNode, depth: number): number {
    node.depth = depth
    node.children.sort((a, b) => a.timestamp - b.timestamp)
    let descendants = 0
    for (const child of node.children) {
      descendants += 1 + setDepths(child, depth + 1)
    }
    node.descendantCount = descendants
    return descendants
  }

  // Sort roots by timestamp
  roots.sort((a, b) => a.timestamp - b.timestamp)
  for (const root of roots) {
    setDepths(root, 0)
  }

  return roots
}

function flattenTree(roots: SpanNode[], collapsedSpanIds: Set<string>): SpanNode[] {
  const result: SpanNode[] = []

  function traverse(node: SpanNode) {
    result.push(node)
    if (collapsedSpanIds.has(node.span_id)) return
    for (const child of node.children) {
      traverse(child)
    }
  }

  for (const root of roots) {
    traverse(root)
  }

  return result
}

function collectParentSpanIds(roots: SpanNode[]): Set<string> {
  const ids = new Set<string>()

  function traverse(node: SpanNode) {
    if (node.children.length > 0) ids.add(node.span_id)
    for (const child of node.children) {
      traverse(child)
    }
  }

  for (const root of roots) {
    traverse(root)
  }

  return ids
}

// ============================================================================
// Time Formatting
// ============================================================================

function formatDuration(ms: number): string {
  if (ms < 1) return `${(ms * 1000).toFixed(0)}μs`
  if (ms < 1000) return `${ms.toFixed(1)}ms`
  return `${(ms / 1000).toFixed(2)}s`
}

function clampPercent(value: number): number {
  return Math.max(0, Math.min(100, value))
}

// ============================================================================
// Service Colors
// ============================================================================

const SERVICE_COLORS = [
  "bg-blue-500",
  "bg-green-500",
  "bg-purple-500",
  "bg-orange-500",
  "bg-pink-500",
  "bg-teal-500",
  "bg-indigo-500",
  "bg-yellow-500",
]

function getServiceColor(service: string, serviceMap: Map<string, number>): string {
  if (!serviceMap.has(service)) {
    serviceMap.set(service, serviceMap.size)
  }
  const index = serviceMap.get(service)!
  return SERVICE_COLORS[index % SERVICE_COLORS.length]
}

// ============================================================================
// Components
// ============================================================================

function SpanEventMarker({
  event,
  traceStart,
  traceDuration,
}: {
  event: TraceSpan
  traceStart: number
  traceDuration: number
}) {
  const leftPercent = clampPercent(((event.timestamp - traceStart) / traceDuration) * 100)

  return (
    <div
      className="group/marker absolute flex h-3 w-3 -translate-x-1/2 cursor-default items-center justify-center"
      style={{ left: `${leftPercent}%` }}
      title={event.name}
    >
      <div className="h-2 w-2 rotate-45 bg-amber-500 transition-transform group-hover/marker:scale-125" />
    </div>
  )
}

function SpanRow({
  span,
  traceStart,
  traceDuration,
  serviceColorMap,
  isSelected,
  isCollapsed,
  onToggleCollapse,
  onClick,
  onZoom,
}: {
  span: SpanNode
  traceStart: number
  traceDuration: number
  serviceColorMap: Map<string, number>
  isSelected: boolean
  isCollapsed: boolean
  onToggleCollapse: () => void
  onClick: () => void
  onZoom?: () => void
}) {
  const durationMs = span["span.duration_ms"] ?? 0
  // Clock skew can put a child slightly outside the focused span. Clip both
  // edges so the bar covers only the visible part of the timeline.
  const timelineEnd = traceStart + traceDuration
  const clippedStart = Math.max(span.timestamp, traceStart)
  const clippedEnd = Math.min(span.timestamp + durationMs, timelineEnd)
  const leftPercent = clampPercent(((clippedStart - traceStart) / traceDuration) * 100)
  const widthPercent = clampPercent(
    (Math.max(clippedEnd - clippedStart, 0) / traceDuration) * 100
  )
  // Ensure minimum width for visibility, but never overflow the timeline.
  const displayWidth = Math.max(Math.min(widthPercent, 100 - leftPercent), 0.5)

  const statusCode = span["span.status_code"]
  const isError = statusCode === "ERROR" || statusCode === "error"
  const barColor = isError ? "bg-red-500" : getServiceColor(span.service, serviceColorMap)

  // Position label inside bar (right-aligned) if bar ends near right edge,
  // otherwise position it to the right of the bar
  const barEndPercent = leftPercent + displayWidth
  const labelInside = barEndPercent > 85

  const hasEvents = span.spanEvents.length > 0
  const hasChildren = span.children.length > 0

  return (
    <div
      className={cn(
        "group flex min-h-[40px] cursor-pointer border-b border-border hover:bg-muted/50",
        isSelected && "bg-accent"
      )}
      onClick={onClick}
    >
      {/* Left column: span info */}
      <div className="flex w-[260px] shrink-0 flex-col justify-center border-r border-border px-2 py-1 sm:w-[300px]">
        <div
          className="flex items-center gap-1.5 truncate text-sm font-medium"
          style={{ paddingLeft: `${span.depth * 16}px` }}
        >
          {hasChildren ? (
            <button
              type="button"
              className="inline-flex size-6 shrink-0 items-center justify-center rounded hover:bg-muted-foreground/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:size-5"
              aria-label={`${isCollapsed ? "Expand" : "Collapse"} ${span.name}`}
              aria-expanded={!isCollapsed}
              title={isCollapsed ? "Expand span group" : "Collapse span group"}
              onClick={(event) => {
                event.stopPropagation()
                onToggleCollapse()
              }}
            >
              <ChevronRight
                className={cn(
                  "h-4 w-4 text-muted-foreground transition-transform sm:h-3.5 sm:w-3.5",
                  !isCollapsed && "rotate-90"
                )}
              />
            </button>
          ) : (
            <span className="size-6 shrink-0 sm:size-5" aria-hidden />
          )}
          <span className="min-w-0 truncate">{span.name}</span>
          {isCollapsed && span.descendantCount > 0 && (
            <span
              className="shrink-0 rounded bg-muted px-1 text-[10px] font-normal text-muted-foreground"
              title={`${span.descendantCount} hidden span${span.descendantCount === 1 ? "" : "s"}`}
            >
              +{span.descendantCount}
            </span>
          )}
          {hasEvents && (
            <span className="shrink-0 text-xs text-amber-500" title={`${span.spanEvents.length} event(s)`}>
              ({span.spanEvents.length})
            </span>
          )}
          {onZoom && (
            <button
              type="button"
              className="ml-auto inline-flex size-6 shrink-0 items-center justify-center rounded text-muted-foreground transition-colors hover:bg-muted-foreground/20 hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:size-5"
              aria-label={`Zoom into ${span.name}`}
              title="Zoom into this span"
              onClick={(event) => {
                event.stopPropagation()
                onZoom()
              }}
            >
              <ZoomIn className="h-4 w-4" />
            </button>
          )}
        </div>
        <div
          className="truncate text-xs text-muted-foreground"
          style={{ paddingLeft: `${span.depth * 16}px` }}
        >
          {span.service}
        </div>
      </div>

      {/* Right column: timeline bar + event markers */}
      <div className="relative flex flex-1 items-center overflow-hidden px-2">
        <div
          className={cn("absolute h-6 rounded-sm", barColor)}
          style={{
            left: `${leftPercent}%`,
            width: `${displayWidth}%`,
            minWidth: "2px",
          }}
        />
        {/* Duration label */}
        <span
          className={cn(
            "absolute whitespace-nowrap text-xs",
            labelInside ? "text-white" : "text-muted-foreground"
          )}
          style={
            labelInside
              ? { left: `${leftPercent}%`, width: `${displayWidth}%`, textAlign: "right", paddingRight: "4px" }
              : { left: `calc(${barEndPercent}% + 4px)` }
          }
        >
          {formatDuration(durationMs)}
        </span>
        {/* Span event markers */}
        {span.spanEvents.map((event, i) => (
          <SpanEventMarker
            key={`${event.timestamp}-${i}`}
            event={event}
            traceStart={traceStart}
            traceDuration={traceDuration}
          />
        ))}
      </div>
    </div>
  )
}

function TraceToolbar({
  visibleCount,
  totalCount,
  onExpandAll,
  onCollapseAll,
}: {
  visibleCount: number
  totalCount: number
  onExpandAll: () => void
  onCollapseAll: () => void
}) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-muted/10 px-2 py-1.5">
      <div className="flex min-w-0 items-center gap-2">
        <span className="text-xs text-muted-foreground">
          {visibleCount} of {totalCount} spans visible
        </span>
        <span className="text-[10px] text-muted-foreground sm:hidden">
          Swipe to see timeline
        </span>
      </div>
      <div className="ml-auto flex items-center gap-0.5">
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="h-8 px-2 sm:h-6"
          aria-label="Expand all span groups"
          onClick={onExpandAll}
        >
          Expand all
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="h-8 px-2 sm:h-6"
          aria-label="Collapse all span groups"
          onClick={onCollapseAll}
        >
          Collapse all
        </Button>
      </div>
    </div>
  )
}

function TimelineHeader({ traceDuration }: { traceDuration: number }) {
  // Generate tick marks at nice intervals
  const tickCount = 5
  const ticks = Array.from({ length: tickCount + 1 }, (_, i) => {
    const percent = (i / tickCount) * 100
    const ms = (i / tickCount) * traceDuration
    return { percent, label: formatDuration(ms) }
  })

  return (
    <div className="flex border-b border-border bg-muted/30">
      <div className="w-[260px] shrink-0 border-r border-border px-2 py-2 text-xs font-medium text-muted-foreground sm:w-[300px]">
        Span
      </div>
      <div className="relative flex-1 overflow-hidden px-2 py-2">
        {ticks.map((tick, i) => {
          // Align first tick left, last tick right, others centered
          const isFirst = i === 0
          const isLast = i === ticks.length - 1
          const transform = isFirst ? "none" : isLast ? "translateX(-100%)" : "translateX(-50%)"

          return (
            <span
              key={tick.percent}
              className="absolute text-xs text-muted-foreground"
              style={{ left: `${tick.percent}%`, transform }}
            >
              {tick.label}
            </span>
          )
        })}
      </div>
    </div>
  )
}

// ============================================================================
// Main Component
// ============================================================================

export function TraceWaterfall({
  spans,
  focusSpanId,
  selectedSpanId,
  onSpanSelect,
  onZoomIn,
  onZoomOut,
}: {
  spans: TraceSpan[]
  focusSpanId?: string | null
  selectedSpanId?: string | null
  onSpanSelect?: (spanId: string | null) => void
  onZoomIn?: (spanId: string) => void
  onZoomOut?: () => void
}) {
  const { roots, focusSpan, actualSpanCount, traceStart, traceDuration } = useMemo(() => {
    const resolvedFocus = resolveTraceFocus(spans, focusSpanId)
    const focusSpan = resolvedFocus?.focusSpan
    const displayedSpans = resolvedFocus?.subtree ?? spans
    const tree = buildSpanTree(displayedSpans)

    let minTimestamp = Infinity
    let maxEnd = -Infinity

    if (focusSpan) {
      // Zooming rebases the timeline on the focused span, so its bar fills the
      // full width. Descendant bars are clamped to that range when rendering.
      minTimestamp = focusSpan.timestamp
      maxEnd = focusSpan.timestamp + (focusSpan["span.duration_ms"] ?? 0)
    }

    // A zero-duration span (or the full trace view) still needs a usable axis.
    if (!focusSpan || maxEnd <= minTimestamp) {
      for (const span of displayedSpans) {
        if (span["meta.signal_type"] !== "span") continue
        minTimestamp = Math.min(minTimestamp, span.timestamp)
        maxEnd = Math.max(maxEnd, span.timestamp + (span["span.duration_ms"] ?? 0))
      }
    }

    return {
      roots: tree,
      focusSpan,
      actualSpanCount: displayedSpans.filter(
        (span) => span["meta.signal_type"] === "span"
      ).length,
      traceStart: minTimestamp,
      traceDuration: maxEnd > minTimestamp ? maxEnd - minTimestamp : 1,
    }
  }, [spans, focusSpanId])

  const activeFocusSpanId = focusSpan?.span_id ?? null
  const collapsibleSpanIds = useMemo(() => collectParentSpanIds(roots), [roots])
  const [collapsedSpanIds, setCollapsedSpanIds] = useState<Set<string>>(
    () => new Set()
  )

  // A different zoom level starts fully expanded instead of inheriting folded
  // groups from the previous subtree.
  const previousFocusSpanId = useRef<string | null>(activeFocusSpanId)
  useEffect(() => {
    if (previousFocusSpanId.current !== activeFocusSpanId) {
      previousFocusSpanId.current = activeFocusSpanId
      setCollapsedSpanIds(new Set())
    }
  }, [activeFocusSpanId])

  const visibleSpans = useMemo(
    () => flattenTree(roots, collapsedSpanIds),
    [roots, collapsedSpanIds]
  )

  // Service color mapping (stable across renders)
  const serviceColorMap = useMemo(() => new Map<string, number>(), [])

  const toggleCollapsed = (spanId: string) => {
    setCollapsedSpanIds((previous) => {
      const next = new Set(previous)
      if (next.has(spanId)) {
        next.delete(spanId)
      } else {
        next.add(spanId)
      }
      return next
    })
  }

  return (
    <div className="overflow-auto rounded-md border border-border">
      {focusSpan && (
        <div className="flex flex-wrap items-center gap-2 border-b border-border bg-muted/20 px-2 py-1.5 text-xs">
          <ZoomIn className="size-3.5 shrink-0 text-muted-foreground" aria-hidden />
          <span className="min-w-0 truncate">
            Focused on <span className="font-medium">{focusSpan.name}</span>
          </span>
          <span className="text-muted-foreground">
            {actualSpanCount} span{actualSpanCount === 1 ? "" : "s"}
          </span>
          {onZoomOut && (
            <Button
              type="button"
              variant="ghost"
              size="xs"
              className="ml-auto h-8 px-2 sm:h-6"
              onClick={onZoomOut}
            >
              Show full trace
            </Button>
          )}
        </div>
      )}
      {collapsibleSpanIds.size > 0 && (
        <TraceToolbar
          visibleCount={visibleSpans.length}
          totalCount={actualSpanCount}
          onExpandAll={() => setCollapsedSpanIds(new Set())}
          onCollapseAll={() => setCollapsedSpanIds(new Set(collapsibleSpanIds))}
        />
      )}
      <div className="overflow-x-auto">
        <div className="min-w-[720px]">
          <TimelineHeader traceDuration={traceDuration} />
          <div>
            {visibleSpans.map((span) => (
              <SpanRow
                key={span.span_id}
                span={span}
                traceStart={traceStart}
                traceDuration={traceDuration}
                serviceColorMap={serviceColorMap}
                isSelected={selectedSpanId === span.span_id}
                isCollapsed={collapsedSpanIds.has(span.span_id)}
                onToggleCollapse={() => toggleCollapsed(span.span_id)}
                onZoom={
                  onZoomIn && span.span_id !== activeFocusSpanId
                    ? () => onZoomIn(span.span_id)
                    : undefined
                }
                onClick={() => {
                  // Toggle selection: click again to deselect
                  const newSelection = selectedSpanId === span.span_id ? null : span.span_id
                  onSpanSelect?.(newSelection)
                }}
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
