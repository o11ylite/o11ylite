# Alert Rules

Manage alert rules — scheduled query evaluations that detect anomalous conditions.

O11ylite handles **rule evaluation only**. It runs queries on a schedule, determines whether the condition is met, and dispatches Alertmanager-compatible webhooks on state changes. Notification routing, silencing, grouping, and escalation are the responsibility of an external system (e.g., Alertmanager, PagerDuty, Opsgenie) that receives those webhooks.

Alert rules use **Inertia page routes** — see SKILL.md for the read/write protocol.

## Evaluation model

Every `eval_interval_ms`, the engine runs the rule's query over the trailing `eval_window_ms` window and updates alert state. On evaluation failure (validation error, exception), the tick is skipped: the rule keeps its previous state and the error is recorded in `last_eval_error`. A broken evaluation is an operational problem, not an alert condition.

### Alert instances and rollup state

A rule does not hold a single state — it holds one **alert instance** per group, where a group is a distinct combination of the query's `group_by` columns. A rule with no `group_by` has exactly one instance, keyed on the empty fingerprint (shown as `(all results)`). Each instance carries its own state, timestamps, and labels, and is identified by a `fingerprint`.

The rule's top-level `state` is a **worst-wins rollup**: `firing` if any instance is firing, otherwise `ok`. Instances are returned in the `instances` prop of `GET /alert-rules/:id/edit`; see "Alert instances and dismissal" below for the read/dismiss mechanics.

### `alert_on` — what makes an instance fire

The `alert_on` field controls how query results map to instance state:

**`alert_on: "result"`** (presence detection): an instance fires when its group appears in results.
**`alert_on: "no_result"`** (absence detection): an instance fires when its group is missing from results.

There is no separate threshold field. To alert when error count exceeds 100, write a query with `having: {ref: "A", op: ">", value: 100}` — the having clause filters out sub-threshold groups, so a group present in the result means the threshold was breached. For threshold-based absence (e.g., "QPS dropped below 10"), invert the query to evidence of health (`having count >= 10`) and use `alert_on: "no_result"` — the alert fires because there's no evidence of health.

### Per-instance lifecycle

What happens when a group stops breaching differs by mode, because "no breach" means different things:

- **Match (`alert_on: "result"`)** — an instance is tracked only while it breaches. It is minted `firing` the tick its group first appears, and **deleted** the tick the group clears (after a `resolved` notification). A match rule's instance list therefore shows only *currently firing* groups, and is empty when all is well. A cleared group re-fires from scratch if it breaches again.
- **Absence (`alert_on: "no_result"`)** — an instance is tracked as `ok` once its group is seen present, fires when the group later disappears, and resolves back to `ok` (**retained, not deleted**) when the group reappears. An absence rule's instance list shows every group it is watching, firing or not.

### Absence only fires for groups it has seen

An absence rule can only fire for a group it has previously observed *present* — it has no way to know a group "should" exist otherwise. This has a consequence worth understanding when authoring rules:

