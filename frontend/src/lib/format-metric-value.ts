// Unit-aware formatting for metric values.
//
// Handles common OpenTelemetry unit strings (By, s, ms, %, 1, etc.)
// and auto-scales to human-readable representations.

export interface UnitFormatter {
  /** Format for tooltip display, e.g., "1.07 GB", "42.5%" */
  format: (value: number) => string
  /** Format for Y-axis ticks (compact), e.g., "1.1G", "43%" */
  formatTick: (value: number) => string
}

// ---------------------------------------------------------------------------
// Scale definitions
// ---------------------------------------------------------------------------

interface ScaleStep {
  threshold: number
  suffix: string
  tickSuffix: string
}

const BYTE_SCALES: ScaleStep[] = [
  { threshold: 1e15, suffix: " PB", tickSuffix: "PB" },
  { threshold: 1e12, suffix: " TB", tickSuffix: "TB" },
  { threshold: 1e9,  suffix: " GB", tickSuffix: "GB" },
  { threshold: 1e6,  suffix: " MB", tickSuffix: "MB" },
  { threshold: 1e3,  suffix: " KB", tickSuffix: "KB" },
  { threshold: 1,    suffix: " B",  tickSuffix: "B" },
]

const SECOND_SCALES: ScaleStep[] = [
  { threshold: 86400, suffix: " d",   tickSuffix: "d" },
  { threshold: 3600,  suffix: " h",   tickSuffix: "h" },
  { threshold: 60,    suffix: " min", tickSuffix: "m" },
  { threshold: 1,     suffix: " s",   tickSuffix: "s" },
  { threshold: 1e-3,  suffix: " ms",  tickSuffix: "ms" },
  { threshold: 1e-6,  suffix: " us",  tickSuffix: "us" },
  { threshold: 1e-9,  suffix: " ns",  tickSuffix: "ns" },
]

const MILLISECOND_SCALES: ScaleStep[] = [
  { threshold: 86400000, suffix: " d",   tickSuffix: "d" },
  { threshold: 3600000,  suffix: " h",   tickSuffix: "h" },
  { threshold: 60000,    suffix: " min", tickSuffix: "m" },
  { threshold: 1000,     suffix: " s",   tickSuffix: "s" },
  { threshold: 1,        suffix: " ms",  tickSuffix: "ms" },
  { threshold: 1e-3,     suffix: " us",  tickSuffix: "us" },
]

const MICROSECOND_SCALES: ScaleStep[] = [
  { threshold: 3600e6,  suffix: " h",   tickSuffix: "h" },
  { threshold: 60e6,    suffix: " min", tickSuffix: "m" },
  { threshold: 1e6,     suffix: " s",   tickSuffix: "s" },
  { threshold: 1e3,     suffix: " ms",  tickSuffix: "ms" },
  { threshold: 1,       suffix: " us",  tickSuffix: "us" },
  { threshold: 1e-3,    suffix: " ns",  tickSuffix: "ns" },
]

const NANOSECOND_SCALES: ScaleStep[] = [
  { threshold: 3600e9, suffix: " h",   tickSuffix: "h" },
  { threshold: 60e9,   suffix: " min", tickSuffix: "m" },
  { threshold: 1e9,    suffix: " s",   tickSuffix: "s" },
  { threshold: 1e6,    suffix: " ms",  tickSuffix: "ms" },
  { threshold: 1e3,    suffix: " us",  tickSuffix: "us" },
  { threshold: 1,      suffix: " ns",  tickSuffix: "ns" },
]

const COUNT_SCALES: ScaleStep[] = [
  { threshold: 1e12, suffix: " T", tickSuffix: "T" },
  { threshold: 1e9,  suffix: " B", tickSuffix: "B" },
  { threshold: 1e6,  suffix: " M", tickSuffix: "M" },
  { threshold: 1e3,  suffix: " K", tickSuffix: "K" },
]

// ---------------------------------------------------------------------------
// Formatting helpers
// ---------------------------------------------------------------------------

/** Format a number with appropriate precision, trimming trailing zeros.
 *  Uses `decimals` for values >= 1, and `toPrecision` for smaller values
 *  so that e.g. 0.023 doesn't round to "0" with 1 decimal place.
 *  Defensively handles non-number inputs (strings, null, undefined) by
 *  converting or falling back to a safe display. */
