-- 4.1.0 (spec 008, code review): hashes of revoked well-known keys (the 'system administrator' and 'trial validator'
-- keys installed from FV_ADMIN_API_KEY / FV_VALIDATOR_API_KEY). Startup never installs a value whose hash is listed,
-- so an instance still configured with a leaked, revoked value can't bring it back after the key was rotated.
-- The ids match nzi.fhir.validator.core.Constant (checked by ConstantMigrationConsistencyTest).
CREATE TABLE IF NOT EXISTS fhir_validator_schema.revoked_key_hashes (
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

-- Well-known keys that are already revoked.
INSERT INTO fhir_validator_schema.revoked_key_hashes (key_hash, key_id)
SELECT key_hash, id FROM fhir_validator_schema.api_keys
WHERE revoked_at IS NOT NULL
  AND id IN ('00000000-0000-0000-0000-200000000001', '00000000-0000-0000-0000-200000000002')
ON CONFLICT (key_hash) DO NOTHING;
