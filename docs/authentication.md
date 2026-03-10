# Authentication

O11yLite supports two independent, opt-in authentication mechanisms:

1. **OIDC** — Protects the UI and REST API. Bring your own identity provider.
2. **API keys** — Protects OTLP ingestion. Managed via the UI.

Both are optional. With zero configuration, everything is open — no login screens, no tokens required.

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

## Health endpoints

The following endpoints are always accessible, regardless of authentication settings:

- `GET /api/status` — System status
- `GET /api/health` — Health check for load balancers


