-- Spec 010 FR-010 (data-model §7): the terminology setting a validation used, recorded with its usage row.
--   tx_mode   none, server or ecosystem
--   tx_server the normalised server URL (mode server; or the default server named for mode ecosystem), else NULL
--   tx_source request (tx/txMode given) or context (the context's default)
-- All nullable with no default: rows written before 1.1.0, and requests refused before validation, keep NULL.
-- Adding nullable columns without a default only changes the catalog (no table rewrite).
ALTER TABLE fhir_validator_schema.validation_usage ADD COLUMN tx_mode VARCHAR(16) NULL;
ALTER TABLE fhir_validator_schema.validation_usage ADD COLUMN tx_server VARCHAR(512) NULL;
ALTER TABLE fhir_validator_schema.validation_usage ADD COLUMN tx_source VARCHAR(8) NULL;
