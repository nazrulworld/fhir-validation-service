-- 1.0.0: the complete schema in one migration. It replaces every earlier migration (V1 to V15 of development
-- builds), so new migrations start at V16.
--
-- Only a new database runs this file. A database that ran the earlier migrations keeps its history; DatabaseService
-- validates versions up to 15 leniently and V16 onwards strictly. A database that stops before V15 is refused.
--
-- Never edit this file once released: a database created from it stores its checksum, and databases with the earlier
-- history validate versions up to 15 leniently, so an edit would reach only some databases. Change the schema with
-- V16 onwards.
--
-- The result must equal the earlier migrations applied in order (SquashedBaselineIT): the same tables, column order,
-- defaults, constraint and index names, functions, triggers and seed rows. The well-known ids match
-- nzi.fhir.validator.core.Constant (ConstantMigrationConsistencyTest).

CREATE SCHEMA IF NOT EXISTS fhir_validator_schema;

-- Unused; kept so a new database matches an upgraded one.
DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                       WHERE t.typname = 'fhir_version_enum' AND n.nspname = 'fhir_validator_schema') THEN
            CREATE TYPE fhir_validator_schema.fhir_version_enum AS ENUM ('DSTU2', 'STU3', 'R4', 'R4B', 'R5', 'R6');
        END IF;
    END
$$;

-- Organisations own API keys, IG packages and included validator contexts.
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
    CONSTRAINT organisations_system_active_chk CHECK (type <> 'SYSTEM' OR is_active),
    -- Exactly the well-known id is the system organisation.
    CONSTRAINT organisations_system_id_chk
        CHECK ((type = 'SYSTEM') = (id = '00000000-0000-0000-0000-100000000001'))
);

CREATE UNIQUE INDEX organisations_one_system_uq
    ON fhir_validator_schema.organisations ((true)) WHERE type = 'SYSTEM';
CREATE UNIQUE INDEX organisations_name_short_uq
    ON fhir_validator_schema.organisations (lower(name_short));

-- The well-known organisations. Fields configured through FV_SYSTEM_ORG_* are applied at every start.
INSERT INTO fhir_validator_schema.organisations (id, name_short, name_long, type, is_active)
VALUES ('00000000-0000-0000-0000-100000000001', 'system', 'FHIR Validation Service', 'SYSTEM', true),
       ('00000000-0000-0000-0000-100000000002', 'trial', 'Free trial validation service', 'NON_COMMERCIAL', true);

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
    -- Expanded size of the archive, so an include that would not fit in the heap is refused before it is committed.
    expanded_bytes     BIGINT       NULL,
    owner              UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-100000000001'
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

-- One row per (validator, FHIR version); included_ig_packages lists the included (pinned) contexts, and revision
-- increases on every change so instances apply it.
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

-- API keys; only SHA-256 hashes are stored. A key is active while revoked_at IS NULL AND (expires_at IS NULL OR
-- expires_at > now()). last_used_ip is the client address resolved by the trusted-proxy rule.
CREATE TABLE fhir_validator_schema.api_keys (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(64) UNIQUE NOT NULL,
    key_hash     BYTEA UNIQUE NOT NULL,
    role         VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN', 'VALIDATE')),
    created_at   TIMESTAMPTZ DEFAULT now(),
    revoked_at   TIMESTAMPTZ NULL,
    last_used_at TIMESTAMPTZ NULL,
    owner        UUID NOT NULL DEFAULT '00000000-0000-0000-0000-100000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT,
    expires_at   TIMESTAMPTZ NULL,
    last_used_ip INET        NULL
);

CREATE INDEX api_keys_owner_idx ON fhir_validator_schema.api_keys (owner);

