#!/bin/sh
# Tests scripts/export-custom-profiles.sh against a throwaway PostgreSQL at the 1.2.0 schema (V1..V5).
# Needs: docker, psql, tar, gzip. Run from the repository root.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
SCRIPT="$ROOT/scripts/export-custom-profiles.sh"
MIGRATIONS="$ROOT/src/main/resources/db/migration"
NAME=fv-export-test-$$
PORT=${EXPORT_TEST_PG_PORT:-55439}
WORK=$(mktemp -d)

cleanup() {
  docker rm -f "$NAME" > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

export PGHOST=127.0.0.1 PGPORT=$PORT PGDATABASE=fhir_validator PGUSER=postgres PGPASSWORD=export-test

docker run -d --name "$NAME" -e POSTGRES_PASSWORD=$PGPASSWORD -e POSTGRES_DB=$PGDATABASE -p "$PORT:5432" postgres:16 > /dev/null
i=0
until pg_isready -q -h $PGHOST -p "$PORT"; do
  i=$((i + 1)); [ $i -lt 60 ] || fail "postgres did not start"; sleep 1
done
sleep 1

for v in 1 2 3 4 5; do
  psql -X -q -v ON_ERROR_STOP=1 -f "$(ls "$MIGRATIONS"/V${v}__*.sql)" > /dev/null
done

sd() {
  printf '{"resourceType":"StructureDefinition","url":"%s","fhirVersion":"%s","kind":"resource","type":"Patient"}' "$1" "$2"
}
psql -X -q -v ON_ERROR_STOP=1 > /dev/null <<SQL
INSERT INTO fhir_validator_schema.fhir_profiles (url, profile_json, fhir_version, modified_at) VALUES
 ('http://ex.org/b', '$(sd http://ex.org/b 4.0.1)', '4.0.1', now()),
 ('http://ex.org/a', '$(sd http://ex.org/a 4.0.1)', '4.0.1', now()),
 ('http://ex.org/r5', '$(sd http://ex.org/r5 5.0.0)', '5.0.0', now());
SQL

# --check
out=$("$SCRIPT" --check)
[ "$out" = "$(printf '4.0.1 2\n5.0.0 1\ntotal 3')" ] || fail "--check printed: $out"

# export
"$SCRIPT" --out "$WORK/out" --dependency hl7.fhir.dk.core#3.4.0 > /dev/null
for rel in r4 r5; do
  [ -f "$WORK/out/local.custom.profiles.$rel-1.0.0.tgz" ] || fail "missing $rel archive"
done
mkdir "$WORK/r4" && tar -xzf "$WORK/out/local.custom.profiles.r4-1.0.0.tgz" -C "$WORK/r4"
grep -q '"name": "local.custom.profiles.r4"' "$WORK/r4/package/package.json" || fail "r4 package name"
grep -q '"fhirVersions": \["4.0.1"\]' "$WORK/r4/package/package.json" || fail "r4 fhirVersions"
grep -q '"hl7.fhir.r4.core": "4.0.1"' "$WORK/r4/package/package.json" || fail "r4 core dependency"
grep -q '"hl7.fhir.dk.core": "3.4.0"' "$WORK/r4/package/package.json" || fail "--dependency not written"
[ "$(ls "$WORK/r4/package" | grep -c '^StructureDefinition-')" = 2 ] || fail "r4 should hold 2 profiles"
# Numbered in url order, text equal to the stored document.
stored=$(psql -X -At -c "SELECT profile_json::text FROM fhir_validator_schema.fhir_profiles WHERE url = 'http://ex.org/a'")
[ "$(cat "$WORK/r4/package/StructureDefinition-1.json")" = "$stored" ] || fail "StructureDefinition-1 is not http://ex.org/a verbatim"
[ "$(wc -l < "$WORK/out/MANIFEST.txt" | tr -d ' ')" = 3 ] || fail "MANIFEST should list 3 profiles"

# refuses to overwrite
if "$SCRIPT" --out "$WORK/out" > /dev/null 2>&1; then fail "second export into the same --out must fail"; fi
code=0; "$SCRIPT" --out "$WORK/out" > /dev/null 2>&1 || code=$?
[ "$code" = 1 ] || fail "existing output should exit 1, got $code"

# a generated package that is already stored: refuse before writing anything (FR-014, clarification Q2)
psql -X -q -v ON_ERROR_STOP=1 > /dev/null <<SQL
INSERT INTO fhir_validator_schema.fhir_implementation_guides (ig_package_id, ig_package_version, ig_package_meta, content_raw)
VALUES ('local.custom.profiles.r4', '1.0.0', '{}', '\x00');
SQL
code=0; err=$("$SCRIPT" --out "$WORK/clash" 2>&1 > /dev/null) || code=$?
[ "$code" = 1 ] || fail "an already stored package should exit 1, got $code"
case "$err" in *"local.custom.profiles.r4#1.0.0"*) ;; *) fail "clash message should name the package: $err" ;; esac
case "$err" in *"--package-version"*) ;; *) fail "clash message should suggest --name or --package-version: $err" ;; esac
[ -z "$(ls "$WORK/clash" 2>/dev/null)" ] || fail "nothing must be written when a package is already stored"
"$SCRIPT" --out "$WORK/clash-ok" --package-version 1.0.1 > /dev/null || fail "a different --package-version should succeed"
[ -f "$WORK/clash-ok/local.custom.profiles.r4-1.0.1.tgz" ] || fail "missing r4 archive with --package-version 1.0.1"
psql -X -q -c "DELETE FROM fhir_validator_schema.fhir_implementation_guides" > /dev/null

