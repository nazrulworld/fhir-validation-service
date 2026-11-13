-- 4.2.0 (spec 009, data-model §1): an active flag per validator context. A context whose FHIR version is not enabled
-- (FV_ENABLED_FHIR_VERSIONS) is marked inactive at startup and never built; it becomes active again when the version
-- is enabled again. The included set in fhir_validator_logs is not changed by (de)activation.
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ADD COLUMN IF NOT EXISTS is_active       BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS inactive_reason VARCHAR(32) NULL;

-- Every included context needs a row to carry its flag: add a system-owned, shareable one (the column defaults) for
-- any included package without one, as V8 did.
INSERT INTO fhir_validator_schema.fhir_validator_snapshots (fhir_version, ig_package)
SELECT fhir_version, unnest(included_ig_packages)
FROM fhir_validator_schema.fhir_validator_logs
ON CONFLICT DO NOTHING;

ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ADD CONSTRAINT fhir_validator_snapshots_active_chk CHECK (is_active = (inactive_reason IS NULL)),
    ADD CONSTRAINT fhir_validator_snapshots_reason_chk
        CHECK (inactive_reason IS NULL OR inactive_reason IN ('fhir-version-disabled'));

CREATE INDEX IF NOT EXISTS fhir_validator_snapshots_inactive_idx
    ON fhir_validator_schema.fhir_validator_snapshots (fhir_version) WHERE NOT is_active;
