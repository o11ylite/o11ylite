# Notebooks

Manage notebooks — collections of query cells for ad-hoc investigation and lightweight dashboards. A notebook holds an ordered list of cells, each with its own query. Use notebooks to build incident timelines, save reusable monitoring views, or assemble a set of charts you revisit regularly.

Notebooks use **Inertia page routes** — see SKILL.md for the read/write protocol.

## Time ranges and pinning

By default, each cell uses the notebook's global time range (selected by the user in the UI). To freeze a cell to a specific window — for example, to preserve the exact moment of an incident — set `pinned_from` and `pinned_to`. A pinned cell ignores the global time picker and always queries its fixed window, which is useful for dashboards where each panel covers a different period or for preserving evidence during investigations.

Set both to `null` (or omit them) to return the cell to the global time range.

## Endpoints

### Notebooks

| Method | Path                    | Description             |
|--------|-------------------------|-------------------------|
| GET    | `/notebooks`            | List all notebooks      |
| POST   | `/notebooks`            | Create a notebook       |
| GET    | `/notebooks/:id`        | Get notebook with cells |
| GET    | `/notebooks/:id/edit`   | Get notebook for editing|
| PUT    | `/notebooks/:id`        | Update notebook metadata|
| DELETE | `/notebooks/:id`        | Delete notebook         |

### Cells

| Method | Path                                     | Description      |
|--------|------------------------------------------|------------------|
| POST   | `/notebooks/:id/cells`                   | Add a cell       |
| PUT    | `/notebooks/:id/cells/:cell-id`          | Update a cell    |
| DELETE | `/notebooks/:id/cells/:cell-id`          | Delete a cell    |
| POST   | `/notebooks/:id/cells/:cell-id/move`     | Reorder a cell   |

---

## List notebooks

**GET** `/notebooks` (Inertia read)

**Response props:**
```json
{
  "notebooks": [
    {
      "id": "01912f...",
      "name": "Latency investigation",
      "description": "Debugging slow endpoints",
      "cell_count": 3,
      "created_at": 1700000000000,
      "updated_at": 1700003500000
    }
  ]
}
```

Ordered by `updated_at` descending.

---

## Get a notebook

**GET** `/notebooks/:id` (Inertia read)

**Response props:**
```json
{
  "notebook": {
    "id": "01912f...",
    "name": "Latency investigation",
    "description": "Debugging slow endpoints",
    "created_at": 1700000000000,
    "updated_at": 1700003500000,
    "cells": [
      {
        "id": "01913a...",
        "notebook_id": "01912f...",
        "position": 0,
        "title": "Error count by service",
        "description": "Last hour",
        "query_mode": "events",
        "query": { ... },
        "pinned_from": "2024-01-15T10:00:00Z",
        "pinned_to": "2024-01-15T11:00:00Z",
        "created_at": 1700000100000,
        "updated_at": 1700003400000
      }
    ]
  }
}
```

Cells are ordered by `position` ascending.

---

## Create a notebook

**POST** `/notebooks` (Inertia write)

**Request body:**
```json
{
  "name": "Latency investigation",
  "description": "Debugging slow endpoints"
}
```

| Field         | Required | Type   | Description            |
|---------------|----------|--------|------------------------|
| `name`        | Yes      | string | 1–255 characters       |
| `description` | No       | string | Free-text description  |