# profiles stored under an unsupported FHIR version: kept as raw files, not packaged, warned about (FR-014, Q1)
psql -X -q -v ON_ERROR_STOP=1 > /dev/null <<SQL
INSERT INTO fhir_validator_schema.fhir_profiles (url, profile_json, fhir_version, modified_at) VALUES
 ('http://ex.org/r6', '$(sd http://ex.org/r6 6.0.0)', '6.0.0', now());
SQL
code=0; err=$("$SCRIPT" --out "$WORK/unsupported" 2>&1 > /dev/null) || code=$?
[ "$code" = 0 ] || fail "unsupported FHIR versions must not fail the export, got $code: $err"
stored=$(psql -X -At -c "SELECT profile_json::text FROM fhir_validator_schema.fhir_profiles WHERE url = 'http://ex.org/r6'")
[ "$(cat "$WORK/unsupported/unsupported-6.0.0/StructureDefinition-1.json")" = "$stored" ] \
  || fail "unsupported-6.0.0/StructureDefinition-1.json is not the stored document"
! ls "$WORK/unsupported"/*6.0.0*.tgz > /dev/null 2>&1 || fail "no package may be generated for 6.0.0"
[ -f "$WORK/unsupported/local.custom.profiles.r4-1.0.0.tgz" ] || fail "supported versions must still be packaged"
case "$err" in *"1 profile(s)"*"6.0.0"*) ;; *) fail "expected a warning with the count: $err" ;; esac
psql -X -q -c "DELETE FROM fhir_validator_schema.fhir_profiles WHERE fhir_version = '6.0.0'" > /dev/null

# empty table: nothing written, exit 0
psql -X -q -c "DELETE FROM fhir_validator_schema.fhir_profiles" > /dev/null
out=$("$SCRIPT" --out "$WORK/empty")
case "$out" in *"No custom profiles found"*) ;; *) fail "empty table message: $out" ;; esac
[ ! -e "$WORK/empty/MANIFEST.txt" ] || fail "nothing must be written for an empty table"

# after V6 it is too late: exit 3
psql -X -q -v ON_ERROR_STOP=1 -f "$(ls "$MIGRATIONS"/V6__*.sql)" > /dev/null
code=0; "$SCRIPT" --check > /dev/null 2>&1 || code=$?
[ "$code" = 3 ] || fail "after V6 the script should exit 3, got $code"

echo "export-custom-profiles: all checks passed"
