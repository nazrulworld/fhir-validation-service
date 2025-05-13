-- FHIR Profiles Table
CREATE TABLE IF NOT EXISTS fhir_profiles (
    id SERIAL PRIMARY KEY,
    url TEXT NOT NULL UNIQUE,
    profile_json JSONB NOT NULL,
    fhir_version TEXT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Implementation Guides Table
CREATE TABLE IF NOT EXISTS fhir_implementation_guides (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    version TEXT NOT NULL,
    package_id TEXT NOT NULL,
    ig_bytea BYTEA NOT NULL,
    dependencies JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(name, version)
);

-- Create update trigger function
CREATE OR REPLACE FUNCTION update_timestamp()
RETURNS TRIGGER AS $$
BEGIN
   NEW.updated_at = NOW();
RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Add triggers to tables
CREATE TRIGGER update_profile_timestamp
    BEFORE UPDATE ON fhir_profiles
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();

CREATE TRIGGER update_ig_timestamp
    BEFORE UPDATE ON fhir_implementation_guides
    FOR EACH ROW EXECUTE FUNCTION update_timestamp();
