# Metrics Query

Query time-series metric data (gauges, counters, histograms) stored in o11ylite.

## Endpoints

| Method | Path                  | Description                         |
|--------|-----------------------|-------------------------------------|
| GET    | `/api/metrics`        | List all metrics (name, type, unit) |
| GET    | `/api/metrics/:name`  | Get detailed metadata for a metric  |
| POST   | `/api/query/metrics`  | Query metric time series            |

All endpoints require `Authorization: Bearer <token>`.

---

## GET /api/metrics

**Response:**
```json
[
  {"name": "cpu.utilization", "metric_type": "gauge", "unit": "%"},
  {"name": "http.server.duration", "metric_type": "histogram", "unit": "ms"},
  {"name": "http.server.requests", "metric_type": "sum", "unit": "1"}
]
```

## GET /api/metrics/:name

**Response (200):**
```json
{
  "name": "http.server.duration",
  "description": "Duration of HTTP server requests",
  "unit": "ms",
  "metric_type": "histogram",
  "temporality": "delta",
  "attributes": ["attr.http.method", "attr.http.route", "attr.http.status_code"],
  "hist_boundaries": [0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10]
}
```

**Response (404):** `{"error": "metric_not_found", "name": "nonexistent"}`

Use the `attributes` list to know which fields are valid for `filter` and `group_by`.

---

## POST /api/query/metrics

### Request body

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "metrics": [
    {"id": "A", "name": "cpu.utilization", "agg": "avg"}
  ],
  "bucket_ms": 60000,
  "filter": { ... },
  "group_by": ["attr.host.name"],
  "having": { ... },
  "formulas": [ ... ]
}
```

| Field       | Required | Description                                            |
|-------------|----------|--------------------------------------------------------|
| `time_range`| Yes      | `{start, end}` — Unix epoch milliseconds              |
| `metrics`   | Yes      | Array of metric definitions (min 1)                    |
| `bucket_ms` | No       | Bucket width in ms; auto-selected if omitted           |
| `filter`    | No       | Global filter applied to all metrics                   |
| `group_by`  | No       | Array of attribute names to group by (shared across metrics) |
| `having`    | No       | Post-aggregation numeric filter                        |
| `formulas`  | No       | Derived series computed over query results (max 10)    |
| `visualization` | No  | `{type: "time_series", ...}` — see Visualization below |

### Metric definition

```json
{"id": "A", "name": "cpu.utilization", "agg": "avg", "filter": { ... }}
```

- `id` — single uppercase letter A–Z (unique within the query)
- `name` — metric name (e.g., `cpu.utilization`)
- `agg` — aggregation function (depends on metric type, see below)
- `filter` — optional per-metric filter (same syntax as global filter)

### Aggregations by metric type

| Type        | Allowed aggregations                  |
|-------------|---------------------------------------|
| `gauge`     | `sum`, `avg`, `min`, `max`, `last`    |
| `sum`       | `sum`, `rate`                         |
| `histogram` | `count`, `sum`, `avg`, `min`, `max`   |

Use `GET /api/metrics/:name` to find a metric's type before querying.

### Filter expression

Same syntax as events query. Operators: `=`, `!=`, `contains`, `exists`, `starts-with`. Combine with `and`/`or`.

```json
{"field": "attr.host.name", "op": "=", "value": "server-1"}
```

### Having expression

Filter on aggregation results. Drops time-buckets where the predicate
is false.

```json
{"ref": "A", "op": ">", "value": 90}
```

- `ref` — a declared metric id (`A`–`Z`) or formula id (`F1`–`F9`)
- `op` — `>`, `<`, `>=`, `<=`, `=`, `!=`

When `ref` targets a formula, only that formula's series is filtered;
the source metric series it derives from are returned in full.

### Formulas

Compute derived series from query metrics. Source metric series are
returned alongside formula series — the frontend decides what to render.

```json
{"id": "F1", "expr": "A / B * 100", "name": "free mem %", "unit": "%"}
```

| Field   | Required | Description                                                    |
|---------|----------|----------------------------------------------------------------|
| `id`    | Yes      | `F1`–`F9` (distinct namespace from metric IDs A–Z)             |
| `expr`  | Yes      | Expression using `+ - * /`, parens, decimals, metric refs      |
| `name`  | No       | Display name; result `name` becomes `"<id>: <name>"`           |
| `unit`  | No       | Unit string surfaced in `units[<id>: <name>]`                  |

Semantics:

- Inner-join on `(bucket, labels)`. Source series share `group_by` so
  labels match by construction.
- Buckets where any operand is missing — or where division by zero
  occurs — are dropped (no `null` / `NaN` propagation).
- Each formula must reference at least one metric; constant-only
  expressions like `1 + 2` are rejected at validation time.
- Synthetic formula series carry `:metric null`, `:formula <expr>`, and
  the requested `:unit` (when supplied).

---

### Visualization (`time_series`)

Controls how query results are rendered. All fields optional.

```json
{
  "type": "time_series",
  "bucket_ms": 60000,
  "overlay": true,
  "render_as": "line",
  "hidden_metrics": ["A", "B"]
}
```

| Field            | Type               | Default | Description                                              |
|------------------|--------------------|---------|----------------------------------------------------------|
| `bucket_ms`      | integer            | auto    | Bucket width in ms                                       |
| `overlay`        | boolean            | `false` | When multiple series are present, draw them in a single chart instead of one chart per metric |
| `render_as`      | `"line"` \| `"stacked_area"` \| `"bar"` | `"line"` | How to draw each series. Stacked area and bar treat missing buckets as 0 so the stack has no holes; pick either for part-of-whole views |
| `hidden_metrics` | string[]           | `[]`    | Source-metric IDs whose series should be hidden from the chart. Render-only — the backend ignores this for query execution but persists it (e.g. in notebook cells) so the hiding state survives reloads |

---

### Response

```json
{
  "data": {
    "bucket_ms": 60000,
    "start_ms": 1700000000000,
    "end_ms": 1700003600000,
    "series": [
      {
        "id": "A",
        "metric": "cpu.utilization",
        "name": "avg(cpu.utilization)",
        "labels": {"attr.host.name": "server-1"},
        "data": [
          {"timestamp": 1700000000000, "value": 72.5},
          {"timestamp": 1700000060000, "value": 68.3}
        ]
      }
    ]
  },
  "metadata": {"query_time_ms": 42}
}
```

One series per `(labels, metric)` combination. `labels` is `{}` when no `group_by`.

---

## Examples

### Average CPU by host over the last hour

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "metrics": [{"id": "A", "name": "cpu.utilization", "agg": "avg"}],
  "group_by": ["attr.host.name"]
}
```

