# Authentication

O11yLite supports three authentication mechanisms:

1. **OIDC** — Protects the UI and REST API. Bring your own identity provider.
2. **API keys** — Protects OTLP ingestion. Managed via the UI.
3. **Agent auth (OAuth)** — Short-lived JWT access tokens for LLM agents, scripts, and MCP clients. Uses Authorization Code + PKCE.

OIDC and API keys are opt-in. Agent auth is always available — it uses the same session secret and scope system as the rest of o11ylite.

## Open mode (default)

A fresh O11yLite instance runs in open mode. The UI is accessible without login, OTLP ingestion accepts all data, and all API endpoints are unrestricted.

## OIDC (UI and API authentication)

Setting `O11YLITE_OIDC_ISSUER_URL` enables OIDC. Any OpenID Connect-compliant identity provider works (Okta, Auth0, Keycloak, etc.).

When enabled, OIDC protects:
- All UI pages (unauthenticated users are redirected to `/auth/login`)
- All REST API endpoints under `/api/*` (returns 401 JSON for unauthenticated requests)

OIDC does **not** affect OTLP ingestion — that is controlled independently by API keys.

### Configuration

| Variable | Required | Description |
|----------|----------|-------------|
| `O11YLITE_OIDC_ISSUER_URL` | Yes | Your IdP's issuer URL |
| `O11YLITE_OIDC_CLIENT_ID` | Yes | OAuth 2.0 client ID |
| `O11YLITE_OIDC_CLIENT_SECRET` | Yes | OAuth 2.0 client secret |
| `O11YLITE_SESSION_SECRET` | No | 16-byte hex string for session cookie encryption. Auto-generated and persisted on first boot if not set. |

### Redirect URI

Register the following redirect URI with your identity provider:

```
https://<your-o11ylite-host>/auth/callback
```

O11yLite auto-derives this from the incoming request's `Host` header (or `X-Forwarded-Host` behind a reverse proxy).

### Flow

