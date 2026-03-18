# Agent Native

LLM coding agents (Claude, Cursor, Copilot, etc.) can query traces, logs, and metrics, manage alert rules, and edit notebooks. No CLI tool to install. The agent talks straight to O11yLite's APIs using the same endpoints the UI uses. The skill package just teaches it how.

## Install the skill

```bash
npx skills add o11ylite/o11ylite
```

This downloads a small skill package into your project. The agent loads it automatically on every invocation.

## Environment variables

Set these before starting your agent session. Only the URL is required — the rest are optional conveniences.

| Variable | Required | Description |
|----------|----------|-------------|
| `O11YLITE_URL` | Yes | Base URL of your O11yLite instance (e.g., `https://o11ylite.yourcompany.com`) |
| `O11YLITE_API_KEY` | No | API key with `write` scope. If set, the agent uses it directly — no OAuth browser flow. Recommended for CI and headless environments. |
| `O11YLITE_INSECURE` | No | Set to `1` to skip SSL certificate verification (self-signed certs in dev). |

### Quick setup

```bash
# Interactive (opens browser for OAuth on first use)
export O11YLITE_URL=https://o11ylite.yourcompany.com

# Non-interactive (API key, no browser needed)
export O11YLITE_URL=https://o11ylite.yourcompany.com
export O11YLITE_API_KEY=o11y_your_key_here
```

## How authentication works

On the agent's first API call, the skill runs a built-in auth script:

1. **If `O11YLITE_API_KEY` is set** — uses it directly. No browser interaction.
2. **Otherwise** — runs an OAuth PKCE flow: opens your browser, you log in (or auto-approves in open mode), and the agent receives a short-lived JWT token.

Credentials are cached at `~/.o11ylite/credentials.json` (chmod 600). Subsequent calls reuse the cached token until it expires (1 hour for OAuth tokens; API keys never expire).

## What the agent can do

Once authenticated, the agent can:

- **Query events, logs, and traces** — filter by service, duration, status; aggregate and group; view trace waterfalls
- **Query metrics** — gauges, counters, histograms with appropriate aggregations
- **Manage alert rules** — create, update, delete alert rules with webhook notifications
- **Manage notebooks** — create investigative notebooks with query cells and markdown notes

## Example prompts

```
"Show me the slowest API calls in the last hour"
"What's the error rate for the payments service over the last 24 hours?"
"Create an alert that fires when p99 latency exceeds 500ms for any service"
"Find the trace for request abc123 and tell me where the time was spent"
```
