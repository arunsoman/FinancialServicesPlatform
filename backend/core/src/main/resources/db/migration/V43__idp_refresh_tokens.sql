-- V43: refresh tokens for the BFF's sessions (FAPI 2.0). The IdP issues one refresh token per
-- sign-in (authorization_code grant) so the BFF can renew the 15-minute access token while the
-- person is active — the browser never sees either token.
--
--  * stored as SHA-256 of the opaque value only (never the token itself);
--  * bound to the client (private_key_jwt at the token endpoint) AND to the DPoP key the access
--    tokens are bound to (jkt): a refresh needs both;
--  * NOT rotated (FAPI 2.0 §5.3.2.1: no refresh token rotation);
--  * lives at most as long as the BFF's absolute session (8 h), and is revoked at logout
--    (RFC 7009 revocation, part of the logout trio) or when the session expires.

CREATE TABLE IF NOT EXISTS idp_refresh_tokens (
  id            UUID PRIMARY KEY,
  token_hash    VARCHAR(64) NOT NULL UNIQUE,
  person_id     UUID        NOT NULL REFERENCES persons(id),
  tenant_id     UUID        NOT NULL REFERENCES tenants(id),
  client_id     TEXT        NOT NULL,
  jkt           TEXT        NOT NULL,
  issued_at     timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  last_used_at  timestamptz,
  revoked_at    timestamptz,
  revoke_reason TEXT
);

CREATE INDEX IF NOT EXISTS ix_idp_refresh_tokens_live ON idp_refresh_tokens (person_id) WHERE revoked_at IS NULL;