### Request rate in production

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "metrics": [{"id": "A", "name": "http.server.requests", "agg": "rate"}],
  "filter": {"field": "attr.env", "op": "=", "value": "prod"},
  "group_by": ["attr.http.route"]
}
```

### Multiple metrics in one query

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "metrics": [
    {"id": "A", "name": "http.server.duration", "agg": "avg"},
    {"id": "B", "name": "http.server.requests", "agg": "count"}
  ],
  "group_by": ["attr.http.route"]
}
```

### Free memory % via formula

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "group_by": ["attr.host.name"],
  "metrics": [
    {"id": "A", "name": "system.memory.usage", "agg": "last",
     "filter": {"field": "attr.state", "op": "=", "value": "free"}},
    {"id": "B", "name": "system.memory.limit", "agg": "last"}
  ],
  "formulas": [
    {"id": "F1", "expr": "A / B * 100", "name": "free memory %", "unit": "%"}
  ]
}
```

The response contains three series per host: `A` (free bytes), `B`
(total bytes), and `F1: free memory %` (the derived percent).

---

## Errors

| Status | Error                  | Cause                                              |
|--------|------------------------|----------------------------------------------------|
| 400    | `invalid_request`      | Schema validation failed                           |
| 400    | `invalid_aggregation`  | Aggregation not valid for metric type              |
| 401    | `unauthorized`         | Missing or invalid Bearer token                    |
