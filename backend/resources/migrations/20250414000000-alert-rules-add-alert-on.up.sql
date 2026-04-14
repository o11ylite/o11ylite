ALTER TABLE alert_rules ADD COLUMN alert_on TEXT NOT NULL DEFAULT 'result'
  CHECK(alert_on IN ('result', 'no_result'));
