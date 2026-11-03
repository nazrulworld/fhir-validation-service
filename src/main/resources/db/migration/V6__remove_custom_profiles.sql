-- 2.0.0 (spec 003): profiles come only from included IG packages. Custom profile registration is removed, so its
-- storage is dropped. This runs unconditionally, even when rows exist (accepted risk, FR-015): operators export them
-- first with scripts/export-custom-profiles.sh against the 1.x database (see docs/upgrade-2.0.md).
DROP TABLE IF EXISTS fhir_validator_schema.fhir_profiles;

-- Never read or written by any release.
ALTER TABLE fhir_validator_schema.fhir_validator_logs DROP COLUMN IF EXISTS included_profiles;
