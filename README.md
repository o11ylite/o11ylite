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
