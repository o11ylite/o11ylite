-- Per-group alert instances.
-- One row per (rule_id, fingerprint); fingerprint is the empty string for
-- rules without group-by (the degenerate single-instance case). Timestamps
-- are epoch milliseconds (INTEGER), matching alert_rules / scheduled_jobs.
CREATE TABLE IF NOT EXISTS alert_instances (
  rule_id      TEXT NOT NULL REFERENCES alert_rules(id) ON DELETE CASCADE,
  fingerprint  TEXT NOT NULL,            -- hash of canonical group-by pairs; '' for no group-by
  labels       TEXT NOT NULL,            -- JSON object of group-by column -> value

  state        TEXT NOT NULL CHECK(state IN ('ok', 'firing', 'resolved')),

  first_seen   INTEGER NOT NULL,         -- epoch ms, first time this fingerprint was observed
  last_seen    INTEGER NOT NULL,         -- epoch ms, display only; never drives transitions
  started_at   INTEGER,                  -- epoch ms, when firing began (Alertmanager startsAt)
  resolved_at  INTEGER,                  -- epoch ms

  last_value   TEXT,                     -- JSON of non-group columns from the breaching row (match rules)

  PRIMARY KEY (rule_id, fingerprint)
);
--;;
-- Lookups during evaluation and rollup are always scoped by rule.
CREATE INDEX IF NOT EXISTS idx_alert_instances_rule_state
  ON alert_instances (rule_id, state);
