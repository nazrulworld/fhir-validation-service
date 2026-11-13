-- 3.1.0 (spec 005, organisation ownership): organisations own API keys, IG packages and included validator
-- contexts.
--
-- Ownership of included contexts lives in a child table, fhir_validator_snapshots, not on fhir_validator_logs: that
-- table holds one row per FHIR version, so columns there would give every context of a version one owner (research
-- R1). included_ig_packages stays the membership list.
--
-- Rolling upgrade (research R11): every new column has a database default (owner = the system organisation,
-- is_shareable = true), so 3.0 instances, which don't know the columns, keep writing valid rows. An included
-- context with no fhir_validator_snapshots row is read as owned by the system organisation and shareable. Create
-- organisations and private content only after every instance runs 3.1.

CREATE TABLE IF NOT EXISTS fhir_validator_schema.organisations (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name_short            VARCHAR(32)  NOT NULL CHECK (name_short ~ '^[A-Za-z0-9_-]{2,32}$'),
    name_long             VARCHAR(256) NOT NULL,
    type                  VARCHAR(16)  NOT NULL CHECK (type IN ('SYSTEM', 'COMMERCIAL', 'NON_COMMERCIAL')),
    is_active             BOOLEAN      NOT NULL DEFAULT true,
    address_lines         TEXT[]       NOT NULL DEFAULT '{}',
    post_code             VARCHAR(16)  NULL,
    country               CHAR(2)      NULL CHECK (country ~ '^[A-Z]{2}$'),
    email                 VARCHAR(254) NULL,
    phone                 VARCHAR(32)  NULL,
    website               VARCHAR(256) NULL,
    contact_person_name   VARCHAR(128) NULL,
    contact_person_mobile VARCHAR(32)  NULL,
    contact_person_email  VARCHAR(254) NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NULL,
    -- FR-003: exactly the well-known id is the system organisation, and it is always active.
    CONSTRAINT organisations_system_id_chk
        CHECK ((type = 'SYSTEM') = (id = '00000000-0000-0000-0000-000000000001')),
    CONSTRAINT organisations_system_active_chk CHECK (type <> 'SYSTEM' OR is_active)
);

CREATE UNIQUE INDEX IF NOT EXISTS organisations_one_system_uq
    ON fhir_validator_schema.organisations ((true)) WHERE type = 'SYSTEM';
CREATE UNIQUE INDEX IF NOT EXISTS organisations_name_short_uq
    ON fhir_validator_schema.organisations (lower(name_short));

-- The system organisation. Fields configured through FV_SYSTEM_ORG_* are applied at every start (research R10).
INSERT INTO fhir_validator_schema.organisations (id, name_short, name_long, type, is_active)
VALUES ('00000000-0000-0000-0000-000000000001', 'system', 'FHIR Validation Service', 'SYSTEM', true)
ON CONFLICT DO NOTHING;

-- Existing keys (the bootstrap key included) and packages belong to the system organisation (FR-009). Organisations
-- are never deleted (FR-005): ON DELETE RESTRICT makes a manual delete fail while anything references one.
ALTER TABLE fhir_validator_schema.api_keys
    ADD COLUMN IF NOT EXISTS owner UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT;

ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ADD COLUMN IF NOT EXISTS owner UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS is_shareable BOOLEAN NOT NULL DEFAULT true;

-- Access of each included (pinned) validator context.
CREATE TABLE IF NOT EXISTS fhir_validator_schema.fhir_validator_snapshots (
    fhir_version VARCHAR(16)  NOT NULL,
    ig_package   VARCHAR(192) NOT NULL,
    owner        UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT,
    is_shareable BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NULL,
    PRIMARY KEY (fhir_version, ig_package)
);

-- One system-owned, shareable row per context already included.
INSERT INTO fhir_validator_schema.fhir_validator_snapshots (fhir_version, ig_package)
SELECT fhir_version, unnest(included_ig_packages)
FROM fhir_validator_schema.fhir_validator_logs
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS api_keys_owner_idx ON fhir_validator_schema.api_keys (owner);
CREATE INDEX IF NOT EXISTS fhir_implementation_guides_owner_idx ON fhir_validator_schema.fhir_implementation_guides (owner);
CREATE INDEX IF NOT EXISTS fhir_implementation_guides_private_idx
    ON fhir_validator_schema.fhir_implementation_guides (owner) WHERE NOT is_shareable;
CREATE INDEX IF NOT EXISTS fhir_validator_snapshots_owner_idx ON fhir_validator_schema.fhir_validator_snapshots (owner);
