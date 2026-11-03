# Trace lookup without a time range

The trace page requires a time range. A trace id link
(`/trace/:id`) does not carry one, so opening a link falls back to the
default `now-15m..now`. Any trace older than that shows no spans.
Appending `from`/`to` to the link only moves the problem: relative
ranges keep moving, absolute ranges are brittle, and the link still
stops working when it falls outside the window.

The only expiry we want is data retention.

## Why the time range exists

`/api/query/events` requires `time_range`
(`backend/src/o11ylite/store/events/query_schema.clj#L182`), and the
trace query always adds `timestamp >= start` / `< end`
(`backend/src/o11ylite/store/events/query.clj#L371-L372`). Without a
time bound the query has to look at every file that might contain the
trace id.

The events table is sorted by `timestamp` and partitioned by day and
service bucket. `trace_id` is random, so its min/max stats cannot skip
anything. A time bound is currently the only useful pruning.

## What changed since the original design

The repository now uses DuckDB 2.0 and DuckLake 1.1. Three relevant
changes:

- DuckDB reads and writes Parquet Bloom filters for equality
  predicates (since 1.2). The writer emits one per column per row
  group when the column is dictionary-encoded. The reader probes them
  transparently and skips row groups that cannot match.
- DuckLake still has no secondary indexes. `CREATE INDEX` on a
  DuckLake table fails with `DuckLake does not support indexes`, and
  the DuckLake spec lists indexes as unlikely to be supported. DuckDB
  ART indexes only work on native DuckDB tables.
- DuckLake 1.1 has `ducklake_table_insertions(catalog, schema,
  table, start_snapshot, end_snapshot)`, which returns rows inserted
  between two snapshots. This makes it possible to build a derived
  trace index without touching the ingest hot path.

## Options

### A. Drop the time range and rely on Parquet Bloom filters

This is a tiny change: make `time_range` optional for trace queries,
then run the trace query over all retained data. DuckDB will use the
Bloom filters when they exist.

It works well when a row group contains several spans of the same
trace. A Bloom filter is only written when DuckDB keeps the trace id
column dictionary-encoded. When distinct trace ids are a large
fraction of the rows, DuckDB falls back to plain encoding and writes
no filter. Single-span traces are the extreme case; traces whose spans
are spread across many row groups have the same problem. Those queries
read the whole trace id column.

It also scales with file count. Every lookup opens every file to read
its Bloom filters; files without filters are scanned.

Measured on synthetic 30-day tables using the production schema, zstd,
32 MB compaction, local SSD, warm cache, median of repeated point
queries:

| Dataset | Files | Bloom on `trace_id` | Trace query, all data | Trace query, bounded |
|:--|--:|:--|--:|--:|
| 1M traces x 6 spans (6M events, 196 MB) | 98 | yes | 22.7 ms | 18.2 ms |
| 6M single-span traces (6M events, 235 MB) | 224 | no | 171.7 ms (143 MB read) | 3.1 ms |

So this is a good **fallback**, not a guarantee. It is also the
cheapest first step.

### B. Exact trace time index (recommended)

Maintain a small table:

```
trace_index(trace_id, min_ts, max_ts)
```

sorted by `trace_id`. A point lookup returns the exact time range; the
events query then runs with that range, exactly like today. No false
positives, no full-table scan, and no dependence on how many spans a
trace has.

Measured with 6M traces:

| Index | Size | Point lookup |
|:--|--:|--:|
| DuckLake table, 64 trace buckets | 195 MB | 14.9 ms |
| DuckLake table, 256 trace buckets | 192 MB | 11.9 ms (756 KB read) |
| DuckLake table, unpartitioned, sorted | 187 MB | 18.0 ms (3.8 MB read) |
| SQLite table, `trace_id BLOB PRIMARY KEY` | 263 MB | 0.008 ms |

Adding the DuckLake lookup to the events query gives about 18.6 ms
end-to-end, which is in the same range as today's bounded query. SQLite
is much faster but adds a second store and its own retention/backup
story.

