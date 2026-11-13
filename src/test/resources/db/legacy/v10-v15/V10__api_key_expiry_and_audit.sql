-- 4.1.0 (spec 008): optional key expiry (NULL = never) and the client address of the last use.
--
-- A key is active while revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now()). last_used_ip is the client
-- address resolved by the trusted-proxy rule (FV_AUTH_TRUSTED_PROXIES), written together with last_used_at.
ALTER TABLE fhir_validator_schema.api_keys
    ADD COLUMN IF NOT EXISTS expires_at   TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS last_used_ip INET        NULL;
