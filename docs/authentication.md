# Authentication

O11yLite supports three authentication mechanisms:

1. **OIDC** — Protects the UI and REST API. Bring your own identity provider.
2. **API keys** — Protects OTLP ingestion. Managed via the UI.
3. **Agent auth (OAuth)** — Short-lived JWT access tokens for LLM agents and scripts. Uses Authorization Code + PKCE.

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

## Agent auth (OAuth 2.0 Authorization Code + PKCE)

O11yLite acts as its own OAuth 2.0 authorization server, issuing short-lived JWT access tokens via the standard Authorization Code flow with PKCE. This is designed for LLM agents and scripts that need programmatic API access without permanent API keys.

### How it works

1. The agent starts a temporary local HTTP server and opens the authorization URL in the user's browser.
2. O11yLite validates the request and redirects back to `localhost` with a signed authorization code.
3. The agent exchanges the code (plus PKCE verifier) for a JWT access token.
4. The agent uses the token in `Authorization: Bearer <token>` headers to call `/api/*` endpoints.

In **open mode**, the entire flow completes instantly with no user interaction. In **OIDC mode**, the user must log in first (if not already), then the flow auto-approves.

### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/oauth/authorize` | GET | Authorization endpoint. Returns a 302 redirect with a signed authorization code. |
| `/oauth/token` | POST | Token endpoint. Exchanges code + verifier for a JWT access token. |

### Authorization request parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `response_type` | Yes | Must be `code` |
| `redirect_uri` | Yes | Must be `http://localhost:*` or `http://127.0.0.1:*` |
| `code_challenge` | Yes | PKCE S256 challenge (`BASE64URL(SHA256(code_verifier))`) |
| `code_challenge_method` | Yes | Must be `S256` |
| `scope` | No | Default `write`. One of: `ingest`, `read`, `write`, `admin` |
| `state` | Recommended | Opaque string, returned as-is in redirect |

### Token exchange

POST to `/oauth/token` with JSON or form-encoded body:

```json
{
  "grant_type": "authorization_code",
  "code": "<authorization_code>",
  "code_verifier": "<original_verifier>",
  "redirect_uri": "<same_redirect_uri>"
}
```

Response:

```json
{
  "access_token": "eyJhbG...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "write"
}
```

### Token details

- **Type**: JWT signed with HMAC256 (derived from session secret)
- **TTL**: 1 hour
- **Scope**: Follows the same hierarchy as API keys (see [Scopes](#scopes))
- **Revocation**: Not per-token. Rotating `O11YLITE_SESSION_SECRET` invalidates all tokens.

### Security

- `redirect_uri` restricted to `http://localhost:*` and `http://127.0.0.1:*` only
- PKCE (S256) is mandatory — authorization codes cannot be exchanged without the correct verifier
- Authorization codes are signed JWTs with a 5-minute TTL
- No client registration or consent screen — auto-approves (same model as `gh auth login`)

## Health endpoints

The following endpoints are always accessible, regardless of authentication settings:

- `GET /api/status` — System status
- `GET /api/health` — Health check for load balancers