The index is about 31 MB per 1M traces in DuckLake, or roughly 16% on
top of the 196 MB event dataset above. Single-span workloads pay more
because the event rows are smaller; that is exactly the case where the
index is most valuable.

Why not a Bloom filter instead:

- A Bloom filter can only say "this trace might be in this time
  bucket". It cannot return the range. You still have to query the
  bucket, so bucket size and event volume decide the latency.
- To make buckets small enough, you need many filters. False positives
  then select a percentage of all buckets, and that percentage grows
  with the dataset.
- The exact index is simpler to reason about, needs no false-positive
  tuning, and is still small relative to the events it indexes.

A time-bucket Bloom filter is a reasonable space optimization if trace
volume makes 31 MB/1M traces too expensive, but it is a second design
problem, not a simpler one.

### C. Repartition or sort events by trace id

DuckLake supports one sort order per table. Sorting events by
`trace_id` makes time-range queries scan much more data, which is the
opposite of what the rest of the product needs. Adding
`bucket(N, trace_id)` to the partition spec would prune trace lookups
to one bucket but multiplies the partition/file count by N for every
time-range query. Not viable.

### D. `CREATE INDEX` on DuckLake

Not supported. Confirmed by running it:

```
Not implemented Error: DuckLake does not support indexes
```

DuckDB ART indexes only apply to native DuckDB tables. They are not
buffer-managed and must fit in memory during creation; a 30-day trace
index would not.

## Recommendation

1. Make `time_range` optional for trace visualization. If it is
   present, ignore it for trace lookups; a trace id is already unique.
2. Add an exact `trace_index` table in DuckLake. Build it
   asynchronously from `ducklake_table_insertions`, not in the ingest
   path.
3. On lookup, use the index range when available. If the index has no
   row yet (new trace, backfill still running, index lost), fall back
   to the time-range-free events query. The fallback keeps links
   correct; the index keeps them fast.
4. Remove the time range selector from the trace page. Retention is
   the only expiry.
5. Update the MCP `get_trace` tool and the agent skill docs: no
   default 24h window.

Keeping the index out of the query schema for all other
visualizations is deliberate. Only trace lookup has a unique key that
makes a time range unnecessary.

## Implementation sketch

### Table

```sql
CREATE TABLE IF NOT EXISTS o11ylite.trace_index (
  trace_id VARCHAR NOT NULL,
  min_ts TIMESTAMP_NS NOT NULL,
  max_ts TIMESTAMP_NS NOT NULL
);

ALTER TABLE o11ylite.trace_index SET SORTED BY (trace_id ASC);

-- sort_on_insert is disabled catalog-wide for ingest throughput; the index
-- needs it so each incremental batch is written in trace-id order.
CALL o11ylite.set_option('sort_on_insert', true, table_name => 'trace_index');
```

Create it from `store/init.clj` next to the events/metrics tables. Do
not add a SQLite migration for this; DuckLake owns the table.

### Incremental build

Run a scheduler job every minute:

```sql
INSERT INTO o11ylite.trace_index
SELECT trace_id, min(timestamp), max(timestamp)
FROM ducklake_table_insertions(
       'o11ylite', 'main', 'events', :from_snapshot, :to_snapshot)
WHERE "meta.signal_type" IN ('span', 'span_event')
  AND trace_id IS NOT NULL
GROUP BY trace_id;
```

Store the last processed snapshot in the existing `kv` table. Reads
from the change feed are cheap: 6M inserted rows took ~40 ms, and the
group-by aggregation took ~143 ms in the benchmark.

Each run appends rows. A trace with spans in multiple runs gets
multiple rows; the lookup aggregates them with `min`/`max`. This is
append-only on purpose. `MERGE` would be correct too, but repeated
updates create delete files that have to be rewritten. If duplicate
rows become a storage problem, run a daily dedupe:

```sql
CREATE OR REPLACE TABLE o11ylite.trace_index AS
SELECT trace_id, min(min_ts) AS min_ts, max(max_ts) AS max_ts
FROM o11ylite.trace_index
GROUP BY trace_id;
```

