-- OAuth refresh tokens issued by the built-in authorization server.
-- Only the SHA-256 hash of a token is stored. Every refresh rotates the
-- token: the presented row is marked used and a new row is inserted in the
-- same family. Presenting a used token again is treated as theft and
-- revokes the whole family (OAuth 2.1 §4.3.1). Timestamps are epoch ms.
CREATE TABLE IF NOT EXISTS oauth_refresh_tokens (
  token_hash  TEXT PRIMARY KEY,
  family_id   TEXT NOT NULL,
  sub         TEXT NOT NULL,
  scope       TEXT NOT NULL,
  client_id   TEXT,
  resource    TEXT,
  created_at  INTEGER NOT NULL,
  expires_at  INTEGER NOT NULL,
  used_at     INTEGER
);
--;;
CREATE INDEX IF NOT EXISTS idx_oauth_refresh_tokens_family
  ON oauth_refresh_tokens (family_id);
--;;
CREATE INDEX IF NOT EXISTS idx_oauth_refresh_tokens_expires
  ON oauth_refresh_tokens (expires_at);