**Response:** `303` redirect to `/notebooks/:id` (the new notebook's show page).

---

## Update notebook metadata

**PUT** `/notebooks/:id` (Inertia write)

Same body as create. Full replacement.

**Response:** `303` redirect to `/notebooks/:id`.

---

## Delete a notebook

**DELETE** `/notebooks/:id` (Inertia write)

No request body. Deleting a notebook also deletes all its cells.

**Response:** `303` redirect to `/notebooks`.

---

## Add a cell

**POST** `/notebooks/:id/cells` (Inertia write)

**Request body:**
```json
{
  "title": "Error count by service",
  "description": "Grouped by service name",
  "query_mode": "events",
  "query": {
    "filter": {"field": "span.status_code", "op": "=", "value": "ERROR"},
    "aggregations": [{"id": "A", "function": "count", "field": "*"}],
    "group_by": ["service"],
    "visualization": {"type": "table", "sort": {"ref": "A", "order": "desc"}}
  },
  "pinned_from": "2024-01-15T10:00:00Z",
  "pinned_to": "2024-01-15T11:00:00Z"
}
```

### Cell fields

| Field         | Required | Type   | Description                                       |
|---------------|----------|--------|---------------------------------------------------|
| `title`       | No       | string | Cell heading                                      |
| `description` | No       | string | Cell description                                  |
| `query_mode`  | Yes      | enum   | `"events"` or `"metrics"`                         |
| `query`       | Yes      | object | Query definition (see below)                      |
| `pinned_from` | No       | string | ISO 8601 timestamp — override time range start (see "Time ranges and pinning") |
| `pinned_to`   | No       | string | ISO 8601 timestamp — override time range end (see "Time ranges and pinning")   |

New cells are appended at the end of the notebook.

### Cell query object

Same schema as the corresponding query API but **without** `time_range` and `cursor`. Unlike alert rules, notebooks **do** allow `limit`.

**Events mode** (`query_mode: "events"`):
```json
{
  "filter": { ... },
  "aggregations": [{"id": "A", "function": "count", "field": "*"}],
  "group_by": ["service"],
  "having": {"ref": "A", "op": ">", "value": 10},
  "visualization": {"type": "time_series"},
  "limit": 50
}
```

See `docs/events-query.md` for filter, aggregation, having, and visualization syntax.

**Metrics mode** (`query_mode: "metrics"`):
```json
{
  "metrics": [{"id": "A", "name": "cpu.utilization", "agg": "avg"}],
  "filter": { ... },
  "group_by": ["attr.host.name"],
  "having": {"ref": "A", "op": ">", "value": 80},
  "visualization": {"type": "time_series", "overlay": true, "render_as": "line", "hidden_metrics": []}
}
```

See `docs/metrics-query.md` for metric definition and filter syntax.

**Response:** `303` redirect to `/notebooks/:id`.

---

## Update a cell

**PUT** `/notebooks/:id/cells/:cell-id` (Inertia write)

Same body as cell create. All fields must be provided (full replacement).

**Response:** `303` redirect to `/notebooks/:id`.

---

## Delete a cell

**DELETE** `/notebooks/:id/cells/:cell-id` (Inertia write)

No request body.

**Response:** `303` redirect to `/notebooks/:id`.

---

## Move a cell

**POST** `/notebooks/:id/cells/:cell-id/move` (Inertia write)

**Request body:**
```json
{"direction": "up"}
```

`direction` is `"up"` or `"down"`. Swaps position with the adjacent cell.

**Response:** `303` redirect to `/notebooks/:id`.

---

## Examples

### Create a notebook with a cell

1. Create the notebook:
```
POST /notebooks
```
```json
{"name": "Incident 2024-01-15", "description": "Investigating 500s on checkout"}
```

2. Follow the 303 redirect to get the notebook ID from `props.notebook.id`.

3. Add a cell:
```
POST /notebooks/:id/cells
```
```json
{
  "title": "Errors over time",
  "query_mode": "events",
  "query": {
    "filter": {"field": "span.status_code", "op": "=", "value": "ERROR"},
    "aggregations": [{"id": "A", "function": "count", "field": "*"}],
    "group_by": ["service"],
    "visualization": {"type": "time_series"}
  }
}
```

### Add a metrics cell with a pinned time range

```
POST /notebooks/:id/cells
```
```json
{
  "title": "CPU during incident",
  "query_mode": "metrics",
  "query": {
    "metrics": [{"id": "A", "name": "cpu.utilization", "agg": "avg"}],
    "group_by": ["attr.host.name"]
  },
  "pinned_from": "2024-01-15T14:00:00Z",
  "pinned_to": "2024-01-15T15:00:00Z"
}
```

---

## Errors

| Status | Cause                                              |
|--------|----------------------------------------------------|
| 303    | Validation failed — redirects back with `errors` in flash (read the redirect target with Inertia to see errors) |
| 401    | Missing or invalid Bearer token                    |
| 404    | Notebook or cell not found                         |
