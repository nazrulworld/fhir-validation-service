-- Spec 012 (data-model §3): validator contexts built from two IG packages.
-- A two-IG context's key is "A#a|B#b" (include order). It is stored where single keys are stored
-- (fhir_validator_logs.included_ig_packages, fhir_validator_snapshots.ig_package); aliases are derived in memory and
-- never stored (research R3).

-- The validate `ig` parameter allows 256 characters, so a key may be that long. Raising a VARCHAR limit only changes
-- the catalog (no table rewrite, no index rebuild).
ALTER TABLE fhir_validator_schema.fhir_validator_snapshots ALTER COLUMN ig_package TYPE VARCHAR(256);
ALTER TABLE fhir_validator_schema.validation_usage ALTER COLUMN ig TYPE VARCHAR(256);

-- FR-012: one two-IG context per first IG and FHIR version, so the first-IG alias is never ambiguous. Defence in
-- depth: the include transaction checks it first under the configuration row lock. Single-IG rows (no '|') don't
-- match the predicate.
CREATE UNIQUE INDEX fhir_validator_snapshots_first_member_uq
    ON fhir_validator_schema.fhir_validator_snapshots (fhir_version, split_part(ig_package, '|', 1))
    WHERE strpos(ig_package, '|') > 0;

-- FR-008: the same two IG versions are included at most once per FHIR version, in either order.
CREATE UNIQUE INDEX fhir_validator_snapshots_pair_uq
    ON fhir_validator_schema.fhir_validator_snapshots (
        fhir_version,
        LEAST(split_part(ig_package, '|', 1), split_part(ig_package, '|', 2)),
        GREATEST(split_part(ig_package, '|', 1), split_part(ig_package, '|', 2)))
    WHERE strpos(ig_package, '|') > 0;
