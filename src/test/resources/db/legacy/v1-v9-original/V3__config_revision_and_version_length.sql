-- 1.0.0: longer package versions, package provenance, validator configuration revisions.

-- FR-024 / B8: pre-release version labels can exceed 16 characters.
ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ALTER COLUMN ig_package_version TYPE VARCHAR(64);

ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ADD COLUMN IF NOT EXISTS content_sha256 BYTEA NULL,
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NULL;

ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ADD CONSTRAINT fhir_implementation_guides_source_chk
        CHECK (source IN ('UPLOAD', 'REGISTRY', 'URL', 'UNKNOWN'));

-- FR-002 / FR-005: revision counter for cross-instance propagation and the persisted resolved package set.
ALTER TABLE fhir_validator_schema.fhir_validator_logs
    ADD COLUMN IF NOT EXISTS resolved_ig_packages TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1;

UPDATE fhir_validator_schema.fhir_validator_logs
SET resolved_ig_packages = included_ig_packages;

INSERT INTO fhir_validator_schema.fhir_validator_logs (validator_id, fhir_version)
VALUES ('standard_validator', 'STU3'),
       ('standard_validator', 'R4'),
       ('standard_validator', 'R4B'),
       ('standard_validator', 'R5')
ON CONFLICT (validator_id, fhir_version) DO NOTHING;

-- Every profile row carries a change timestamp so the poll fallback never misses new profiles.
UPDATE fhir_validator_schema.fhir_profiles
SET modified_at = COALESCE(modified_at, created_at, now());

ALTER TABLE fhir_validator_schema.fhir_profiles
    ALTER COLUMN modified_at SET DEFAULT now();

-- Earlier releases bound JSON text to jsonb parameters, which stored documents as JSON *string* values.
-- Unwrap them so profiles and package metadata are real JSON objects.
UPDATE fhir_validator_schema.fhir_profiles
SET profile_json = (profile_json #>> '{}')::jsonb
WHERE jsonb_typeof(profile_json) = 'string';

UPDATE fhir_validator_schema.fhir_implementation_guides
SET ig_package_meta = (ig_package_meta #>> '{}')::jsonb
WHERE jsonb_typeof(ig_package_meta) = 'string';
