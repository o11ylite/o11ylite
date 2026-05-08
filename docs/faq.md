# FAQ

## Is O11yLite free forever?

Yes. O11yLite is licensed under the AGPL v3.0 and will remain under AGPL forever. It is free for all uses permitted by the license, including self-hosted commercial use and modification, provided you comply with the AGPL terms.

## How is O11yLite different from the Grafana LGTM stack?

O11yLite is primarily designed to run on a single host rather than as a distributed system. We aim to be small, raising the bar of what a single computer can achieve. We do rely on Alertmanager for alert notifications.

## Is there a plan to make it horizontally scalable?

Yes. DuckLake, the storage layer, is fundamentally designed for horizontal scalability. Enabling this will require a refactor, which will happen if there is enough interest.
