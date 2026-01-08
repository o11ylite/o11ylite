import { useMemo } from "react"

import { cn } from "@/lib/utils"
import type { TraceSpan } from "@/types"

// ============================================================================
// Types
// ============================================================================

interface SpanNode extends TraceSpan {
  depth: number
  children: SpanNode[]
}

// ============================================================================
// Tree Building
// ============================================================================

function buildSpanTree(spans: TraceSpan[]): SpanNode[] {
  // Create a map of span_id to SpanNode
  const nodeMap = new Map<string, SpanNode>()
  for (const span of spans) {
    nodeMap.set(span.span_id, { ...span, depth: 0, children: [] })
  }

  // Build tree structure
  const roots: SpanNode[] = []
  for (const node of nodeMap.values()) {
    if (node.parent_span_id && nodeMap.has(node.parent_span_id)) {
      const parent = nodeMap.get(node.parent_span_id)!
      parent.children.push(node)
    } else {
      roots.push(node)
    }
  }

  // Calculate depths via DFS
  function setDepths(node: SpanNode, depth: number) {
    node.depth = depth
    // Sort children by timestamp
    node.children.sort((a, b) => a.timestamp - b.timestamp)
    for (const child of node.children) {
      setDepths(child, depth + 1)
    }
  }

  // Sort roots by timestamp
  roots.sort((a, b) => a.timestamp - b.timestamp)
  for (const root of roots) {
    setDepths(root, 0)
  }

  return roots
}

function flattenTree(roots: SpanNode[]): SpanNode[] {
  const result: SpanNode[] = []

  function traverse(node: SpanNode) {
    result.push(node)
    for (const child of node.children) {
      traverse(child)
    }
  }

  for (const root of roots) {
    traverse(root)
  }

  return result
}

// ============================================================================
// Time Formatting
// ============================================================================

function formatDuration(ms: number): string {
  if (ms < 1) return `${(ms * 1000).toFixed(0)}μs`
  if (ms < 1000) return `${ms.toFixed(1)}ms`
  return `${(ms / 1000).toFixed(2)}s`
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

function SpanRow({
  span,
  traceStart,
  traceDuration,
  serviceColorMap,
}: {
  span: SpanNode
  traceStart: number
  traceDuration: number
  serviceColorMap: Map<string, number>
}) {
  // Calculate bar position and width as percentages
  const durationMs = span["span.duration_ms"]
  const leftPercent = ((span.timestamp - traceStart) / traceDuration) * 100
  const widthPercent = (durationMs / traceDuration) * 100
  // Ensure minimum width for visibility
  const displayWidth = Math.max(widthPercent, 0.5)

  const statusCode = span["span.status_code"]
  const isError = statusCode === "ERROR" || statusCode === "error"
  const barColor = isError ? "bg-red-500" : getServiceColor(span.service, serviceColorMap)

  // Position label inside bar (right-aligned) if bar ends near right edge,
  // otherwise position it to the right of the bar
  const barEndPercent = leftPercent + displayWidth
  const labelInside = barEndPercent > 85

  return (
    <div className="group flex min-h-[40px] border-b border-border hover:bg-muted/50">
      {/* Left column: span info */}
      <div className="flex w-[300px] shrink-0 flex-col justify-center border-r border-border px-2 py-1">
        <div
          className="truncate text-sm font-medium"
          style={{ paddingLeft: `${span.depth * 16}px` }}
        >
          {span.name}
        </div>
        <div
          className="truncate text-xs text-muted-foreground"
          style={{ paddingLeft: `${span.depth * 16}px` }}
        >
          {span.service}
        </div>
      </div>

      {/* Right column: timeline bar */}
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
      <div className="w-[300px] shrink-0 border-r border-border px-2 py-2 text-xs font-medium text-muted-foreground">
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

export function TraceWaterfall({ spans }: { spans: TraceSpan[] }) {
  const { flatSpans, traceStart, traceDuration } = useMemo(() => {
    const tree = buildSpanTree(spans)
    const flat = flattenTree(tree)

    // Calculate trace timeline bounds
    let minTimestamp = Infinity
    let maxEnd = -Infinity
    for (const span of spans) {
      minTimestamp = Math.min(minTimestamp, span.timestamp)
      maxEnd = Math.max(maxEnd, span.timestamp + span["span.duration_ms"])
    }

    return {
      flatSpans: flat,
      traceStart: minTimestamp,
      traceDuration: maxEnd - minTimestamp,
    }
  }, [spans])

  // Service color mapping (stable across renders)
  const serviceColorMap = useMemo(() => new Map<string, number>(), [])

  if (traceDuration <= 0) {
    return (
      <div className="flex h-64 items-center justify-center text-muted-foreground">
        Invalid trace data
      </div>
    )
  }

  return (
    <div className="flex flex-col overflow-auto rounded-md border border-border">
      <TimelineHeader traceDuration={traceDuration} />
      <div className="flex-1">
        {flatSpans.map((span) => (
          <SpanRow
            key={span.span_id}
            span={span}
            traceStart={traceStart}
            traceDuration={traceDuration}
            serviceColorMap={serviceColorMap}
          />
        ))}
      </div>
    </div>
  )
}
