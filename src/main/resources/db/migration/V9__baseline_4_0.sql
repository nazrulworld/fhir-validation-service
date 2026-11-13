-- 4.3.0: the 4.0.0 schema in one migration. It replaces the original V1 to V9 (0.1.0 to 4.0.0), which are retired.
--
-- Only a new database runs this file. A database that already ran the original V1 to V9 keeps its history; Flyway
-- validates those retired versions leniently (DatabaseService) and applies V10 onwards as usual. A schema older than
-- 4.0.0 is refused at startup: upgrade it with 4.2.0 first (docs/upgrade-4.3.md).
--
-- The result must equal the original chain applied in order (SquashedBaselineIT): the same tables, column order,
-- defaults, constraint and index names (later migrations drop and re-add some by name), and the same seed rows.

CREATE SCHEMA IF NOT EXISTS fhir_validator_schema;

-- Unused since 1.0.0; kept so a squashed database matches an upgraded one.
DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                       WHERE t.typname = 'fhir_version_enum' AND n.nspname = 'fhir_validator_schema') THEN
            CREATE TYPE fhir_validator_schema.fhir_version_enum AS ENUM ('DSTU2', 'STU3', 'R4', 'R4B', 'R5', 'R6');
        END IF;
    END
$$;

-- Organisations own API keys, IG packages and included validator contexts (spec 005).
CREATE TABLE fhir_validator_schema.organisations (
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
    -- The 4.0 well-known id; V11 re-keys it.
    CONSTRAINT organisations_system_id_chk
        CHECK ((type = 'SYSTEM') = (id = '00000000-0000-0000-0000-000000000001')),
    CONSTRAINT organisations_system_active_chk CHECK (type <> 'SYSTEM' OR is_active)
);

CREATE UNIQUE INDEX organisations_one_system_uq
    ON fhir_validator_schema.organisations ((true)) WHERE type = 'SYSTEM';
CREATE UNIQUE INDEX organisations_name_short_uq
    ON fhir_validator_schema.organisations (lower(name_short));

INSERT INTO fhir_validator_schema.organisations (id, name_short, name_long, type, is_active)
VALUES ('00000000-0000-0000-0000-000000000001', 'system', 'FHIR Validation Service', 'SYSTEM', true);

-- Stored IG packages.
CREATE TABLE fhir_validator_schema.fhir_implementation_guides (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ig_package_id      VARCHAR(127) NOT NULL,
    ig_package_version VARCHAR(64)  NOT NULL,
    ig_package_meta    JSONB        NOT NULL,
    content_raw        BYTEA        NOT NULL,
    dependencies       TEXT[]       NOT NULL DEFAULT '{}',
    created_at         TIMESTAMPTZ  DEFAULT NOW(),
    content_sha256     BYTEA        NULL,
    source             VARCHAR(16)  NOT NULL DEFAULT 'UNKNOWN',
    updated_at         TIMESTAMPTZ  NULL,
    expanded_bytes     BIGINT       NULL,
    owner              UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT,
    is_shareable       BOOLEAN      NOT NULL DEFAULT true,
    UNIQUE (ig_package_id, ig_package_version),
    CONSTRAINT fhir_implementation_guides_source_chk
        CHECK (source IN ('UPLOAD', 'REGISTRY', 'URL', 'UNKNOWN'))
);

CREATE INDEX idx_fhir_implementation_guides_package_meta
    ON fhir_validator_schema.fhir_implementation_guides USING GIN (ig_package_meta);
CREATE INDEX idx_fhir_implementation_guides_dependencies
    ON fhir_validator_schema.fhir_implementation_guides USING GIN (dependencies);
CREATE INDEX fhir_implementation_guides_owner_idx
    ON fhir_validator_schema.fhir_implementation_guides (owner);
CREATE INDEX fhir_implementation_guides_private_idx
    ON fhir_validator_schema.fhir_implementation_guides (owner) WHERE NOT is_shareable;

-- One row per (validator, FHIR version); included_ig_packages lists the included (pinned) contexts.
CREATE TABLE fhir_validator_schema.fhir_validator_logs (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    validator_id         VARCHAR(36) NOT NULL,
    fhir_version         VARCHAR(16) NOT NULL,
    included_ig_packages TEXT[]      NOT NULL DEFAULT '{}',
    is_active            BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP   NULL,
    revision             BIGINT      NOT NULL DEFAULT 1,
    UNIQUE (validator_id, fhir_version)
);

CREATE INDEX fhir_validator_logs_validator_is_active_idx ON fhir_validator_schema.fhir_validator_logs (is_active);

INSERT INTO fhir_validator_schema.fhir_validator_logs (validator_id, fhir_version)
VALUES ('standard_validator', 'STU3'),
       ('standard_validator', 'R4'),
       ('standard_validator', 'R4B'),
       ('standard_validator', 'R5');

-- API keys; only SHA-256 hashes are stored.
CREATE TABLE fhir_validator_schema.api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(64) UNIQUE NOT NULL,
    key_hash     BYTEA UNIQUE NOT NULL,
    role         VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'VALIDATE')),
    created_at   TIMESTAMPTZ DEFAULT now(),
    revoked_at   TIMESTAMPTZ NULL,
    last_used_at TIMESTAMPTZ NULL,
    owner        UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT
);

CREATE INDEX api_keys_owner_idx ON fhir_validator_schema.api_keys (owner);

-- Shared package catalog revision, bumped by every package store.
CREATE TABLE fhir_validator_schema.fhir_package_catalog (
    id         SMALLINT PRIMARY KEY CHECK (id = 1),
    revision   BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO fhir_validator_schema.fhir_package_catalog (id, revision, updated_at)
VALUES (1, 1, now());

-- Access and terminology setting of each included validator context.
CREATE TABLE fhir_validator_schema.fhir_validator_snapshots (
    fhir_version VARCHAR(16)  NOT NULL,
    ig_package   VARCHAR(192) NOT NULL,
    owner        UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT,
    is_shareable BOOLEAN      NOT NULL DEFAULT true,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NULL,
    tx_mode      VARCHAR(16)  NOT NULL DEFAULT 'NONE'
        CHECK (tx_mode IN ('NONE', 'SERVER', 'ECOSYSTEM')),
    tx_server    VARCHAR(512) NULL,
    PRIMARY KEY (fhir_version, ig_package),
    CONSTRAINT fhir_validator_snapshots_tx_chk CHECK (
        (tx_mode = 'NONE' AND tx_server IS NULL) OR
        (tx_mode = 'SERVER' AND tx_server IS NOT NULL) OR
        (tx_mode = 'ECOSYSTEM'))
);

CREATE INDEX fhir_validator_snapshots_owner_idx ON fhir_validator_schema.fhir_validator_snapshots (owner);

-- The last good HL7 terminology registry server list per FHIR version.
CREATE TABLE fhir_validator_schema.tx_registry_snapshot (
    fhir_version         VARCHAR(16) PRIMARY KEY,
    fetched_at           TIMESTAMPTZ NOT NULL,
    registry_last_update TIMESTAMPTZ NULL,
    body                 JSONB       NOT NULL -- the kept entries (data-model §2.1), not the raw registry answer
);
