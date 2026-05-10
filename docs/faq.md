# FAQ

## Why O11yLite?

I started this as a project to build an observability stack for my homelab. After years of using the established OSS tools, they felt fragmented, had a steep learning curve, and inflexible. I wanted to build a Honeycomb-like experience that unifies logs, metrics, and traces in a single tool that can be as easy to deploy as possible and free.

Specifically, I wanted it to
- work well with the wide events pattern.
- have sufficiently high throughput on a single host.
- treat both human and agent interfaces as first-class citizens.

I initially planned to build on ClickHouse, but switched to DuckDB to keep distribution dead simple. I was also inspired by Motherduck's [Perf is not enough](https://motherduck.com/blog/perf-is-not-enough/) post. When DuckLake came out around the same time last year, it felt like the right foundation had just arrived. How good would it be if your OpenTelemetry backend just operate on a directory of Parquet files? So here it is: a small, open-source tool in an era where LLMs dominate the headlines. Hope some people find it useful.

## Is O11yLite free forever?

Yes. O11yLite is licensed under the AGPL v3.0 and will remain under AGPL forever. It is free for all uses permitted by the license, including self-hosted commercial use and modification, provided you comply with the AGPL terms.

## How is O11yLite different from the Grafana LGTM stack?

O11yLite is primarily designed to run on a single host rather than as a distributed system. We aim to be small, raising the bar of what a single computer can achieve. We do rely on Alertmanager for alert notifications.

## Is there a plan to make it horizontally scalable?

Yes. DuckLake, the storage layer, is fundamentally designed for horizontal scalability. Enabling this will require a refactor, which will happen if there is enough interest.
