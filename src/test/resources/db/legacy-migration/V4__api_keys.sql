-- 1.0.0: API keys for administrative and (optionally) validation access. Only SHA-256 hashes are stored.
CREATE TABLE IF NOT EXISTS fhir_validator_schema.api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(64) UNIQUE NOT NULL,
    key_hash     BYTEA UNIQUE NOT NULL,
    role         VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'VALIDATE')),
    created_at   TIMESTAMPTZ DEFAULT now(),
    revoked_at   TIMESTAMPTZ NULL,
    last_used_at TIMESTAMPTZ NULL
);