- A **grouped** absence rule whose query returns nothing on a cold start (no instances tracked yet) fires nothing — there is no group to mark absent. It begins firing only after it has seen groups appear and then disappear. Use this for *"which of the groups I've seen disappeared?"* (the firing notification's labels tell you which).
- An **ungrouped** absence rule (no `group_by`) has its single empty-fingerprint instance tracked from creation, so it fires the first time results come back empty — no warm-up. Use this for *"did this query return nothing at all?"*

If you want an alert that fires when a query returns nothing, **drop `group_by` and use an ungrouped absence rule.** (The rule form shows a hint to this effect when you combine `no_result` with a group-by.)

### Aggregation window (metrics mode)

Each metric is aggregated as a **single value over the full `eval_window_ms`** — not sub-bucketed. `avg` is a true window average, `sum` is a window total, `max`/`min` are window extremes. A momentary sub-bucket spike alone does not trigger the alert. Any `bucket_ms` in the query body is ignored.

This differs from the dashboard metrics API, which sub-buckets results for plotting.

## Endpoints

| Method | Path                        | Description           |
|--------|-----------------------------|-----------------------|
| GET    | `/alert-rules`              | List all alert rules  |
| POST   | `/alert-rules`              | Create a new rule     |
| GET    | `/alert-rules/:id/edit`     | Get a single rule + its alert instances |
| PUT    | `/alert-rules/:id`          | Update a rule         |
| DELETE | `/alert-rules/:id`          | Delete a rule         |
| POST   | `/alert-rules/:id/instances/dismiss` | Dismiss alert instances by fingerprint |

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
      "alert_on": "result",
      "alert_target": null,
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

`state` is `"ok"` or `"firing"`. Evaluation errors are surfaced via `last_eval_error`.

---

## Get a single rule

**GET** `/alert-rules/:id/edit` (Inertia read)

**Response props:**
```json
{
  "alert_rule": { ... },
  "instances": [ ... ],
  "errors": {}
}
```

Same shape as a list item. `instances` is the rule's alert instances (one per group — see "Evaluation model" above and "Alert instances and dismissal" below); empty for a rule that has never evaluated or matched. `errors` is populated only after a failed mutation redirect.

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
  "eval_interval_ms": 60000,
  "alert_on": "result"
}
```

**Response:** `303` redirect to `/alert-rules`.

### Fields

| Field              | Required    | Type     | Description                                      |
|--------------------|-------------|----------|--------------------------------------------------|
| `name`             | Yes         | string   | 1–255 characters                                 |
| `description`      | No          | string   | Free-text description                            |
| `enabled`          | Yes         | boolean  | Whether the rule is actively evaluated           |
| `query_mode`       | Yes         | enum     | `"events"` or `"metrics"`                        |
| `query`            | Yes         | object   | Query definition (see below)                     |
| `eval_window_ms`   | Yes         | enum     | Evaluation window: 60000, 300000, 900000, 1800000, 3600000 |
| `eval_interval_ms` | Yes         | enum     | Evaluation interval: 60000, 300000, 900000, 1800000, 3600000 |
| `alert_on`         | Yes         | enum     | `"result"` (fire on match) or `"no_result"` (fire on absence) |
| `alert_target`     | Conditional | string\|null | Selects which series to watch when the metrics query declares more than one metric/formula. See below. |

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

### `alert_target` (metrics mode only)

A metrics query may declare multiple metrics (`A`, `B`, …) and/or formulas (`F1`, `F2`, …). At evaluation time, every declared series is rendered. `alert_target` picks the one the rule watches; series produced by other ids are ignored.

- Value is the id of a declared metric (`A`–`Z`) or formula (`F1`–`F9`).
- **Required** when the query declares more than one metric/formula in total (`count(metrics) + count(formulas) > 1`). Otherwise must be omitted or `null`.
- Must reference an id that exists in the query at submit time. Stale ids surface as a field-level validation error on `alert_target`.
- Ignored for `query_mode: "events"` (events queries produce a single result set).

Example — alert when formula `F1` (error rate) exceeds 5% while still rendering both raw counts for context:

```json
{
  "query_mode": "metrics",
  "query": {
    "metrics": [
      {"id": "A", "name": "http.requests", "agg": "sum"},
      {"id": "B", "name": "http.errors", "agg": "sum"}
    ],
    "formulas": [{"id": "F1", "expression": "B / A * 100"}],
    "having": {"ref": "F1", "op": ">", "value": 5}
  },
  "alert_target": "F1"
}
```

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

## Alert instances and dismissal

Each alert instance represents one tracked group of a rule (see "Evaluation model" → "Alert instances and rollup state" for the concept and per-mode lifecycle). Instances are read from the `instances` prop of `GET /alert-rules/:id/edit`.

To stop tracking a group — e.g. a service legitimately decommissioned, so its absence is no longer an alert — dismiss its instance:

**POST** `/alert-rules/:id/instances/dismiss` (Inertia write)

**Request body:**
```json
{ "fingerprints": ["<fingerprint>", "..."] }
```

Each fingerprint identifies an instance (the `fingerprint` field of an `instances` entry). Dismissal deletes those rows; it is **not** a permanent mute. A dismissed instance re-tracks naturally on a later eval — an ungrouped rule re-fires on its next empty eval, a grouped rule re-tracks the next time that group is seen present.

**Response:** `303` redirect to `/alert-rules/:id/edit`.

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
  "eval_interval_ms": 60000,
  "alert_on": "result"
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
  "eval_interval_ms": 60000,
  "alert_on": "result"
}
```

### Create an absence detection alert (QPS too low)

The query looks for evidence of health (QPS >= 10). When the query returns nothing — either because traffic dropped below threshold or the service stopped entirely — `no_result` fires the alert.

```
POST /alert-rules
```
```json
{
  "name": "Payment service QPS too low",
  "enabled": true,
  "query_mode": "events",
  "query": {
    "filter": {"field": "service", "op": "=", "value": "payment-service"},
    "aggregations": [{"id": "A", "function": "count", "field": "*"}],
    "having": {"ref": "A", "op": ">=", "value": 10},
    "visualization": {"type": "table"}
  },
  "eval_window_ms": 300000,
  "eval_interval_ms": 60000,
  "alert_on": "no_result"
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
