-- Clients registered through Dynamic Client Registration (RFC 7591).
-- Only public clients are stored: redirect URIs are the only credential,
-- and every authorization still requires user consent. Timestamps are
-- epoch ms. redirect_uris is a JSON array kept verbatim for exact matching.
CREATE TABLE IF NOT EXISTS oauth_clients (
  client_id     TEXT PRIMARY KEY,
  client_name   TEXT NOT NULL,
  client_uri    TEXT,
  redirect_uris TEXT NOT NULL,
  scope         TEXT,
  created_at    INTEGER NOT NULL
);
--;;
CREATE INDEX IF NOT EXISTS idx_oauth_clients_created
  ON oauth_clients (created_at);
