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
