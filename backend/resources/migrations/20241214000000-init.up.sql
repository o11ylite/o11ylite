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
--;;
-- Scheduled jobs for periodic background tasks
-- Jobs are defined in code and upserted on startup
CREATE TABLE IF NOT EXISTS scheduled_jobs (
  job_name TEXT PRIMARY KEY,
  interval_ms INTEGER NOT NULL,        -- milliseconds between runs
  last_run_at INTEGER,                 -- epoch ms, when job last completed (NULL = never)
  last_success_at INTEGER,             -- epoch ms, when job last succeeded (NULL = never)
  last_error TEXT,                     -- error message from last failure (NULL = no error)
  enabled INTEGER DEFAULT 1,           -- 0 = disabled, 1 = enabled
  created_at INTEGER NOT NULL,         -- epoch ms
  updated_at INTEGER NOT NULL          -- epoch ms
);
--;;
-- Alert rules for threshold-based alerting
-- Query is stored as a nippy-frozen BLOB (same shape as /api/query/* payloads minus time_range)
-- State is tracked by the evaluation engine
CREATE TABLE IF NOT EXISTS alert_rules (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  enabled INTEGER NOT NULL DEFAULT 1,

  query_mode TEXT NOT NULL CHECK(query_mode IN ('events', 'metrics')),
  query BLOB NOT NULL,                   -- nippy: full query payload (filters, aggregations, having, metrics, etc.)

  eval_window_ms INTEGER NOT NULL,       -- how far back to look when evaluating
  eval_interval_ms INTEGER NOT NULL,     -- how often to evaluate

  state TEXT NOT NULL DEFAULT 'ok' CHECK(state IN ('ok', 'firing', 'no_data')),
  state_changed_at INTEGER,              -- epoch ms
  last_eval_at INTEGER,                  -- epoch ms
  last_eval_error TEXT,

  created_at INTEGER NOT NULL,           -- epoch ms
  updated_at INTEGER NOT NULL            -- epoch ms
);
--;;
-- Notebooks for multi-query saved investigations
CREATE TABLE IF NOT EXISTS notebooks (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  description TEXT,
  created_at INTEGER NOT NULL,           -- epoch ms
  updated_at INTEGER NOT NULL            -- epoch ms
);
--;;
-- Individual query cells within a notebook
-- Query stored as nippy BLOB (same as alert_rules)
-- Pinned time freezes a cell to absolute timestamps; NULL means use global time
CREATE TABLE IF NOT EXISTS notebook_cells (
  id TEXT PRIMARY KEY,
  notebook_id TEXT NOT NULL REFERENCES notebooks(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  title TEXT,
  query_mode TEXT NOT NULL CHECK(query_mode IN ('events', 'metrics')),
  query BLOB NOT NULL,
  pinned_from TEXT,                       -- NULL = use global time, absolute ISO timestamp when pinned
  pinned_to TEXT,                         -- NULL = use global time, absolute ISO timestamp when pinned
  created_at INTEGER NOT NULL,            -- epoch ms
  updated_at INTEGER NOT NULL             -- epoch ms
);

