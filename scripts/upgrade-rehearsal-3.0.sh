#!/bin/sh
# Rehearses docs/upgrade-3.0.md against a throwaway 2.0.0 installation:
#   2.0.0 + dk-core included -> record validation results with ?profile=dk-core-patient -> back up -> stop every 2.x
#   instance -> start 3.x on the same database (V7) -> dk-core is a pinned context -> the same results with
#   ?ig=hl7.fhir.dk.core#3.4.0 -> without ig the profile is unknown (core only) -> rollback by restoring the backup.
# Usage: sh scripts/upgrade-rehearsal-3.0.sh   (repository root; needs docker, curl, jq, psql, openssl; ~10 minutes)
set -eu

OLD_TAG="${OLD_TAG:-2.0.0}"
NEW_TAG="${NEW_TAG:-3-rehearsal}"
PROJECT=fvrehearsal3
HTTP_PORT=18993
PG_PORT=55441
WORK=$(mktemp -d)
IG="hl7.fhir.dk.core#3.4.0"
IG_QUERY="hl7.fhir.dk.core%233.4.0"
PROFILE="http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient"
COUNT_SQL="SELECT 'igs', count(*) FROM fhir_validator_schema.fhir_implementation_guides UNION ALL SELECT 'configs', count(*) FROM fhir_validator_schema.fhir_validator_logs UNION ALL SELECT 'keys', count(*) FROM fhir_validator_schema.api_keys ORDER BY 1"
INCLUDED_SQL="SELECT revision || '|' || included_ig_packages::text FROM fhir_validator_schema.fhir_validator_logs WHERE fhir_version = 'R4'"
BASE="http://127.0.0.1:${HTTP_PORT}"

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

# Validates the complete and the identifier-less Patient with ?profile=$PROFILE (and the query suffix $2, if any);
# saves error-level issues per variant.
validate_dk() {
  name=$1
  suffix=${2:-}
  for variant in complete no-identifier; do
    curl -fs -H 'Content-Type: application/fhir+json' -H 'Accept: application/json' --data-binary @"$WORK/patient-$variant.json" \
      "$BASE/R4/validate?profile=$PROFILE$suffix" > "$WORK/$name.$variant.json" || fail "validation of the $variant Patient on $name"
    errors "$WORK/$name.$variant.json" > "$WORK/$name.$variant.errors"
  done
  jq -e --arg p "$PROFILE" '.appliedProfiles | index($p) != null' "$WORK/$name.complete.json" > /dev/null \
    || fail "appliedProfiles on $name lacks $PROFILE: $(jq -c .appliedProfiles "$WORK/$name.complete.json")"
}

admin() { curl -fs -H "Authorization: Bearer $ADMIN_KEY" "$@"; }

cp .env.example "$WORK/.env"
PG_PASSWORD="rehearsal$(openssl rand -hex 8)"
ADMIN_KEY="rehearsal$(openssl rand -hex 20)"
sed -i.bak "s/^PG_PASSWORD=.*/PG_PASSWORD=$PG_PASSWORD/; s/^FV_ADMIN_API_KEY=.*/FV_ADMIN_API_KEY=$ADMIN_KEY/; s/^FV_HTTP_PORT=.*/FV_HTTP_PORT=$HTTP_PORT/" "$WORK/.env"
export PGHOST=127.0.0.1 PGPORT=$PG_PORT PGDATABASE=fhir_validator PGUSER=postgres PGPASSWORD=$PG_PASSWORD

cp src/test/resources/fhir/resources/dk/Patient.json "$WORK/patient-complete.json"
jq 'del(.identifier)' "$WORK/patient-complete.json" > "$WORK/patient-no-identifier.json"

