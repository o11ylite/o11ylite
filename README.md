<h1 align="center">O11yLite</h1>

<br />

A lightweight, single-process observability engine that speaks [OpenTelemetry](https://opentelemetry.io/).

Ship traces, logs, and metrics to a single container. No distributed infrastructure. Just a Docker image and a volume.

## Features

- **Full OpenTelemetry support.** Ingests traces, logs, and metrics via OTLP/gRPC and OTLP/HTTP.
- **Single process.** One container runs the entire stack: ingestion, storage, query, and UI.
- **Automatic schema evolution.** New fields in your telemetry automatically become queryable columns. No manual schema management.
- **Powered by DuckDB + SQLite.** Fast analytical queries on telemetry data without heavy infrastructure.
- **Explore.** Query and visualize traces, logs, and metrics with a built-in query builder and trace waterfall view.
- **Alert Rules.** Define alert rules with Alertmanager-compatible webhook notifications.
- **Notebooks.** Compose and share investigative workflows combining queries and notes.
- **Agent native.** Built-in OAuth PKCE and an [agent skill package](docs/agent-native.md) let LLM agents query telemetry, manage alerts, and edit notebooks out of the box.

## Philosophy

**Wide events over scattered logs.** Traditional logging produces dozens of narrow, context-free lines per request that are optimized for writing, not querying. We believe in [wide events](https://loggingsucks.com/): single, rich records per unit of work that carry all the context you need. User info, business data, feature flags, timings. O11yLite's automatic schema evolution and high-cardinality columnar storage are designed specifically to make this style of instrumentation practical. Send us your 50-field spans and we'll make every field queryable without configuration.

**Smaller but capable.** The backend for observability doesn't need to be a distributed fleet of indexers and query nodes. Advances in embedded analytical databases like DuckDB mean a single process can handle what used to take a cluster. O11yLite leans into this: one container, one volume, minimal moving parts. Built to realize the potential of your 7000 MB/s NVMe drive. It sustains **140K events/s ingestion** on a single M1 Max MacBook Pro and aims to serve teams from small startups through upper-mid-size organizations.

**Built for you and your agents.** O11yLite offers first-class agent support through lean, solid primitives and a builtin agent skill. The agent skill is considered the first class interface just like UI.

## Quick Start

```bash
docker run -d \
  -p 80:80 \
  -v o11ylite-data:/data \
  ghcr.io/o11ylite/o11ylite:latest
```

Then:
- Open `http://localhost` for the UI
- Point your OTLP exporters at `localhost:80`. The built-in proxy routes both gRPC and HTTP on the same port

## Documentation

- [Installation](docs/installation.md) — Docker and Helm chart setup
- [Kubernetes OpenTelemetry Setup](docs/kubernetes-otel-setup.md) — Collect logs, traces, and metrics from your Kubernetes cluster
- [Authentication](docs/authentication.md) — OIDC setup, API keys, and scopes
- [Agent Native](docs/agent-native.md) — First-class interface for LLM agents
- [FAQ](docs/faq.md) — Frequently asked questions

## Development

See [DEVELOPMENT.md](DEVELOPMENT.md) for setup instructions, architecture details, and contribution workflow.

## License

O11yLite is licensed under the [GNU Affero General Public License v3.0](./LICENSE).

For commercial use without AGPL obligations, contact [zhming0](https://github.com/zhming0).
