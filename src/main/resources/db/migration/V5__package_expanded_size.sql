-- Expanded size of each stored package archive, so an include can be refused before it is committed when the
-- validator build would not fit in the heap. NULL for packages stored before this migration (estimated from the
-- archive size instead).
ALTER TABLE fhir_validator_schema.fhir_implementation_guides
    ADD COLUMN IF NOT EXISTS expanded_bytes BIGINT NULL;