echo "==> start $OLD_TAG and include dk-core"
FV_TAG=$OLD_TAG $DC pull -q validator > /dev/null || fail "cannot pull the $OLD_TAG image"
FV_TAG=$OLD_TAG $DC up -d --no-build --wait --wait-timeout 300 > /dev/null || fail "$OLD_TAG did not become ready"
admin -F "file=@src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz;type=application/gzip" "$BASE/igs/upload" > /dev/null || fail "upload on $OLD_TAG"
admin -H 'Content-Type: application/json' --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' "$BASE/R4/include-ig" > /dev/null \
  || fail "include on $OLD_TAG"
validate_dk old
psql -X -At -c "$COUNT_SQL" > "$WORK/before.txt" || fail "count query on $OLD_TAG"
psql -X -At -c "$INCLUDED_SQL" > "$WORK/included-before.txt" || fail "configuration query on $OLD_TAG"

echo "==> guide step 1: back up"
$DC exec -T postgres pg_dump -Fc -U postgres -d fhir_validator > "$WORK/backup.dump"
[ -s "$WORK/backup.dump" ] || fail "empty backup"

echo "==> guide step 2: stop every 2.x instance"
$DC stop -t 30 validator > /dev/null && $DC rm -f validator > /dev/null

echo "==> guide step 3: start the new version on the same database"
FV_TAG=$NEW_TAG $DC up -d --build --wait --wait-timeout 300 validator > /dev/null || fail "new version did not become ready"
psql -X -At -c "$COUNT_SQL" > "$WORK/after.txt"
diff "$WORK/before.txt" "$WORK/after.txt" || fail "IG, configuration or API key counts changed"
psql -X -At -c "$INCLUDED_SQL" > "$WORK/included-after.txt"
diff "$WORK/included-before.txt" "$WORK/included-after.txt" || fail "the included IG set or its revision changed"
[ "$(psql -X -At -c "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fhir_validator_schema' AND table_name = 'fhir_validator_logs' AND column_name = 'resolved_ig_packages'")" = 0 ] \
  || fail "V7 did not drop resolved_ig_packages"

echo "==> guide step 4: verify"
curl -fs "$BASE/health" | jq -e --arg k "$IG" '.readiness.versions.R4.contexts[] | select(.key == $k and .kind == "pinned" and .state == "ACTIVE")' > /dev/null \
  || fail "$IG is not an ACTIVE pinned context: $(curl -fs "$BASE/health" | jq -c .readiness.versions.R4)"
validate_dk new "&ig=$IG_QUERY"
for variant in complete no-identifier; do
  diff "$WORK/old.$variant.errors" "$WORK/new.$variant.errors" \
    || fail "error-level issues of the $variant Patient differ between $OLD_TAG and the new version with ig"
done
context=$(curl -fs -D - -o /dev/null -H 'Content-Type: application/fhir+json' --data-binary @"$WORK/patient-complete.json" \
  "$BASE/R4/validate?ig=$IG_QUERY" | tr -d '\r' | awk -F': ' 'tolower($1) == "x-validation-context" {print $2}')
[ "$context" = "$IG" ] || fail "X-Validation-Context is '$context', expected $IG"
code=$(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/fhir+json' --data-binary @"$WORK/patient-complete.json" \
  "$BASE/R4/validate?profile=$PROFILE")
[ "$code" = 400 ] || fail "without ig the dk-core profile should be unknown (core only), got $code"
echo "    dk-core is a pinned context, same error-level issues with ig, core only without it"

echo "==> guide step 6: rollback by restoring the backup"
$DC stop -t 30 validator > /dev/null && $DC rm -f validator > /dev/null
$DC exec -T postgres dropdb -U postgres --force fhir_validator
$DC exec -T postgres createdb -U postgres fhir_validator
$DC exec -T postgres pg_restore -U postgres -d fhir_validator --no-owner < "$WORK/backup.dump" || fail "restore failed"
FV_TAG=$OLD_TAG $DC up -d --no-build --wait --wait-timeout 300 validator > /dev/null || fail "$OLD_TAG did not start on the restored backup"
validate_dk rollback
echo "    $OLD_TAG serves dk-core without ig again after restoring the backup"

echo "REHEARSAL OK"
