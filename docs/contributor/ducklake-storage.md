# DuckLake storage

The source carries the rationale. Read, in order:

- `backend/src/o11ylite/store/ducklake.clj` — tiered compaction
  (`compaction-tiers`), `with-both-writers` lock ordering, the catalog
  jobs (checkpoint, snapshot cleanup, inlined-data flush), and the
  header comment on why the small tier must stay a single unbounded
  pass (merge memory is O(files scanned)).
- `backend/src/o11ylite/components/duckdb_pool.clj` — one root
  connection, `.duplicate()` handles, writer pool size 1 per table.
- `backend/src/o11ylite/store/init.clj` and
  `components/core_config.clj` (`:metrics-partition-buckets`) —
  partition fan-out.

One fact the source does not state: there is deliberately NO
process-local schema cache. PR #169 deleted `:cache/events-schema`;
consumers call `schema/fetch-event-fields` directly. Do not reintroduce
one.

Measured behaviour of the compaction parameters (what the caps actually
bound, the bistable failure mode, the missing `max_input_files` primitive)
→ `docs/contributor/ducklake-compaction-experiments.md`.
