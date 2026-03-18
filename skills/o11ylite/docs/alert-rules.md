# Alert Rules

Manage alert rules — scheduled query evaluations that detect anomalous conditions.

O11ylite handles **rule evaluation only**. It runs queries on a schedule, determines whether the condition is met, and dispatches Alertmanager-compatible webhooks on state changes. Notification routing, silencing, grouping, and escalation are the responsibility of an external system (e.g., Alertmanager, PagerDuty, Opsgenie) that receives those webhooks.

Alert rules use **Inertia page routes** — see SKILL.md for the read/write protocol.

## Evaluation model

O11ylite uses a simple "non-empty result = firing" model. The query itself encodes the alert condition — use `filter` and `having` to define what "bad" looks like. Every `eval_interval_ms`, the engine runs the query over the trailing `eval_window_ms` window:

- **Query returns any rows** (events) or **any data points** (metrics) → state becomes `firing`
- **Query returns empty results** → state becomes `ok`
- **Query fails** (validation error, exception) → state becomes `no_data`

There is no separate threshold field. To alert when error count exceeds 100, write a query with `having: {ref: "A", op: ">", value: 100}` — the having clause filters out sub-threshold groups, so a non-empty result means the threshold was breached.

## Endpoints

| Method | Path                        | Description           |
|--------|-----------------------------|-----------------------|
| GET    | `/alert-rules`              | List all alert rules  |
| POST   | `/alert-rules`              | Create a new rule     |
| GET    | `/alert-rules/:id/edit`     | Get a single rule     |
| PUT    | `/alert-rules/:id`          | Update a rule         |
| DELETE | `/alert-rules/:id`          | Delete a rule         |

---

## List alert rules

**GET** `/alert-rules` (Inertia read)

**Response props:**
```json
{
  "alert_rules": [
    {
      "id": "01912f...",
      "name": "High error rate",
      "description": "Alert when error count spikes",
      "enabled": true,
      "query_mode": "events",
      "query": { ... },
      "eval_window_ms": 300000,
      "eval_interval_ms": 60000,
      "state": "ok",
      "last_eval_at": 1700003500000,
      "last_eval_error": null,
      "state_changed_at": 1700000000000,
      "created_at": 1700000000000,
      "updated_at": 1700003500000
    }
  ]
}
```

`state` is `"ok"`, `"firing"`, or `"no_data"`.

---

## Get a single rule

**GET** `/alert-rules/:id/edit` (Inertia read)

**Response props:**
```json
{
  "alert_rule": { ... },
  "errors": {}
}
```

Same shape as a list item. `errors` is populated only after a failed mutation redirect.

---

## Create a rule

**POST** `/alert-rules` (Inertia write)

**Request body:**
```json
{
  "name": "High error rate",
  "description": "Alert when errors spike",
  "enabled": true,
  "query_mode": "events",
  "query": {
    "filter": {"field": "span.status_code", "op": "=", "value": "ERROR"},
    "aggregations": [{"id": "A", "function": "count", "field": "*"}],
    "having": {"ref": "A", "op": ">", "value": 100},
    "visualization": {"type": "table"}
  },
  "eval_window_ms": 300000,
  "eval_interval_ms": 60000
}
```

**Response:** `303` redirect to `/alert-rules`.

### Fields

| Field              | Required | Type     | Description                                      |
|--------------------|----------|----------|--------------------------------------------------|
| `name`             | Yes      | string   | 1–255 characters                                 |
| `description`      | No       | string   | Free-text description                            |
| `enabled`          | Yes      | boolean  | Whether the rule is actively evaluated           |
| `query_mode`       | Yes      | enum     | `"events"` or `"metrics"`                        |
| `query`            | Yes      | object   | Query definition (see below)                     |
| `eval_window_ms`   | Yes      | enum     | Evaluation window: 60000, 300000, 900000, 1800000, 3600000 |
| `eval_interval_ms` | Yes      | enum     | Evaluation interval: 60000, 300000, 900000, 1800000, 3600000 |

### Query object

The query follows the same schema as the corresponding query API but **without** `time_range`, `cursor`, or `limit` — those are controlled by the evaluation engine.

**Events mode** (`query_mode: "events"`):
```json
{
  "filter": { ... },
  "aggregations": [{"id": "A", "function": "count", "field": "*"}],
  "group_by": ["service"],
  "having": {"ref": "A", "op": ">", "value": 100},
  "visualization": {"type": "table"}
}
```

See `docs/events-query.md` for filter, aggregation, and having syntax. All fields except `visualization` are optional.

**Metrics mode** (`query_mode: "metrics"`):
```json
{
  "metrics": [{"id": "A", "name": "cpu.utilization", "agg": "avg"}],
  "filter": { ... },
  "group_by": ["attr.host.name"],
  "having": {"ref": "A", "op": ">", "value": 90}
}
```

See `docs/metrics-query.md` for metric definition and filter syntax.

---

## Update a rule

**PUT** `/alert-rules/:id` (Inertia write)

Same body as create. All fields must be provided (full replacement, not patch).

**Response:** `303` redirect to `/alert-rules`.

---

## Delete a rule

**DELETE** `/alert-rules/:id` (Inertia write)

No request body.

**Response:** `303` redirect to `/alert-rules`.

---

## Examples

### Create an events alert (error count > 100)

```
POST /alert-rules
```
```json
{
  "name": "Error spike",
  "enabled": true,
  "query_mode": "events",
  "query": {
    "filter": {"field": "span.status_code", "op": "=", "value": "ERROR"},
    "aggregations": [{"id": "A", "function": "count", "field": "*"}],
    "having": {"ref": "A", "op": ">", "value": 100},
    "visualization": {"type": "table"}
  },
  "eval_window_ms": 300000,
  "eval_interval_ms": 60000
}
```

### Create a metrics alert (CPU > 90%)

```
POST /alert-rules
```
```json
{
  "name": "CPU critical",
  "enabled": true,
  "query_mode": "metrics",
  "query": {
    "metrics": [{"id": "A", "name": "cpu.utilization", "agg": "avg"}],
    "having": {"ref": "A", "op": ">", "value": 90}
  },
  "eval_window_ms": 300000,
  "eval_interval_ms": 60000
}
```

### Disable an existing rule

```
PUT /alert-rules/:id
```

Fetch the rule via `GET /alert-rules/:id/edit`, change `enabled` to `false`, and send the full body back.

---

## Errors

| Status | Cause                                              |
|--------|----------------------------------------------------|
| 303    | Validation failed — redirects back with `errors` in flash (read the redirect target with Inertia to see errors) |
| 401    | Missing or invalid Bearer token                    |
| 404    | Rule not found (GET edit / PUT / DELETE)            |
