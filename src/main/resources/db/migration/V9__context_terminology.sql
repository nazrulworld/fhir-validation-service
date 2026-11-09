-- 4.0.0 (spec 007, terminology routing): a terminology setting per included validator context, and the last good
-- server list of the official HL7 terminology registry.
--
-- The setting lives on fhir_validator_snapshots, which already holds one row per included context (research R12).
-- Rolling upgrade: both columns have defaults (mode NONE, no server), so 3.x instances, which don't know them, keep
-- inserting valid rows, and every existing context reads as mode NONE (FR-005).
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ADD COLUMN IF NOT EXISTS tx_mode   VARCHAR(16)  NOT NULL DEFAULT 'NONE'
        CHECK (tx_mode IN ('NONE', 'SERVER', 'ECOSYSTEM')),
    ADD COLUMN IF NOT EXISTS tx_server VARCHAR(512) NULL;

ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ADD CONSTRAINT fhir_validator_snapshots_tx_chk CHECK (
        (tx_mode = 'NONE' AND tx_server IS NULL) OR
        (tx_mode = 'SERVER' AND tx_server IS NOT NULL) OR
        (tx_mode = 'ECOSYSTEM'));

-- The last good server list per FHIR version (research R10): read at start, so a restart while the registry is
-- unreachable still has a list. Each instance fetches on its own; a row is only replaced by a newer fetch.
CREATE TABLE IF NOT EXISTS fhir_validator_schema.tx_registry_snapshot (
    fhir_version         VARCHAR(16) PRIMARY KEY,
    fetched_at           TIMESTAMPTZ NOT NULL,
    registry_last_update TIMESTAMPTZ NULL,
    body                 JSONB       NOT NULL -- the kept entries (data-model §2.1), not the raw registry answer
);