Reapply `SORTED BY` and the table options afterwards.

### Backfill

Record the current snapshot before starting. Walk the events table in
day chunks, inserting `group by trace_id` rows. When the walk
finishes, replay `table_insertions` from the recorded snapshot. If
snapshots that old have already been expired, run the backfill again;
do not advance the watermark over a gap.

Backfill is a background task. Trace links use the fallback until the
index is ready.

### Snapshot retention

`run-snapshot-cleanup!` expires snapshots older than one hour
(`backend/src/o11ylite/store/ducklake.clj#L371`). The index job must
therefore run more often than once an hour. On startup, compare the
stored watermark with the oldest available snapshot; if the watermark
is older, rebuild.

### Lookup and fallback

In the trace query path, before building SQL:

- `SELECT min(min_ts), max(max_ts) FROM o11ylite.trace_index WHERE trace_id = ?`
- found: use `[min_ts - 1ms, max_ts + 1ms]`
- not found: build the same query without a time predicate

The fallback is the Bloom-filter-assisted scan from option A. It is
slower on high-cardinality data but always correct.

### Retention

Add `trace_index` to `delete-old-data!`:

```sql
DELETE FROM o11ylite.trace_index
WHERE max_ts < NOW() - INTERVAL '<retention-days> days'
```

The existing checkpoint rewrites files with deletes. The catalog-wide
`ducklake_merge_adjacent_files('o11ylite')` call also compacts the
index files; no new compaction job is needed.

### API, UI, and agent surface

- Make `time_range` optional in `events-query` for
  `visualization.type = "trace"`, and require it for every other
  visualization.
- `frontend/src/pages/Trace.tsx`: drop `useTimeRange` and
  `showTimeRange`; send only the trace filter and visualization.
  Update the empty-state copy to mention retention, not the selected
  range.
- `frontend/src/pages/Trace.test.tsx`: assert the request has no
  `time_range` and the layout has no time selector.
- `backend/src/o11ylite/mcp/tools/telemetry.clj`: remove the 24h
  default from `get_trace`; the API resolves the range.
- `skills/o11ylite/docs/events-query.md`: mark `time_range` optional
  for trace visualization.

## Risks

- **Index lag.** New traces may not be in the index yet. The fallback
  covers this, but if the index job is stuck the page silently gets
  slower. Emit a metric/span for index lag.
- **Snapshot gap.** If the app is down longer than snapshot retention,
  the change feed cannot resume. Rebuild instead of advancing the
  watermark.
- **Index size.** Single-span workloads pay close to the event storage
  size. Measure before enabling for very high-volume deployments. The
  fallback makes it safe to skip the index on those deployments.
- **Compaction load.** The index adds files to the same catalog-wide
  merge. It is small, but watch file counts if the index job writes
  every minute without compaction.

## Open questions

- Should the index live in DuckLake (one store, 18 ms lookup) or
  SQLite (8 µs lookup, separate store)? This document recommends
  DuckLake for operational simplicity; the lookup interface is small
  enough to swap later.
- Is a 1-minute index lag acceptable, or should the index be updated
  from the event batch itself? Updating in the batch removes the lag
  but puts the index on the ingest hot path.
- Do we need to dedupe trace index rows, or is the append-only size
  acceptable at 30-day retention?

## References

- [Parquet Bloom filters in DuckDB](https://duckdb.org/2025/03/07/parquet-bloom-filters-in-duckdb)
- [DuckLake unsupported features](https://ducklake.select/docs/stable/duckdb/unsupported_features)
- [DuckLake sorted tables](https://ducklake.select/docs/stable/duckdb/advanced_features/sorted_tables)
- [DuckLake data change feed](https://ducklake.select/docs/stable/duckdb/advanced_features/data_change_feed)
- [Grafana Tempo block format](https://grafana.com/docs/tempo/latest/reference-tempo-architecture/block-format/) — a production example of sorted trace blocks with Bloom filters and a trace index.
