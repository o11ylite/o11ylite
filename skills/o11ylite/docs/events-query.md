# Events Query

Query spans, logs, and traces stored in o11ylite.

## Endpoints

| Method | Path                 | Description                              |
|--------|----------------------|------------------------------------------|
| GET    | `/api/events/fields` | List all queryable fields with types      |
| GET    | `/api/services`      | List all known service names              |
| POST   | `/api/query/events`  | Query events (table, time series, trace) |

All endpoints require `Authorization: Bearer <token>`.

---

## GET /api/events/fields

Returns all fields the query engine knows about.

**Response:**
```json
[
  {"name": "service", "type": "string"},
  {"name": "span.duration_ms", "type": "float"},
  {"name": "span.status_code", "type": "string"},
  {"name": "timestamp", "type": "instant"},
  {"name": "trace_id", "type": "string"}
]
```

Field types: `string`, `integer`, `float`, `boolean`, `instant`.

---

## POST /api/query/events

The main query endpoint. Send a JSON body describing what to query.

### Request body

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "visualization": {"type": "table"},
  "filter": { ... },
  "aggregations": [ ... ],
  "group_by": ["service"],
  "having": { ... },
  "limit": 100,
  "cursor": null
}
```

| Field           | Required | Description                                              |
|-----------------|----------|----------------------------------------------------------|
| `time_range`    | Yes      | `{start, end}` — Unix epoch milliseconds                |
| `visualization` | Yes      | `{type: "table" | "time_series" | "trace", ...}`        |
| `filter`        | No       | Filter expression (see below)                            |
| `aggregations`  | No       | Array of aggregation specs                               |
| `group_by`      | No       | Array of field names to group by                         |
| `having`        | No       | Filter on aggregation results (requires `aggregations`)  |
| `limit`         | No       | 1–10000, default 100                                     |
| `cursor`        | No       | Pagination cursor (table without aggregations only)      |

### Filter expression

Filters are recursive. Combine with `and`/`or`.

**Simple filter:**
```json
{"field": "service", "op": "=", "value": "api-gateway"}
```

**Compound filter:**
```json
{
  "and": [
    {"field": "service", "op": "=", "value": "api-gateway"},
    {"field": "span.duration_ms", "op": ">", "value": 1000}
  ]
}
```

**Operators by field type:**

| Type      | Operators                                        |
|-----------|--------------------------------------------------|
| `string`  | `=`, `!=`, `contains`, `exists`, `starts-with`   |
| `integer` | `=`, `!=`, `>`, `<`, `>=`, `<=`, `exists`        |
| `float`   | `=`, `!=`, `>`, `<`, `>=`, `<=`, `exists`        |
| `boolean` | `=`, `!=`, `exists`                               |
| `instant` | `=`, `!=`, `>`, `<`, `>=`, `<=`, `exists`        |

The `exists` operator takes no `value` — it checks if the field is present.

### Aggregations

```json
{
  "aggregations": [
    {"id": "A", "function": "count", "field": "*"},
    {"id": "B", "function": "p99", "field": "span.duration_ms"}
  ]
}
```

- `id` — single uppercase letter A–Z (unique within the query)
- `function` — `count`, `sum`, `avg`, `min`, `max`, `p50`, `p90`, `p99`
- `field` — field name, or `"*"` for `count(*)`

### Having expression

Filter on aggregation results. Only valid when `aggregations` is present.

```json
{"ref": "A", "op": ">", "value": 100}
```

- `ref` — references an aggregation `id`
- `op` — `>`, `<`, `>=`, `<=`, `=`, `!=`
- `value` — number

Compound having uses `and`/`or` just like filters.

---

## Visualization types

### table

Raw rows or aggregated results.

**Request:**
```json
{
  "visualization": {
    "type": "table",
    "sort": {"field": "span.duration_ms", "order": "desc"},
    "displayed_fields": ["service", "name", "span.duration_ms"]
  }
}
```

Sort can reference a field (`{"field": ..., "order": ...}`) or an aggregation (`{"ref": "A", "order": "desc"}`).

**Response:**
```json
{
  "data": {
    "rows": [
      {"service": "api-gateway", "name": "HTTP GET /users", "span.duration_ms": 1234.5}
    ],
    "total_count": 42,
    "has_more": true,
    "next_cursor": "eyJzIj...",
    "columns": [{"ref": "A", "key": "count(*)"}]
  },
  "metadata": {"query_time_ms": 15, "has_more": true}
}
```

- `columns` — only present when aggregations are used
- `next_cursor` — pass as `cursor` in the next request to paginate (only without aggregations)

### time_series

Bucketed time series. Requires at least one aggregation.

**Request:**
```json
{
  "visualization": {"type": "time_series", "bucket_ms": 60000},
  "aggregations": [{"id": "A", "function": "count", "field": "*"}],
  "group_by": ["service"]
}
```

`bucket_ms` is optional — auto-selected based on time range if omitted.

Optional visualization fields:
- `overlay` (boolean, default `false`) — when multiple metrics are present, draw them in a single chart instead of one chart per metric.
- `render_as` (`"line"` | `"stacked_area"` | `"bar"`, default `"line"`) — draw each series as a line, as a filled region stacked on top of the others, or as a stacked bar per bucket. Stacked area and bar treat missing buckets as 0 so the stack has no holes; pick either for part-of-whole views (e.g. request count by status code).

**Response:**
```json
{
  "data": {
    "bucket_ms": 60000,
    "start_ms": 1700000000000,
    "end_ms": 1700003600000,
    "series": [
      {
        "labels": {"service": "api-gateway"},
        "name": "count(*)",
        "data": [
          {"timestamp": 1700000000000, "value": 42},
          {"timestamp": 1700000060000, "value": 38}
        ]
      }
    ]
  },
  "metadata": {"query_time_ms": 25, "has_more": false}
}
```

One series per `(labels, aggregation)` combination.

### trace

Waterfall view for a single trace. Requires a `trace_id` filter.

**Request:**
```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "visualization": {"type": "trace"},
  "filter": {"field": "trace_id", "op": "=", "value": "abc123def456"}
}
```

**Response:**
```json
{
  "data": {
    "spans": [
      {
        "span_id": "span_001",
        "parent_span_id": null,
        "name": "HTTP GET /users",
        "service": "api-gateway",
        "meta.signal_type": "span",
        "span.status_code": "OK",
        "span.duration_ms": 45.2,
        "timestamp": 1700000000123.456
      },
      {
        "span_id": "span_002",
        "parent_span_id": "span_001",
        "name": "SELECT users",
        "service": "user-service",
        "meta.signal_type": "span",
        "span.status_code": "OK",
        "span.duration_ms": 12.1,
        "timestamp": 1700000000130.0
      }
    ],
    "total_count": 2
  },
  "metadata": {"query_time_ms": 8, "has_more": false}
}
```

Spans are ordered by timestamp. `meta.signal_type` is `"span"` or `"span_event"`. Hard limit of 1000 spans per trace.

---

## Examples

### Find slow spans in the last hour

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "visualization": {
    "type": "table",
    "sort": {"field": "span.duration_ms", "order": "desc"}
  },
  "filter": {"field": "span.duration_ms", "op": ">", "value": 1000},
  "limit": 20
}
```

### Error rate by service over time

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "visualization": {"type": "time_series"},
  "filter": {"field": "span.status_code", "op": "=", "value": "ERROR"},
  "aggregations": [{"id": "A", "function": "count", "field": "*"}],
  "group_by": ["service"]
}
```

### Top endpoints by request count

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "visualization": {
    "type": "table",
    "sort": {"ref": "A", "order": "desc"}
  },
  "aggregations": [{"id": "A", "function": "count", "field": "*"}],
  "group_by": ["name"],
  "limit": 10
}
```

### Look up a specific trace

```json
{
  "time_range": {"start": 1700000000000, "end": 1700003600000},
  "visualization": {"type": "trace"},
  "filter": {"field": "trace_id", "op": "=", "value": "abc123def456"}
}
```

---

## Errors

| Status | Error                  | Cause                                         |
|--------|------------------------|-----------------------------------------------|
| 400    | `invalid_request`      | Schema validation failed (missing/invalid fields) |
| 400    | `invalid_filter`       | Operator not valid for field type              |
| 401    | `unauthorized`         | Missing or invalid Bearer token                |
