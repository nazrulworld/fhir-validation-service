#!/bin/sh
# Rehearses docs/upgrade-2.0.md against a throwaway 1.2.0 installation:
#   1.2.0 + dk-core included + a registered custom profile -> record validation results -> export the custom profiles
#   -> back up -> stop every 1.x instance -> start 2.x on the same database (V6 drops fhir_profiles) -> upload and
#   include the exported package -> same results -> the export is refused (too late) -> rollback by restoring the backup.
# Usage: sh scripts/upgrade-rehearsal-2.0.sh   (repository root; needs docker, curl, jq, psql, openssl; ~10 minutes)
set -eu

OLD_TAG="${OLD_TAG:-1.2.0}"
NEW_TAG="${NEW_TAG:-2-rehearsal}"
PROJECT=fvrehearsal2
HTTP_PORT=18992
PG_PORT=55440
WORK=$(mktemp -d)
# A custom profile no IG defines: only the exported package can make it resolve after the upgrade.
CUSTOM="http://example.org/rehearsal/StructureDefinition/rehearsal-dk-patient"
# An issue only this profile raises (base Patient allows no identifier).
RULE="Patient.identifier: minimum required = 1"
COUNT_SQL="SELECT 'igs', count(*) FROM fhir_validator_schema.fhir_implementation_guides UNION ALL SELECT 'configs', count(*) FROM fhir_validator_schema.fhir_validator_logs UNION ALL SELECT 'keys', count(*) FROM fhir_validator_schema.api_keys ORDER BY 1"
BASE="http://127.0.0.1:${HTTP_PORT}"

# Settings come from $WORK/.env only; the override also publishes PostgreSQL on loopback for psql and the export.
cat > "$WORK/compose.rehearsal.yaml" <<YAML
services:
  validator:
    env_file: !override
      - $WORK/.env
  postgres:
    ports: ["127.0.0.1:${PG_PORT}:5432"]
YAML
DC="docker compose -p $PROJECT --env-file $WORK/.env -f compose.yaml -f $WORK/compose.rehearsal.yaml"

cleanup() {
  $DC down -v > /dev/null 2>&1 || true
  rm -rf "$WORK"
}
fail() { echo "REHEARSAL FAILED: $*" >&2; $DC logs --no-color --tail=80 validator >&2 || true; exit 1; }
trap cleanup EXIT

errors() {
  jq -r '.messages[] | select(.severity == "ERROR" or .severity == "FATAL") | .location + " | " + .message' "$1" \
    | sed 's/ (@char [0-9]*)//g' | sort
}

# Validates the complete and the identifier-less Patient with ?profile=$CUSTOM; saves error-level issues per variant.
validate_custom() {
  name=$1
  for variant in complete no-identifier; do
    curl -fs -H 'Content-Type: application/fhir+json' -H 'Accept: application/json' --data-binary @"$WORK/patient-$variant.json" \
      "$BASE/R4/validate?profile=$CUSTOM" > "$WORK/$name.$variant.json" || fail "validation of the $variant Patient on $name"
    errors "$WORK/$name.$variant.json" > "$WORK/$name.$variant.errors"
  done
  grep -qF "$RULE" "$WORK/$name.no-identifier.errors" || fail "custom profile not in effect on $name (no '$RULE')"
  # The fixture also declares dk-core-patient in meta.profile, which resolves from the included dk-core and is listed
  # too (1.2.0 lists it first, 2.0 lists requested profiles first), so check membership, not the whole list.
  jq -e --arg p "$CUSTOM" '.appliedProfiles | index($p) != null' "$WORK/$name.complete.json" > /dev/null \
    || fail "appliedProfiles on $name lacks $CUSTOM: $(jq -c .appliedProfiles "$WORK/$name.complete.json")"
}

admin() { curl -fs -H "Authorization: Bearer $ADMIN_KEY" "$@"; }

cp .env.example "$WORK/.env"
PG_PASSWORD="rehearsal$(openssl rand -hex 8)"
ADMIN_KEY="rehearsal$(openssl rand -hex 20)"
sed -i.bak "s/^PG_PASSWORD=.*/PG_PASSWORD=$PG_PASSWORD/; s/^FV_ADMIN_API_KEY=.*/FV_ADMIN_API_KEY=$ADMIN_KEY/; s/^FV_HTTP_PORT=.*/FV_HTTP_PORT=$HTTP_PORT/" "$WORK/.env"
export PGHOST=127.0.0.1 PGPORT=$PG_PORT PGDATABASE=fhir_validator PGUSER=postgres PGPASSWORD=$PG_PASSWORD

