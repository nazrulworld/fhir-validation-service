-- Create an enum type if not exists
DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'fhir_version_enum') THEN
        CREATE TYPE fhir_version_enum AS ENUM ('DSTU2','STU3', 'R4', 'R4B', 'R5', 'R6');
    END IF;
END
$$;

-- Create fhir_profiles table
CREATE TABLE IF NOT EXISTS fhir_profiles (
     id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
     url TEXT NOT NULL,
     profile_json JSONB NOT NULL,
     fhir_version VARCHAR(16) NOT NULL,
     created_at TIMESTAMPTZ DEFAULT NOW(),
     modified_at TIMESTAMPTZ NULL,
    UNIQUE (url, fhir_version)
);

-- Create fhir_implementation_guides table
CREATE TABLE IF NOT EXISTS fhir_implementation_guides (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ig_package_id VARCHAR(127) NOT NULL,
    ig_package_version VARCHAR(16) NOT NULL,
    ig_package_meta JSONB NOT NULL,
-- Meta: {'version': , 'fhirVersion', 'url', name}
    content_raw BYTEA NOT NULL,
    dependencies TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (ig_package_id, ig_package_version)
);

-- Create a GIN index for ig_package_meta
CREATE INDEX IF NOT EXISTS idx_fhir_implementation_guides_package_meta 
ON fhir_implementation_guides USING GIN (ig_package_meta);

-- Create a GIN index for dependencies array
CREATE INDEX IF NOT EXISTS idx_fhir_implementation_guides_dependencies 
ON fhir_implementation_guides USING GIN (dependencies);
