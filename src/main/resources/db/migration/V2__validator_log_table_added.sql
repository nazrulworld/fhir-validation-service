-- Create Validators Log Table
CREATE TABLE IF NOT EXISTS fhir_validator_schema.fhir_validator_logs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    validator_id VARCHAR(36) NOT NULL,
    fhir_version VARCHAR(16) NOT NULL,
    included_ig_packages TEXT[] NOT NULL DEFAULT '{}',
    included_profiles TEXT[] NOT NULL DEFAULT '{}',
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    UNIQUE (validator_id, fhir_version)
);

CREATE INDEX IF NOT EXISTS fhir_validator_logs_validator_is_active_idx ON fhir_validator_schema.fhir_validator_logs (is_active);