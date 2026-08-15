# DuckLake compaction — measured behaviour

Measured 2026-08-06 against DuckDB v1.5.5 (Variegata) + the bundled
DuckLake extension, while diagnosing a backlog incident. Read this before
proposing a compaction fix: two plausible fixes were disproved by
experiment, and the correct ask is a bind-time input cap.

## Get the real function signature from the binary, not the docs

The published docs list the advanced options in prose and omit the exact
arity. Enumerate it instead:

```sql
INSTALL ducklake; LOAD ducklake;
SELECT function_name, parameters, parameter_types
FROM duckdb_functions()
WHERE function_name ILIKE '%merge_adjacent%';
```

Result (v1.5.5):

```
[col0, col1, schema, max_compacted_files, max_file_size, min_file_size]
[col0,             max_compacted_files, max_file_size, min_file_size]
```

There is no time filter, no partition filter, no `older_than`, no `WHERE`.
Any design that assumes "just compact the recent partition" cannot be
expressed with this API.

## DISPROVED: `max_compacted_files` does not bound the scan

The intuition ("cap the batch so the merge fits in memory") is wrong. It
caps the number of OUTPUT files; the input scan is unchanged.

Measure `files_processed`, not RSS — it is the direct observable. The
merge's return rows carry `files_processed`; summing it tells you exactly
how many input files the call consumed. RSS is a noisy proxy.

| small files | uncapped s | uncapped RSS | uncapped processed | capped s | capped RSS | capped processed |
|---|---|---|---|---|---|---|
| 500  | 0.53 | 215 MB  | 500  | 0.62 | 207 MB  | 500  |
| 2000 | 0.91 | 640 MB  | 2000 | 0.82 | 642 MB  | 2000 |
| 6000 | 1.71 | 1199 MB | 6000 | 1.81 | 1266 MB | 6000 |

With `max_compacted_files => 10` the call still processed every eligible
file — 6000 of them.

Memory is linear in total eligible file count, ~0.2 MB/file. Extrapolating:
350k small files ≈ 70 GB+ of bind-time metadata, consistent with the
upstream code comment ("333K files / 2.5 GB consumed 30 GB of RAM").

Corollary: looping with a cap does not help and cannot terminate under
continuous ingest — each iteration pays full scan cost while new files
arrive.

### Pitfall: `getrusage(RUSAGE_CHILDREN)` is cumulative

`resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss` is a high-water
mark across every child the process has ever reaped. It never decreases and
is not attributable to the child you just ran. It reported the peak from
the data-building phase, not the merge.

Constant numbers across varying inputs mean a broken instrument, not a
robust finding — especially when they agree with the conclusion you already
favour. Use true per-process peak (`VmHWM` from `/proc/<pid>/status`, polled
while the child runs) — see the harness below.

## CONFIRMED: `max_file_size` already gives you the time window for free

In a time-series store, file size is a proxy for age — old data has already
been merged into large files, so a size ceiling excludes it. Verified:
built 3,000 tiny files over 10 old days, compacted them to a large-file
tail, then added 200 fresh small files.

```
NO-OP merge over the large tail:        0.20s  (0 processed, 0 created)
merge with 200 eligible + large tail:   0.56s  (200 processed, 1 created)
```

Only the 200 new files were scanned and merged. The compacted history cost
0.2s. So "only scan recent data" is already the steady-state behaviour; no
new parameter is needed for the healthy case.

`merge_adjacent_files` is also partition-aware: 1,200 tiny files across 3
day-partitions merged to exactly 3 outputs, one per partition.

## The actual failure mode is bistable, not "scans too much"

- Healthy: old files are large → `max_file_size => 1MB` prunes them → scan
  is a few hundred files → merge is cheap and fast.
- Behind: a backlog is by definition a pile of small files → the same size
  filter now matches every one of them → scan cost explodes → OOM →
  nothing is freed → backlog grows. Self-reinforcing, no path back.

The size filter that normally bounds the scan stops working at precisely
the moment it is needed. That is why the Aug 2026 incident required a
manual purge instead of self-healing.

The correct upstream ask is therefore neither "loop" nor "add a time
filter", but a bind-time input cap — e.g. `max_input_files` / "merge at
most N oldest eligible files this call". That is the one missing primitive
that would let a starved small tier recover incrementally.

## Reproduction harness

Build a synthetic backlog (one INSERT = one snapshot = one parquet file per
partition), then measure. Building a few thousand files takes ~45 s and the
scaling sweep runs minutes.

```python
import subprocess, threading, time, glob

def peak_rss_run(cmd, timeout=1800):
    """Run cmd; return (stdout, stderr, elapsed, true peak RSS in KB)."""
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    peak, stop = [0], threading.Event()
    def mon():
        path = f"/proc/{p.pid}/status"
        while not stop.is_set():
            try:
                with open(path) as f:
                    for line in f:
                        if line.startswith("VmHWM:"):
                            peak[0] = max(peak[0], int(line.split()[1])); break
            except (FileNotFoundError, ProcessLookupError):
                return
            time.sleep(0.01)
    t = threading.Thread(target=mon, daemon=True); t.start()
    t0 = time.time(); out, err = p.communicate(timeout=timeout)
    stop.set(); t.join(timeout=1)
    return out.strip(), err.strip(), time.time() - t0, peak[0]
```

Prefer a functional signal over a resource signal. `files_processed` from
the merge's own return value is exact, cheap, and answers "did the cap
bound the input?" directly. Reach for RSS only when the question is
genuinely about memory.

SQL under test:

```sql
CALL ducklake_set_option('lake','target_file_size','5MB');
SELECT coalesce(sum(files_processed),0) proc, count(*) created
FROM ducklake_merge_adjacent_files('lake', max_file_size => 1048576);
```

Isolating "does it scan the old tail?" needs a controlled experiment: hold
the eligible (small) file count FIXED and vary only the already-compacted
tail. Varying both at once cannot separate the two effects.

| old_days | total files | merge s | peak RSS | processed |
|---|---|---|---|---|
| 2   | 203 | 0.46 | 128 MB | 200 |
| 10  | 211 | 0.50 | 133 MB | 200 |
| 40  | 241 | 0.59 | 126 MB | 200 |
| 120 | 321 | 0.71 | 129 MB | 200 |

Total files 203 → 321, memory flat, `processed` pinned at exactly 200.

## Reading whether compaction is keeping up

o11ylite emits a span per tier run; query its own API:

```json
{"time_range":{"start":<ms>,"end":<ms>},
 "visualization":{"type":"table","sort":{"field":"timestamp","order":"desc"},
   "displayed_fields":["timestamp","name",
     "attr.o11ylite.ducklake.compaction.tier_name",
     "attr.o11ylite.ducklake.compaction.files_created",
     "attr.o11ylite.ducklake.compaction.files_processed","span.duration_ms"]},
 "filter":{"field":"name","op":"contains","value":"merge-adjacent"},
 "limit":25}
```

Healthy output looks like `tier=small created=36 processed=2711 dur=9.6s`
every ~5.5 min. Compare `processed` per run against the file-creation rate;
roughly equal means break-even, which is fine.

File count plateaus rather than dropping — that is expected, not a leak.
Merged-away inputs are not unlinked until `snapshot-cleanup` expires them
(`older_than => 1 hour`, job runs every 30 min). Steady state therefore
sits around `creation_rate × 60-90 min`. Judge health by "is compaction
succeeding each cycle", not by "is the number going down".
