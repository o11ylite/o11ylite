# Alert-rule evaluation state machine

The state machine is data — the source is short and is the best
reference. Read, in order:

- `backend/src/o11ylite/alert_rule/transitions.clj` — the `transitions`
  map itself (keyed `[mode [stored-state presence]]` → effect map) and
  the ns docstring; `step` is the pure generic engine.
- `backend/src/o11ylite/alert_rule/eval.clj` — `step` runs over
  `all-fps = (into (set (keys stored-by-fp)) present-fps)`; ungrouped
  rules inject `fingerprint/empty-fingerprint` (the absence bootstrap);
  `-max-instances-per-rule` = 500.
- `backend/src/o11ylite/alert_rule/instance_store.clj` — SQLite
  `alert_instances`, one row per `(rule_id, fingerprint)`.
- `backend/src/o11ylite/alert_rule/store.clj` — `update!` clears
  instances only when `alert_on` changes.

API-level semantics: `skills/o11ylite/docs/alert-rules.md`.

The mode asymmetry is deliberate: match resolves by deleting the
instance row, absence by keeping it in `:ok`. Do not unify them —
resolved-match history would need new transitions, exclusion from the
500 cap, and a retention sweep. And do not seed instances at rule
create/update time; the empty-fingerprint injection in `eval.clj`
handles the ungrouped case and an earlier seeding helper was removed.
