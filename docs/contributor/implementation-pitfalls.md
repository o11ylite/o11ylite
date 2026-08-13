# Implementation pitfalls

Stack-specific traps that are likely to recur. Each entry names the file
that owns the behaviour.

## Closed query schemas reject unregistered keys

Most query schemas in `store/{events,metrics}/query_schema.clj` are
`[:map {:closed true}]`: an unregistered frontend key 400s with
`disallowed key`. Render-only persistent state must be registered
`[:optional true]` in the closed map AND added to `Visualization` in
`frontend/src/types/index.ts` (cf. `hidden_metrics`).

## Frontend traps

- Root `frontend/tsconfig.json` sets `"files": []` (project references),
  so plain `tsc --noEmit` checks nothing. Always `npx tsc -b --noEmit`
  from `frontend/`.
- Inertia re-mounts the layout per visit, so uncontrolled toggle state
  resets on navigation. Persist with `@/hooks/use-local-storage`
  (`useLocalStorage`), key `o11ylite.<surface>.<thing>` (cf.
  `components/ui/sidebar.tsx`).
- Attribute field grouping: import `groupAttributeFields` from
  `frontend/src/lib/group-fields.ts`; do not reimplement.

## Metrics integration tests and time

Derive fixture `:time-ns` and query `:time_range` from one `bucket-time`
(see `backend/test/o11ylite/integration/api/metric_query_test.clj`).
Hardcoded epochs silently return empty series.

## API error conventions

A missing or unknown field is a validation error — return 400 and let
the frontend render it via `ResultsError`
(`components/results/results-error.tsx`). Do not introduce HTTP 200 +
embedded `data.error` "soft error" responses for validation-class
failures.

## Migrations

Migrations ship tables and indexes only. No data backfills for state the
runtime repopulates (`alert_instances` rows re-mint every eval tick);
backfill only durable, unrecoverable state.

## Renovate Java bumps

Renovate raises only the Dockerfile runtime stage
(`eclipse-temurin:N-jre-noble`); the build stage is a different image it
cannot pair, so a runtime-only bump desyncs build/runtime majors and
reddens CI. Merge a coordinated sibling PR bumping `.tool-versions`,
both Dockerfile stages, and the `DEVELOPMENT.md` Java reference, then
close Renovate's PR as superseded.