-- Hashes of revoked well-known keys ('system administrator' and 'trial validator', installed from FV_ADMIN_API_KEY /
-- FV_VALIDATOR_API_KEY). Startup never installs a value whose hash is listed.
CREATE TABLE fhir_validator_schema.revoked_key_hashes (
    key_hash   BYTEA       PRIMARY KEY,
    key_id     UUID        NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Any revocation of a well-known key records its hash, whatever revoked it (the command line or hand-written SQL).
CREATE OR REPLACE FUNCTION fhir_validator_schema.record_revoked_well_known_key() RETURNS trigger AS $$
BEGIN
    IF NEW.revoked_at IS NOT NULL AND OLD.revoked_at IS NULL
       AND NEW.id IN ('00000000-0000-0000-0000-200000000001', '00000000-0000-0000-0000-200000000002') THEN
        INSERT INTO fhir_validator_schema.revoked_key_hashes (key_hash, key_id)
        VALUES (OLD.key_hash, NEW.id)
        ON CONFLICT (key_hash) DO NOTHING;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER api_keys_record_revoked_well_known
    AFTER UPDATE OF revoked_at ON fhir_validator_schema.api_keys
    FOR EACH ROW EXECUTE FUNCTION fhir_validator_schema.record_revoked_well_known_key();

-- Shared package catalog revision, bumped by every package store.
CREATE TABLE fhir_validator_schema.fhir_package_catalog (
    id         SMALLINT PRIMARY KEY CHECK (id = 1),
    revision   BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO fhir_validator_schema.fhir_package_catalog (id, revision, updated_at)
VALUES (1, 1, now());

-- Access, terminology setting and active flag of each included validator context. A context whose FHIR version is
-- not enabled (FV_ENABLED_FHIR_VERSIONS) is marked inactive at startup and never built.
CREATE TABLE fhir_validator_schema.fhir_validator_snapshots (
    fhir_version    VARCHAR(16)  NOT NULL,
    ig_package      VARCHAR(192) NOT NULL,
    owner           UUID         NOT NULL DEFAULT '00000000-0000-0000-0000-100000000001'
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT,
    is_shareable    BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NULL,
    tx_mode         VARCHAR(16)  NOT NULL DEFAULT 'NONE'
        CHECK (tx_mode IN ('NONE', 'SERVER', 'ECOSYSTEM')),
    tx_server       VARCHAR(512) NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    inactive_reason VARCHAR(32)  NULL,
    PRIMARY KEY (fhir_version, ig_package),
    CONSTRAINT fhir_validator_snapshots_tx_chk CHECK (
        (tx_mode = 'NONE' AND tx_server IS NULL) OR
        (tx_mode = 'SERVER' AND tx_server IS NOT NULL) OR
        (tx_mode = 'ECOSYSTEM')),
    CONSTRAINT fhir_validator_snapshots_active_chk CHECK (is_active = (inactive_reason IS NULL)),
    CONSTRAINT fhir_validator_snapshots_reason_chk
        CHECK (inactive_reason IS NULL OR inactive_reason IN ('fhir-version-disabled'))
);

CREATE INDEX fhir_validator_snapshots_owner_idx ON fhir_validator_schema.fhir_validator_snapshots (owner);
CREATE INDEX fhir_validator_snapshots_inactive_idx
    ON fhir_validator_schema.fhir_validator_snapshots (fhir_version) WHERE NOT is_active;

-- The last good HL7 terminology registry server list per FHIR version.
CREATE TABLE fhir_validator_schema.tx_registry_snapshot (
    fhir_version         VARCHAR(16) PRIMARY KEY,
    fetched_at           TIMESTAMPTZ NOT NULL,
    registry_last_update TIMESTAMPTZ NULL,
    body                 JSONB       NOT NULL -- the kept entries (data-model §2.1), not the raw registry answer
);

-- One row per authorised validation request, written in batches by the db usage sink (FV_USAGE_SINK=db).
-- Append-only; never read on the request path. Retention is the operator's job (docs/operations.md). A deleted key
-- leaves its rows with api_key_id = NULL; org_id has no foreign key, so usage never blocks organisation changes.
-- No resource content is ever stored.
CREATE TABLE fhir_validator_schema.validation_usage (
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

CREATE INDEX validation_usage_key_created_idx
    ON fhir_validator_schema.validation_usage (api_key_id, created_at);
CREATE INDEX validation_usage_org_created_idx
    ON fhir_validator_schema.validation_usage (org_id, created_at);

-- Service-wide settings shared by every instance: 'acl_security' holds the access-control mode of the most recent
-- start ('enabled' | 'disabled'), 'fhir_versions' the served FHIR versions. A missing row means nothing stored yet.
CREATE TABLE fhir_validator_schema.service_settings (
    name       VARCHAR(64)  PRIMARY KEY,
    value      VARCHAR(256) NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by VARCHAR(128) NULL,
    CONSTRAINT service_settings_acl_chk CHECK (name <> 'acl_security' OR value IN ('enabled', 'disabled'))
);
