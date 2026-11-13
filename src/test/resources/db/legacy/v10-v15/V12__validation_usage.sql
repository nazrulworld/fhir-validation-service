-- 4.1.0 (spec 008, research R17): one row per authorised validation request, written in batches by the db usage sink
-- (FV_USAGE_SINK=db). Append-only; never read on the request path.
--
-- Retention is the operator's job: nothing here deletes old rows. At about 250 bytes a row, plan a periodic
--   DELETE FROM fhir_validator_schema.validation_usage WHERE created_at < now() - interval '90 days';
-- A deleted key leaves its rows with api_key_id = NULL; org_id has no foreign key, so usage never blocks organisation
-- changes (organisations are never deleted, spec 005). No resource content is ever stored.
CREATE TABLE IF NOT EXISTS fhir_validator_schema.validation_usage (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ  NOT NULL,
    request_id         VARCHAR(64)  NOT NULL,
    api_key_id         UUID         NULL REFERENCES fhir_validator_schema.api_keys (id) ON DELETE SET NULL,
    api_key_name       VARCHAR(64)  NULL,
    org_id             UUID         NOT NULL,
    fhir_version       VARCHAR(16)  NULL,
    ig                 VARCHAR(192) NULL,
    profile_count      SMALLINT     NOT NULL,
    content_type       VARCHAR(16)  NULL,
    request_bytes      INTEGER      NOT NULL,
    outcome            VARCHAR(32)  NOT NULL,
    http_status        SMALLINT     NOT NULL,
    issues_fatal       INTEGER      NOT NULL DEFAULT 0,
    issues_error       INTEGER      NOT NULL DEFAULT 0,
    issues_warning     INTEGER      NOT NULL DEFAULT 0,
    issues_information INTEGER      NOT NULL DEFAULT 0,
    client_ip          INET         NULL,
    total_ms           INTEGER      NOT NULL,
    queue_ms           INTEGER      NOT NULL,
    context_ms         INTEGER      NOT NULL,
    validate_ms        INTEGER      NOT NULL,
    cpu_time_ns        BIGINT       NOT NULL,
    allocated_bytes    BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS validation_usage_key_created_idx
    ON fhir_validator_schema.validation_usage (api_key_id, created_at);
CREATE INDEX IF NOT EXISTS validation_usage_org_created_idx
    ON fhir_validator_schema.validation_usage (org_id, created_at);
