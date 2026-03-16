#!/usr/bin/env python3
"""O11yLite agent authentication script.

Obtains a Bearer token (via API key or OAuth PKCE) and discovers the
Inertia asset version.  Prints a JSON object to stdout:

    {"token": "...", "base_url": "...", "inertia_version": "..."}

The agent executes this script but never reads its source.

Usage:
    python3 auth.py <base-url> [--scope write] [--refresh]

If <base-url> is omitted, reads O11YLITE_AGENT_URL env var.
If O11YLITE_AGENT_API_KEY is set, uses it directly (no OAuth).
"""

import argparse
import hashlib
import html
import http.server
import json
import os
import re
import secrets
import stat
import sys
import threading
import time
import ssl
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from base64 import urlsafe_b64encode
from pathlib import Path
from typing import Any, NoReturn

CREDENTIALS_PATH = Path.home() / ".o11ylite" / "credentials.json"
CALLBACK_TIMEOUT = 120  # seconds


def _ssl_context() -> ssl.SSLContext | None:
    """Return an unverified SSL context if O11YLITE_INSECURE is set."""
    if os.environ.get("O11YLITE_INSECURE"):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    return None


# ---------------------------------------------------------------------------
# PKCE helpers (stdlib only, no external deps)
# ---------------------------------------------------------------------------


def _generate_code_verifier() -> str:
    """RFC 7636 code_verifier: 43-128 unreserved chars."""
    return secrets.token_urlsafe(64)[:96]


def _generate_code_challenge(verifier: str) -> str:
    """RFC 7636 S256 code_challenge."""
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    return urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")


# ---------------------------------------------------------------------------
# Credential cache
# ---------------------------------------------------------------------------


