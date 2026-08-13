# UI verification recipes

For driving the UI headlessly (screenshots, E2E checks). Re-verify the
selectors against the current tree if the UI changes. API
request/response shapes live in `skills/o11ylite/docs/`
(events-query.md, metrics-query.md, alert-rules.md) — this file covers
only what sits between the agent and the running app.

## Routes

`/` redirects (302) to `/explore`. Navigate to a real Inertia route:
`/explore`, `/alert-rules`, `/notebooks`.

## Demo data

The backend's own OpenTelemetry javaagent self-ingests continuously — no
seeding needed. Reliable demo series: `jvm.memory.used` grouped by
`attr.jvm.memory.pool.name` (~8 series).

## Explore selectors

- Radix tabs: role `tab`, not `button`.
- Add-metric control: accessible name `/add metric/i` (no literal `+`).
- Group-by comboboxes are positional (positions 0–3).
- Command palette input: `[cmdk-input]`; run: the Run button.
- Render-as / overlay options live behind the gear popover.

## Seeding alert-rule UI state via REPL

Do not drive the alert-rule creation form (comboboxes appear only after
an aggregation). Seed via REPL and load `/alert-rules/<rid>/edit`, which
serves the `instances` prop and initializes the form in the seeded shape.

- The `o11ylite.alert-rule` facade re-exports the store fns; each takes
  the DB component as its first arg:
  `(require '[integrant.repl.state :refer [system]])`,
  `(def sqlite (:db/sqlite system))`,
  `(def duckdb (:db/duckdb-reader system))`. Zero-arity → `ArityException`.
- `store/update!` SETs every field unconditionally — pass the full rule
  map (field list: `skills/o11ylite/docs/alert-rules.md`); missing keys
  become NULL, no validation error.
- Firing+ok mix: grouped absence rule (`:alert_on "no_result"`), evaluate
  once → all `:ok`; `update!` with a narrowed filter; re-evaluate → the
  dropped groups fire. IDs:
  `(com.github.f4b6a3.uuid.UuidCreator/getTimeOrderedEpoch)` — mirrors
  `alert_absence_group_test.clj`.
- Empty state: `create!` and never evaluate.
- Cleanup: `store/delete!`, then confirm
  `(count (store/list-all sqlite))` is back to the prior count — the dev
  SQLite file persists between sessions.

## Frontend query calls (not in the API docs)

Explore fires TWO `POST /api/query/events` — table + count-over-time.
Frontend mocks must branch on `body.visualization?.type`, or the aux
chart crashes.
