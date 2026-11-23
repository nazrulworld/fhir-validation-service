-- Spec 014 (1.4.0): per-minute caps on POST /{version}/validate, per API key and per organisation
-- (specs/014-api-throttling/data-model.md). V15-V17 are never edited.

-- FR-006, FR-012: a key's and an organisation's own cap. NULL = no cap of its own (the service-wide default applies).
-- Adding a nullable column without a default only changes the catalog (no table rewrite).
ALTER TABLE fhir_validator_schema.api_keys ADD COLUMN rate_limit_per_minute INTEGER NULL;
ALTER TABLE fhir_validator_schema.api_keys ADD CONSTRAINT api_keys_rate_limit_chk
    CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute BETWEEN 1 AND 1000000);
-- FR-006b: keys of the system organisation are never rate limited.
ALTER TABLE fhir_validator_schema.api_keys ADD CONSTRAINT api_keys_rate_limit_system_chk
    CHECK (owner <> '00000000-0000-0000-0000-100000000001' OR rate_limit_per_minute IS NULL);

ALTER TABLE fhir_validator_schema.organisations ADD COLUMN rate_limit_per_minute INTEGER NULL;
ALTER TABLE fhir_validator_schema.organisations ADD CONSTRAINT organisations_rate_limit_chk
    CHECK (rate_limit_per_minute IS NULL OR rate_limit_per_minute BETWEEN 1 AND 1000000);
ALTER TABLE fhir_validator_schema.organisations ADD CONSTRAINT organisations_rate_limit_system_chk
    CHECK (id <> '00000000-0000-0000-0000-100000000001' OR rate_limit_per_minute IS NULL);

-- FR-006, FR-012: the two service-wide defaults live in service_settings; an absent row = not limited at that level.
-- Nested CASE, not AND/OR: PostgreSQL does not guarantee their evaluation order, and value::int must never run on
-- another setting's value (e.g. acl_security = 'enabled').
ALTER TABLE fhir_validator_schema.service_settings ADD CONSTRAINT service_settings_throttle_chk
    CHECK (CASE WHEN name IN ('throttle_default_key_per_minute', 'throttle_default_org_per_minute')
                THEN CASE WHEN value ~ '^[1-9][0-9]{0,6}$' THEN value::int <= 1000000 ELSE false END
                ELSE true END);

-- FR-005 (research R4): each instance's admitted requests per subject and second, shared once a second. UNLOGGED:
-- rows live at most two minutes, and losing them in a crash only resets windows. No foreign keys: the rows are
-- short-lived and must never block key or organisation changes.
--   scope 'K' = API key, 'O' = organisation; count = absolute count of that instance in that second.
CREATE UNLOGGED TABLE fhir_validator_schema.throttle_counts (
    scope        CHAR(1) NOT NULL CHECK (scope IN ('K', 'O')),
    subject_id   UUID    NOT NULL,
    epoch_second BIGINT  NOT NULL,
    instance_id  UUID    NOT NULL,
    count        INTEGER NOT NULL CHECK (count >= 1),
    PRIMARY KEY (scope, subject_id, epoch_second, instance_id)
);
CREATE INDEX throttle_counts_second_idx ON fhir_validator_schema.throttle_counts (epoch_second);

-- Research R4: live instances (heartbeat on database time); peers = other rows seen in the last 5 seconds.
CREATE UNLOGGED TABLE fhir_validator_schema.throttle_instances (
    instance_id   UUID PRIMARY KEY,
    instance_name VARCHAR(128) NULL,
    seen_at       TIMESTAMPTZ  NOT NULL
);

-- FR-015 (clarification Q3): the initial caps. Seeded once by this migration, so operator changes are never overwritten.
INSERT INTO fhir_validator_schema.service_settings (name, value, updated_by)
VALUES ('throttle_default_key_per_minute', '600', 'V18'),
       ('throttle_default_org_per_minute', '1200', 'V18')
ON CONFLICT (name) DO NOTHING;

-- The trial organisation (seeded by V15).
UPDATE fhir_validator_schema.organisations SET rate_limit_per_minute = 60
WHERE id = '00000000-0000-0000-0000-100000000002' AND rate_limit_per_minute IS NULL;

-- The trial validator key, when it exists already; otherwise it gets 30 when it is first installed at startup
-- (ApiKeyRepository.upsertWellKnown, insert path only).
UPDATE fhir_validator_schema.api_keys SET rate_limit_per_minute = 30
WHERE id = '00000000-0000-0000-0000-200000000002' AND rate_limit_per_minute IS NULL;