cp src/test/resources/fhir/resources/dk/Patient.json "$WORK/patient-complete.json"
jq 'del(.identifier)' "$WORK/patient-complete.json" > "$WORK/patient-no-identifier.json"
jq --arg u "$CUSTOM" '.url = $u | .id = "rehearsal-dk-patient" | .name = "RehearsalDkPatient" | del(.version)' \
  src/test/resources/fhir/profiles/dk/StructureDefinition-dk-core-patient.json > "$WORK/custom-profile.json"

echo "==> start $OLD_TAG, include dk-core, register the custom profile"
FV_TAG=$OLD_TAG $DC pull -q validator > /dev/null || fail "cannot pull the $OLD_TAG image"
FV_TAG=$OLD_TAG $DC up -d --no-build --wait --wait-timeout 300 > /dev/null || fail "$OLD_TAG did not become ready"
admin -F "file=@src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz;type=application/gzip" "$BASE/igs/upload" > /dev/null || fail "upload on $OLD_TAG"
admin -H 'Content-Type: application/json' --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' "$BASE/R4/include-ig" > /dev/null \
  || fail "include on $OLD_TAG"
admin -H 'Content-Type: application/json' --data @"$WORK/custom-profile.json" "$BASE/R4/register-profile" > /dev/null \
  || fail "register-profile on $OLD_TAG"
validate_custom old
psql -X -At -c "$COUNT_SQL" > "$WORK/before.txt" || fail "count query on $OLD_TAG"

echo "==> guide step 1-2: check and export"
[ "$(scripts/export-custom-profiles.sh --check | tail -n 1)" = "total 1" ] || fail "--check did not report total 1"
scripts/export-custom-profiles.sh --out "$WORK/export" --dependency hl7.fhir.dk.core#3.4.0 || fail "export failed"
ARCHIVE="$WORK/export/local.custom.profiles.r4-1.0.0.tgz"
[ -s "$ARCHIVE" ] || fail "no exported R4 package"

echo "==> guide step 3: back up"
$DC exec -T postgres pg_dump -Fc -U postgres -d fhir_validator > "$WORK/backup.dump"
[ -s "$WORK/backup.dump" ] || fail "empty backup"

echo "==> guide step 4: stop every 1.x instance"
$DC stop -t 30 validator > /dev/null && $DC rm -f validator > /dev/null

echo "==> guide step 5: start the new version on the same database"
FV_TAG=$NEW_TAG $DC up -d --build --wait --wait-timeout 300 validator > /dev/null || fail "new version did not become ready"
[ "$(psql -X -At -c "SELECT to_regclass('fhir_validator_schema.fhir_profiles') IS NULL")" = t ] || fail "fhir_profiles still exists"
psql -X -At -c "$COUNT_SQL" > "$WORK/after.txt"
diff "$WORK/before.txt" "$WORK/after.txt" || fail "IG, configuration or API key counts changed"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/fhir+json' --data-binary @"$WORK/patient-complete.json" \
  "$BASE/R4/validate?profile=$CUSTOM")
[ "$code" = 400 ] || fail "the custom profile should be unknown before the exported package is included (got $code)"

echo "==> guide step 6-7: upload and include the exported package, verify"
admin -F "file=@$ARCHIVE;type=application/gzip" "$BASE/igs/upload" > /dev/null || fail "upload of the exported package"
admin -H 'Content-Type: application/json' --data '{"igPackageId":"local.custom.profiles.r4","igPackageVersion":"1.0.0"}' \
  "$BASE/R4/include-ig" > /dev/null || fail "include of the exported package"
validate_custom new
for variant in complete no-identifier; do
  diff "$WORK/old.$variant.errors" "$WORK/new.$variant.errors" \
    || fail "error-level issues of the $variant Patient differ between $OLD_TAG and the new version"
done
echo "    custom profile in effect again, same error-level issues as $OLD_TAG"

code=0; scripts/export-custom-profiles.sh --check > /dev/null 2>&1 || code=$?
[ "$code" = 3 ] || fail "export after the upgrade should exit 3, got $code"

echo "==> guide step 8: rollback by restoring the backup"
$DC stop -t 30 validator > /dev/null && $DC rm -f validator > /dev/null
$DC exec -T postgres dropdb -U postgres --force fhir_validator
$DC exec -T postgres createdb -U postgres fhir_validator
$DC exec -T postgres pg_restore -U postgres -d fhir_validator --no-owner < "$WORK/backup.dump" || fail "restore failed"
FV_TAG=$OLD_TAG $DC up -d --no-build --wait --wait-timeout 300 validator > /dev/null || fail "$OLD_TAG did not start on the restored backup"
validate_custom rollback
echo "    $OLD_TAG serves the custom profile again after restoring the backup"

echo "REHEARSAL OK"
