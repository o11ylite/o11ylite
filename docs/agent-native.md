# Agent Native

LLM coding agents (Claude, Cursor, Copilot, etc.) can query traces, logs, and metrics, manage alert rules, and edit notebooks. There are two ways to connect:

- **[MCP server](#mcp-server)**: built in at `/mcp`. Works with any MCP client, including chat apps such as Claude and ChatGPT. Nothing to install.
- **[Agent skill](#install-the-skill)**: a small skill package that teaches coding agents to call O11yLite's HTTP APIs directly.

## MCP server

O11yLite serves the [Model Context Protocol](https://modelcontextprotocol.io/specification/2026-07-28) (revision `2026-07-28`, Streamable HTTP) at:

```
https://<your-o11ylite-host>/mcp
```

Add it as a remote MCP server in your client. For example, in Claude Code:

```bash
claude mcp add --transport http o11ylite https://o11ylite.yourcompany.com/mcp
```

**Authentication** follows the MCP authorization spec, so clients handle it automatically:

- **Open mode**: no authentication.
- **OIDC mode**: the first request returns `401` with a pointer to `/.well-known/oauth-protected-resource/mcp`. The client then runs OAuth against O11yLite's built-in authorization server, using a Client ID Metadata Document. You log in if needed, approve the client on a consent page, and the client receives an access token bound to `/mcp` plus a refresh token. See [Agent auth](authentication.md#agent-auth-oauth-21).
- **Headless**: send an API key instead: `Authorization: Bearer o11y_...`. A `read` key can use the read-only tools; a `write` key can use all tools. This goes beyond the MCP spec, which only covers OAuth tokens, but it lets CI and scripts connect without a browser.

Set `O11YLITE_PUBLIC_URL` to the URL clients use; remote clients require `https`. Requests that carry a browser `Origin` header are refused unless it matches `O11YLITE_PUBLIC_URL`, or is a loopback origin when that setting is unset. This protects against DNS rebinding.

### Tools

| Tool | Scope | Description |
|------|-------|-------------|
| `list_services` | read | Services that have sent telemetry |
| `list_event_fields` | read | Queryable span/log fields and types |
| `query_events` | read | Raw rows, aggregates, or time series over spans and logs |
| `get_trace` | read | All spans of one trace |
| `list_metrics` | read | Metric names, types, and units |
| `describe_metric` | read | A metric's attributes, temporality, and type |
| `query_metrics` | read | Metric time series with formulas |
| `list_alert_rules` | read | Alert rules and whether they are firing |
| `get_alert_rule` | read | One rule with its query and alert instances |
| `save_alert_rule` | write | Create or replace an alert rule |
| `create_notebook` | write | Save an investigation as a notebook |

Time ranges accept `{"last": "1h"}` or ISO-8601 `start`/`end`. Tool output renders timestamps as ISO-8601 and omits null columns.

## Agent skill

### Install the skill

```bash
npx skills add o11ylite/o11ylite
```

This downloads a small skill package into your project. The agent loads it automatically on every invocation.

### Environment variables

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

### How authentication works

On the agent's first API call, the skill runs a built-in auth script:

1. **If `O11YLITE_API_KEY` is set** — uses it directly. No browser interaction.
2. **Otherwise** — runs an OAuth PKCE flow: opens your browser, you log in (or auto-approves in open mode), and the agent receives a short-lived JWT token.

Credentials are cached at `~/.o11ylite/credentials.json` (chmod 600). Subsequent calls reuse the cached token until it expires (1 hour for OAuth tokens; API keys never expire).

### What the agent can do

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