def _load_cached(base_url: str) -> dict | None:
    """Return cached credentials if token is still valid."""
    if not CREDENTIALS_PATH.exists():
        return None
    try:
        data = json.loads(CREDENTIALS_PATH.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    if data.get("base_url") != base_url:
        return None
    # API key tokens never expire
    if data.get("token_source") == "api_key":
        return data
    # OAuth tokens: check expiry with 60s buffer
    expires_at = data.get("expires_at", 0)
    if time.time() < expires_at - 60:
        return data
    return None


def _save_credentials(data: dict) -> None:
    """Write credentials to disk (chmod 600)."""
    CREDENTIALS_PATH.parent.mkdir(parents=True, exist_ok=True)
    CREDENTIALS_PATH.write_text(json.dumps(data, indent=2))
    CREDENTIALS_PATH.chmod(stat.S_IRUSR | stat.S_IWUSR)


# ---------------------------------------------------------------------------
# Inertia version discovery
# ---------------------------------------------------------------------------


def _discover_inertia_version(base_url: str) -> str:
    """GET /explore and extract the Inertia version from the data-page attribute.

    We use /explore rather than / because / redirects to /explore, and
    urllib's redirect handling may not follow all redirect types cleanly.
    """
    req = urllib.request.Request(base_url + "/explore")
    try:
        with urllib.request.urlopen(req, timeout=10, context=_ssl_context()) as resp:
            body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as exc:
        _die(f"Failed to reach {base_url}: {exc}")

    # Parse: <div id="app" data-page="...">
    match = re.search(r'data-page="([^"]+)"', body)
    if not match:
        _die("Could not find Inertia data-page attribute in response from /")

    page_json = html.unescape(match.group(1))
    try:
        page_data = json.loads(page_json)
    except json.JSONDecodeError:
        _die("Could not parse Inertia page data JSON")

    version = page_data.get("version")
    if not version:
        _die("No 'version' field in Inertia page data")
    return version


# ---------------------------------------------------------------------------
# OAuth PKCE flow
# ---------------------------------------------------------------------------


class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    """Handles a single OAuth redirect callback."""

    code: str | None = None
    error: str | None = None

    def do_GET(self):
        params = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        code = params.get("code", [None])[0]
        error = params.get("error", [None])[0]

        if code:
            _CallbackHandler.code = code
            self._respond("Authentication successful! You can close this tab.")
        else:
            desc = params.get("error_description", [error or "unknown"])[0]
            _CallbackHandler.error = desc
            self._respond(f"Authentication failed: {desc}")

    def _respond(self, message: str):
        body = message.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        pass  # suppress request logging


def _run_pkce_flow(base_url: str, scope: str) -> dict:
    """Execute the full OAuth PKCE flow. Returns token response dict."""
    verifier = _generate_code_verifier()
    challenge = _generate_code_challenge(verifier)
    state = secrets.token_urlsafe(32)

    # Bind callback server on random port
    server = http.server.HTTPServer(("127.0.0.1", 0), _CallbackHandler)
    port = server.server_address[1]
    redirect_uri = f"http://localhost:{port}/callback"

    # Build authorize URL
    params = urllib.parse.urlencode(
        {
            "response_type": "code",
            "redirect_uri": redirect_uri,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "scope": scope,
            "state": state,
        }
    )
    authorize_url = f"{base_url}/oauth/authorize?{params}"

    # Reset handler state
    _CallbackHandler.code = None
    _CallbackHandler.error = None

    # Start server in background, open browser
    server_thread = threading.Thread(target=server.handle_request, daemon=True)
    server_thread.start()

    print(f"Opening browser for authentication...", file=sys.stderr)
    webbrowser.open(authorize_url)

    # Wait for callback
    server_thread.join(timeout=CALLBACK_TIMEOUT)
    server.server_close()

    if _CallbackHandler.error:
        _die(f"Authorization failed: {_CallbackHandler.error}")
    if not _CallbackHandler.code:
        _die("Timed out waiting for authorization callback")

    # Exchange code for token
    token_body = json.dumps(
        {
            "grant_type": "authorization_code",
            "code": _CallbackHandler.code,
            "code_verifier": verifier,
            "redirect_uri": redirect_uri,
        }
    ).encode("utf-8")

    token_req = urllib.request.Request(
        f"{base_url}/oauth/token",
        data=token_body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    try:
        with urllib.request.urlopen(
            token_req, timeout=10, context=_ssl_context()
        ) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        _die(f"Token exchange failed ({exc.code}): {body}")
    except urllib.error.URLError as exc:
        _die(f"Token exchange failed: {exc}")


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------


def _die(message: str) -> NoReturn:
    print(f"error: {message}", file=sys.stderr)
    sys.exit(1)


def _output(token: str, base_url: str, inertia_version: str):
    print(
        json.dumps(
            {
                "token": token,
                "base_url": base_url,
                "inertia_version": inertia_version,
            }
        )
    )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    parser = argparse.ArgumentParser(description="O11yLite agent authentication")
    parser.add_argument("base_url", nargs="?", help="O11yLite base URL")
    parser.add_argument("--scope", default="write", help="OAuth scope (default: write)")
    parser.add_argument(
        "--refresh",
        action="store_true",
        help="Force re-discovery of Inertia version and refresh token if expired",
    )
    args = parser.parse_args()

    # Resolve base URL
    base_url = args.base_url or os.environ.get("O11YLITE_AGENT_URL")
    if not base_url:
        _die("No base URL provided. Pass as argument or set O11YLITE_AGENT_URL.")
    base_url = base_url.rstrip("/")

    # Check cache (unless --refresh)
    if not args.refresh:
        cached = _load_cached(base_url)
        if cached and cached.get("inertia_version"):
            _output(cached["token"], base_url, cached["inertia_version"])
            return

    # Resolve token
    api_key = os.environ.get("O11YLITE_AGENT_API_KEY")
    if api_key:
        token = api_key
        token_source = "api_key"
        expires_at = 0
        scope = args.scope
    else:
        # Check if we have a still-valid cached OAuth token (refresh may only
        # need version re-discovery, not a full re-auth)
        cached = _load_cached(base_url)
        if cached and cached.get("token_source") == "oauth":
            token = cached["token"]
            token_source = "oauth"
            expires_at = cached.get("expires_at", 0)
            scope = cached.get("scope", args.scope)
        else:
            token_resp = _run_pkce_flow(base_url, args.scope)
            token = token_resp["access_token"]
            token_source = "oauth"
            expires_at = time.time() + token_resp.get("expires_in", 3600)
            scope = token_resp.get("scope", args.scope)

    # Discover Inertia version
    inertia_version = _discover_inertia_version(base_url)

    # Cache and output
    _save_credentials(
        {
            "base_url": base_url,
            "token": token,
            "token_source": token_source,
            "expires_at": expires_at,
            "scope": scope,
            "inertia_version": inertia_version,
        }
    )

    _output(token, base_url, inertia_version)


if __name__ == "__main__":
    main()
