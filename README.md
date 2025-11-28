# O11yLite

O11yLite is a lightweigh observability engine that speaks OpenTelemetry.

It's designed to work as a single process.

The analytics engine is powered by DuckDB and SQLite.

## Components

### Backend

A Clojure service, the core of O11yLite.

* Handles telemetry ingestion, operate SQLite and DuckDB, managing a fleet of Parquet files.
* Function as InertiaJS backend.

### Frontend

An InertiaJS React frontend.

## Distributions

We bundle everything to a single docker image, using s6 overlay.

## Development

To start all services locally, run `dev/start all` and visit `https://o11ylite.localhost`. This is useful for quick test, or doing frontend only development.

For backend, REPL driven development, start all services except backend being just a REPL, run `dev/start` without arg.
Afterwords, in REPL or via your REPL connected editors, you can use various tools in backend/dev/user.clj to start the system.
