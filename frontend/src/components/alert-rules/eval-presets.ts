export const EVAL_WINDOW_PRESETS = [
  { value: "60000", label: "1 minute" },
  { value: "300000", label: "5 minutes" },
  { value: "900000", label: "15 minutes" },
  { value: "1800000", label: "30 minutes" },
  { value: "3600000", label: "1 hour" },
]

export const EVAL_INTERVAL_PRESETS = [
  { value: "60000", label: "1 minute" },
  { value: "300000", label: "5 minutes" },
  { value: "900000", label: "15 minutes" },
  { value: "1800000", label: "30 minutes" },
  { value: "3600000", label: "1 hour" },
]

const EVAL_LABELS: Record<number, string> = {
  60000: "1m",
  300000: "5m",
  900000: "15m",
  1800000: "30m",
  3600000: "1h",
}

export function formatMs(ms: number): string {
  return EVAL_LABELS[ms] ?? `${ms}ms`
}
