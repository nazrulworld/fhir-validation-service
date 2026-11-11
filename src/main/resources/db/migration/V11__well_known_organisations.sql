-- 4.1.0 (spec 008, research R13): re-keys the system organisation to its well-known id, seeds the trial organisation
-- and gives the bootstrap admin key its well-known id and name. The values match nzi.fhir.validator.core.Constant
-- (checked by ConstantMigrationConsistencyTest).
--
-- NOT rolling-upgrade safe: 4.0 instances hard-code the old system id (00000000-0000-0000-0000-000000000001) in code
-- and in column defaults. Stop every 4.0 instance before starting 4.1.0 (docs/upgrade-4.1.md).

-- 1. The constraint that pins the system organisation to the old id.
ALTER TABLE fhir_validator_schema.organisations DROP CONSTRAINT organisations_system_id_chk;

-- 2. The owner foreign keys created by V8 (PostgreSQL default names). No IF EXISTS: a differently named key must fail
--    the migration loudly instead of leaving an owner reference behind.
ALTER TABLE fhir_validator_schema.api_keys DROP CONSTRAINT api_keys_owner_fkey;
ALTER TABLE fhir_validator_schema.fhir_implementation_guides DROP CONSTRAINT fhir_implementation_guides_owner_fkey;
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots DROP CONSTRAINT fhir_validator_snapshots_owner_fkey;

-- 3. The organisation and 4. every owner reference.
UPDATE fhir_validator_schema.organisations
SET id = '00000000-0000-0000-0000-100000000001'
WHERE id = '00000000-0000-0000-0000-000000000001';

UPDATE fhir_validator_schema.api_keys
SET owner = '00000000-0000-0000-0000-100000000001'
WHERE owner = '00000000-0000-0000-0000-000000000001';

UPDATE fhir_validator_schema.fhir_implementation_guides
SET owner = '00000000-0000-0000-0000-100000000001'
WHERE owner = '00000000-0000-0000-0000-000000000001';

UPDATE fhir_validator_schema.fhir_validator_snapshots
SET owner = '00000000-0000-0000-0000-100000000001'
WHERE owner = '00000000-0000-0000-0000-000000000001';

-- 5. The foreign keys again, exactly as V8 created them.
ALTER TABLE fhir_validator_schema.api_keys
    ADD CONSTRAINT api_keys_owner_fkey FOREIGN KEY (owner)
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT;
ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ADD CONSTRAINT fhir_implementation_guides_owner_fkey FOREIGN KEY (owner)
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT;
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ADD CONSTRAINT fhir_validator_snapshots_owner_fkey FOREIGN KEY (owner)
        REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT;

-- 6. New rows without an explicit owner belong to the system organisation under its new id.
ALTER TABLE fhir_validator_schema.api_keys
    ALTER COLUMN owner SET DEFAULT '00000000-0000-0000-0000-100000000001';
ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ALTER COLUMN owner SET DEFAULT '00000000-0000-0000-0000-100000000001';
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots
    ALTER COLUMN owner SET DEFAULT '00000000-0000-0000-0000-100000000001';

-- 7. Exactly the well-known id is the system organisation.
ALTER TABLE fhir_validator_schema.organisations
    ADD CONSTRAINT organisations_system_id_chk
        CHECK ((type = 'SYSTEM') = (id = '00000000-0000-0000-0000-100000000001'));

-- 8. The well-known organisations. ON CONFLICT DO NOTHING without a target also skips a clashing short name: an
--    existing organisation named 'trial' is left alone, and the startup check reports the clash.
INSERT INTO fhir_validator_schema.organisations (id, name_short, name_long, type, is_active)
VALUES ('00000000-0000-0000-0000-100000000001', 'system', 'FHIR Validation Service', 'SYSTEM', true)
ON CONFLICT DO NOTHING;
INSERT INTO fhir_validator_schema.organisations (id, name_short, name_long, type, is_active)
VALUES ('00000000-0000-0000-0000-100000000002', 'trial', 'Free trial validation service', 'NON_COMMERCIAL', true)
ON CONFLICT DO NOTHING;

-- 9. The bootstrap admin key (FV_ADMIN_API_KEY) gets its well-known id and name, unless either is already taken.
UPDATE fhir_validator_schema.api_keys
SET id   = '00000000-0000-0000-0000-200000000001',
    name = 'system administrator'
WHERE name = 'bootstrap'
  AND NOT EXISTS (SELECT 1
                  FROM fhir_validator_schema.api_keys k
                  WHERE k.name = 'system administrator'
                     OR k.id = '00000000-0000-0000-0000-200000000001');