function formatNumber(value: unknown, decimals: number): string {
  const num = typeof value === "number" ? value : Number(value)
  if (!Number.isFinite(num)) {
    return typeof value === "string" ? value : String(value)
  }
  if (num === 0) return "0"
  const abs = Math.abs(num)
  // For small values, use significant digits so 0.023 shows as "0.02" not "0"
  const s = abs < 1
    ? Number(num.toPrecision(Math.max(decimals, 2)))
        .toString()
    : num.toFixed(decimals)
  // Trim trailing zeros after decimal point, then trailing dot
  return s.replace(/(\.\d*?)0+$/, "$1").replace(/\.$/, "")
}

function scaleFormatter(scales: ScaleStep[], zeroLabel: string): UnitFormatter {
  return {
    format(value: number): string {
      if (value === 0) return `0${scales[scales.length - 1].suffix}`
      const abs = Math.abs(value)
      for (const { threshold, suffix } of scales) {
        if (abs >= threshold) return formatNumber(value / threshold, 2) + suffix
      }
      return formatNumber(value, 2) + (scales[scales.length - 1].suffix)
    },
    formatTick(value: number): string {
      if (value === 0) return zeroLabel
      const abs = Math.abs(value)
      for (const { threshold, tickSuffix } of scales) {
        if (abs >= threshold) return formatNumber(value / threshold, 1) + tickSuffix
      }
      return formatNumber(value, 1) + (scales[scales.length - 1].tickSuffix)
    },
  }
}

function percentFormatter(): UnitFormatter {
  return {
    format: (value) => formatNumber(value, 2) + "%",
    formatTick: (value) => formatNumber(value, 1) + "%",
  }
}

function countFormatter(): UnitFormatter {
  return {
    format(value: number): string {
      const abs = Math.abs(value)
      for (const { threshold, suffix } of COUNT_SCALES) {
        if (abs >= threshold) return formatNumber(value / threshold, 2) + suffix
      }
      return formatNumber(value, 2)
    },
    formatTick(value: number): string {
      const abs = Math.abs(value)
      for (const { threshold, tickSuffix } of COUNT_SCALES) {
        if (abs >= threshold) return formatNumber(value / threshold, 1) + tickSuffix
      }
      return formatNumber(value, 1)
    },
  }
}

/** Formatter for custom OTel annotation units like {request}, {packet}. */
function annotationFormatter(annotation: string): UnitFormatter {
  const cf = countFormatter()
  return {
    format: (value) => cf.format(value) + " " + annotation,
    formatTick: (value) => cf.formatTick(value),
  }
}

/** Default formatter: compact numbers with no unit suffix. */
function defaultFormatter(): UnitFormatter {
  return countFormatter()
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

const UNIT_MAP: Record<string, () => UnitFormatter> = {
  "By":  () => scaleFormatter(BYTE_SCALES, "0B"),
  "s":   () => scaleFormatter(SECOND_SCALES, "0s"),
  "ms":  () => scaleFormatter(MILLISECOND_SCALES, "0ms"),
  "us":  () => scaleFormatter(MICROSECOND_SCALES, "0us"),
  "ns":  () => scaleFormatter(NANOSECOND_SCALES, "0ns"),
  "%":   percentFormatter,
  "1":   countFormatter,
}

/**
 * Create a unit formatter for a given OpenTelemetry unit string.
 *
 * Supported units: By, s, ms, us, ns, %, 1, {annotation}, and null/empty.
 */
export function createUnitFormatter(unit: string | null | undefined): UnitFormatter {
  if (!unit) return defaultFormatter()

  // Exact match
  const factory = UNIT_MAP[unit]
  if (factory) return factory()

  // OTel annotation units: {request}, {packet}, etc.
  const annotationMatch = unit.match(/^\{(.+)\}$/)
  if (annotationMatch) return annotationFormatter(annotationMatch[1])

  // Unknown unit: show as suffix with compact number formatting
  const cf = countFormatter()
  return {
    format: (value) => cf.format(value) + " " + unit,
    formatTick: (value) => cf.formatTick(value),
  }
}

/**
 * Resolve the unit shared by a set of series.
 * Each series carries its own :unit (set by the backend for metric series
 * and for formula series whose operand units agree). Returns the unit when
 * every series with a unit shares the same value, or undefined when units
 * differ or no series carries one.
 */
export function resolveChartUnit(
  series: { unit?: string | null }[],
): string | null | undefined {
  if (series.length === 0) return undefined

  const unitSet = new Set<string | null>()
  for (const s of series) {
    if (s.unit !== undefined && s.unit !== null) {
      unitSet.add(s.unit)
    }
  }

  if (unitSet.size !== 1) return undefined

  const [unit] = unitSet
  return unit
}