O11yLite implements the standard [Authorization Code Flow with PKCE](https://openid.net/specs/openid-connect-core-1_0.html#CodeFlowAuth). Unauthenticated users are redirected to your IdP, and on successful login, redirected back to `/auth/callback` where a session cookie is established.

OIDC-authenticated users have full access (equivalent to `admin` scope).

### Session management

Sessions are stored in encrypted cookies. The encryption key is either set explicitly via `O11YLITE_SESSION_SECRET` (recommended for multi-instance deployments), or auto-generated on first boot and persisted to the database.

### Logout

Clicking "Sign out" clears the local session and redirects to the login page. O11yLite does not initiate [RP-Initiated Logout](https://openid.net/specs/openid-connect-rpinitiated-1_0.html) with the IdP.

## API keys (OTLP ingestion authentication)

API keys protect OTLP ingestion endpoints (both HTTP and gRPC). They are managed through the UI under **System > API Keys**.

- **No API keys exist**: OTLP ingestion is open.
- **First API key created**: All OTLP ingestion requires a valid key.

Creating your first API key is the switch that turns on ingestion auth. Have the key ready to configure in your exporters before creating it.

When OIDC is also enabled, API keys can additionally be used to authenticate REST API requests (`/api/*`), as an alternative to browser sessions. The key must have sufficient scope (see [Scopes](#scopes)).

### Creating a key

1. Navigate to **System > API Keys** in the sidebar.
2. Click **Create API Key**.
3. Enter a name and select a scope.
4. The full key (e.g., `o11y_a1b2c3d4e5f6...`) is shown **once**. Copy it immediately.

Keys are immutable. To rotate: create a new key, update your clients, then delete the old one.

### Using a key

Include the key in the `Authorization` header with a `Bearer` prefix.

**HTTP:**

```bash
curl -H "Authorization: Bearer o11y_your_key_here" \
  https://your-o11ylite-host/v1/traces \
  -d @traces.json
```

**gRPC:**

Most OpenTelemetry SDKs support setting headers natively:

```yaml
# OpenTelemetry Collector exporter config
exporters:
  otlp:
    endpoint: your-o11ylite-host:80
    headers:
      authorization: "Bearer o11y_your_key_here"
```

```bash
# Environment variable (supported by most OTLP exporters)
export OTEL_EXPORTER_OTLP_HEADERS="authorization=Bearer o11y_your_key_here"
```

## Scopes

Each API key has a scope. Scopes form a hierarchy — higher scopes include all permissions of lower ones.

```
        admin
          |
        write
        /    \
   ingest    read
```

`ingest` and `read` are independent branches — an `ingest` key cannot query data, and a `read` key cannot send telemetry. Use `write` or `admin` if you need both. Most OTLP exporters only need `ingest`.

## Agent auth (OAuth 2.1)

O11yLite acts as its own OAuth 2.1 authorization server. It issues short-lived JWT access tokens through the Authorization Code flow with PKCE. Two kinds of clients use it:

- **Local agents**, such as the bundled skill's `auth.py`. They send no `client_id` (or an opaque one) and redirect to `localhost`. The flow auto-approves, the same model as `gh auth login`.
- **MCP clients**, such as Claude, ChatGPT, or Claude Code, connecting to the [MCP endpoint](agent-native.md#mcp-server). They identify themselves with a [Client ID Metadata Document](https://datatracker.ietf.org/doc/html/draft-ietf-oauth-client-id-metadata-document-00): the `client_id` is an HTTPS URL that serves the client's name and allowed redirect URIs. The user sees a consent page before any code is issued.

In **OIDC mode** the user logs in first (if not already). In **open mode** no login is needed.

### Public URL

The issuer, the MCP resource identifier, and the OIDC redirect URI are all derived from the instance's public URL. By default it comes from the request's `X-Forwarded-Proto`, `X-Forwarded-Host`, or `Host` header. Set it explicitly when running behind a proxy or when MCP clients connect from outside:

| Variable | Description |
|----------|-------------|
| `O11YLITE_PUBLIC_URL` | e.g. `https://o11ylite.example.com`. Must match the URL clients use. Remote MCP clients require `https`. |

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/.well-known/oauth-authorization-server` | GET | Authorization server metadata (RFC 8414). |
| `/oauth/authorize` | GET | Authorization endpoint. Auto-approves local agents; shows a consent page to MCP clients. |
| `/oauth/authorize` | POST | Consent page decision (browser only, CSRF-protected). |
| `/oauth/token` | POST | Token endpoint: `authorization_code` and `refresh_token` grants. |

### Authorization request parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `response_type` | Yes | Must be `code` |
| `client_id` | MCP clients | HTTPS URL of a Client ID Metadata Document. Omit for local agents. |
| `redirect_uri` | Yes | Local agents: `http://localhost:*` or `http://127.0.0.1:*`. MCP clients: must be listed in the metadata document (https or localhost). |
| `code_challenge` | Yes | PKCE S256 challenge (`BASE64URL(SHA256(code_verifier))`) |
| `code_challenge_method` | Yes | Must be `S256` |
| `scope` | No | Default `write`. Space-separated; any of `ingest`, `read`, `write`, `admin`. The grant is the narrowest scope that covers all requested ones. |
| `resource` | MCP clients | `<public URL>/mcp`. Binds the token to the MCP endpoint (RFC 8707). |
| `state` | Recommended | Opaque string, returned as-is in redirect |

The redirect back to the client carries `code` (or `error`), `state`, and `iss` (RFC 9207).

### Token exchange

POST to `/oauth/token` with a JSON or form-encoded body:

```json
{
  "grant_type": "authorization_code",
  "code": "<authorization_code>",
  "code_verifier": "<original_verifier>",
  "redirect_uri": "<same_redirect_uri>",
  "client_id": "<same client_id, if one was sent>",
  "resource": "<same resource, if one was sent>"
}
```

Response:

```json
{
  "access_token": "eyJhbG...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "write",
  "refresh_token": "o11yrt_..."
}
```

Refresh with `{"grant_type": "refresh_token", "refresh_token": "...", "client_id": "..."}`. An optional `scope` can narrow the new access token.

### Token details

- **Access tokens**: JWT signed with HMAC256 (derived from session secret), 1 hour TTL.
- **Audience**: tokens requested with `resource` are only accepted by `/mcp`. Tokens without it are only accepted by `/api/*`.
- **Refresh tokens**: opaque and stored in SQLite as a keyed hash. Every refresh returns a new refresh token and retires the old one. Presenting a retired token revokes the whole session. A refresh token expires after 30 days without use.
- **Scope**: follows the same hierarchy as API keys (see [Scopes](#scopes)).
- **Revocation**: not per-token. Rotating `O11YLITE_SESSION_SECRET` invalidates all access and refresh tokens.

### Security

- PKCE (S256) is mandatory, and authorization codes are single-use signed JWTs with a 5-minute TTL.
- Local agents may only redirect to `localhost`.
- MCP clients must register redirect URIs in their metadata document, and redirects must match exactly. The consent page shows the client and the redirect host, and warns when the redirect goes to `localhost`.
- When fetching client metadata documents, O11yLite requires https, refuses private, loopback, and link-local addresses, does not follow redirects, and caps the response at 64 KB and 5 seconds.
- Only public clients are supported: there are no client secrets and no `private_key_jwt`.
- Dynamic Client Registration (deprecated in the MCP 2026-07-28 spec) is not supported.

## Health endpoints

The following endpoints are always accessible, regardless of authentication settings:

- `GET /api/status` — System status
- `GET /api/health` — Health check for load balancers


