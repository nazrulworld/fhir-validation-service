-- 3.0.0 (spec 004, IG-scoped validation): one validator context per (IG package, FHIR version).
--
-- fhir_validator_logs.included_ig_packages keeps its rows and type: each name#version entry is now its own pinned
-- context instead of one member of a shared set (research R3, R13). The combined resolved set is no longer kept:
-- every instance resolves each included IG from the package catalog, so the column is dropped.
-- A 2.x instance cannot run on this schema; stop every 2.x instance before the first 3.0 instance starts.

-- Shared package catalog revision (research R4): bumped by every package store, inside the storing transaction,
-- so instances refresh their in-memory catalog view and invalidate contexts built from replaced packages.
CREATE TABLE IF NOT EXISTS fhir_validator_schema.fhir_package_catalog (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO fhir_validator_schema.fhir_package_catalog (id, revision, updated_at)
VALUES (1, 1, now())
ON CONFLICT (id) DO NOTHING;

ALTER TABLE fhir_validator_schema.fhir_validator_logs DROP COLUMN IF EXISTS resolved_ig_packages;
