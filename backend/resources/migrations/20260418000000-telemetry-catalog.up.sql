-- Telemetry catalog: service ↔ metric and service ↔ event-field ownership + liveness.
-- See .opencode/plans/telemetry-catalog.md for design rationale.

-- Which metrics each service emits.
CREATE TABLE IF NOT EXISTS service_metrics (
  service       TEXT NOT NULL,
  metric_name   TEXT NOT NULL,
  last_seen_at  INTEGER NOT NULL,  -- epoch ms
  PRIMARY KEY (service, metric_name)
);
--;;
-- Which event fields each service emits (core + attr.*).
-- Note: field type intentionally omitted. The DuckDB events schema is the
-- authoritative source of column types (cached in :cache/event-metadata).
CREATE TABLE IF NOT EXISTS service_event_fields (
  service       TEXT NOT NULL,
  field         TEXT NOT NULL,     -- e.g., 'trace_id', 'span.duration_ms', 'attr.http.method'
  last_seen_at  INTEGER NOT NULL,  -- epoch ms
  PRIMARY KEY (service, field)
);
--;;
-- Liveness tracking for services. NULL for existing rows until the catalog
-- buffer's first sweep after deploy.
ALTER TABLE service_metadata ADD COLUMN last_seen_at INTEGER;
