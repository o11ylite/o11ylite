---
name: o11ylite
description: Interact with the O11yLite observability platform for traces, logs, and metrics
---

# O11yLite Agent Skill

O11yLite is a lightweight observability platform for traces, logs, and metrics.

## Connection

Read `O11YLITE_AGENT_URL` env var for the base URL (e.g., `http://localhost:3000`). If not set, ask the user for their o11ylite hostname.

## Authentication

Run: `python3 <skill-dir>/auth.py $O11YLITE_AGENT_URL`

If `O11YLITE_AGENT_API_KEY` is set, the script uses it directly (no browser). Otherwise it runs an OAuth flow that opens the user's browser.

The script prints JSON to stdout:
```json
{"token": "...", "base_url": "...", "inertia_version": "..."}
```

Use the token on every request: `Authorization: Bearer <token>`

If a page route returns **409**, re-run with `--refresh` to get a fresh Inertia version.

## Data model

- **Events** unify spans (traces) and logs into a single queryable table. A "trace" is a set of events sharing a `trace_id`.
- **Metrics** are separate — standard OpenTelemetry gauges, sums, and histograms.
- OpenTelemetry resource and span attributes are stored as fields prefixed with `attr.` (e.g., `attr.http.method`, `attr.service.version`).
- Built-in fields have no prefix: `service`, `name`, `trace_id`, `span_id`, `span.duration_ms`, `span.status_code`, `timestamp`, `meta.signal_type`.
- `meta.signal_type` is `"span"`, `"span_event"`, or `"log"`.

## What to read

| Task                            | Read this file          |
|---------------------------------|-------------------------|
| Query events, logs, or traces   | `docs/events-query.md`  |
| Query metrics                   | `docs/metrics-query.md` |
| Manage alert rules              | `docs/alerts.md`        |
| Manage notebooks                | `docs/notebooks.md`     |

## Conventions

**JSON API routes** (`/api/*`) — standard REST. Send/receive JSON.

**Page routes** (alerts, notebooks) — use the Inertia protocol:
- **Read:** `GET <path>` with `X-Inertia: true`, `X-Inertia-Version: <inertia_version>`, `Authorization: Bearer <token>`. Response is JSON: `{"component": "...", "props": {...}}`. Data is in `props`.
- **Write:** `POST/PUT/DELETE <path>` with `X-Ring-Anti-Forgery: true`, `Content-Type: application/json`, `Authorization: Bearer <token>`. Response is `303` redirect. Follow it with a GET (Inertia headers) to read back the result.

**Useful discovery endpoints** (no special headers, just Bearer auth):
- `GET /api/services` — list all known service names
- `GET /api/events/fields` — list all queryable event fields with types
- `GET /api/metrics` — list all available metrics

**Error shape:** `{"error": "<code>", "error_description": "<message>"}`
