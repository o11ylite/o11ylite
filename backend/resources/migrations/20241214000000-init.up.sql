-- Initialize o11ylite SQLite database

-- Key-value store for system metadata (e.g., cache timestamps)
-- Uses BLOB for values, serialized with nippy
CREATE TABLE IF NOT EXISTS kv (
  key TEXT PRIMARY KEY,
  value BLOB NOT NULL
);
--;;
-- Service registry
-- Services are discovered from telemetry and persist independently
CREATE TABLE IF NOT EXISTS service_metadata (
  service TEXT PRIMARY KEY,
  first_seen_at INTEGER NOT NULL,  -- epoch ms, when first discovered
  updated_at INTEGER NOT NULL      -- epoch ms, last metadata update
  -- future: team TEXT, language TEXT, description TEXT, etc.
);
--;;
-- Metrics metadata (lookup, low write frequency)
-- Histogram boundaries stored here to avoid duplication in hot path
CREATE TABLE IF NOT EXISTS metrics_metadata (
  name TEXT PRIMARY KEY,
  description TEXT,
  unit TEXT,
  metric_type TEXT CHECK(metric_type IN ('gauge', 'sum', 'histogram')),
  original_temporality TEXT CHECK(original_temporality IN ('cumulative', 'delta')),
  is_monotonic INTEGER,               -- 0/1, NULL for gauge/histogram
  hist_boundaries TEXT,               -- JSON array: "[0.5, 1.0, 2.5, 5.0, 10.0]"
  attributes TEXT,                    -- JSON array of attribute names: ["host.name", "cpu.core"]
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now'))
);

