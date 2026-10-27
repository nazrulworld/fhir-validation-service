#!/bin/sh
# Rehearses docs/upgrade-1.0.md against a throwaway 0.1.2 installation:
#   old image + volume -> register an IG and a profile -> record profile validation results -> dump -> restore into
#   the 1.0.0 stack -> compare counts and profile validation results (the profile is in effect after the upgrade).
# Usage: sh scripts/upgrade-rehearsal.sh   (repository root; needs docker, curl, jq; ~10 minutes)
set -eu

OLD_IMAGE="${OLD_IMAGE:-ghcr.io/nazrulworld/fhir-validation-service:0.1.2}"
PROJECT=fvrehearsal
OLD_VOLUME="${PROJECT}_oldpgdata"
OLD_PORT=18990
WORK=$(mktemp -d)
DK_PROFILE="http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient"
# An issue only the dk-core profile raises (base Patient allows no identifier).
DK_RULE="Patient.identifier: minimum required = 1"
COUNT_SQL="SELECT 'igs', count(*) FROM fhir_validator_schema.fhir_implementation_guides UNION ALL SELECT 'profiles', count(*) FROM fhir_validator_schema.fhir_profiles UNION ALL SELECT 'configs', count(*) FROM fhir_validator_schema.fhir_validator_logs ORDER BY 1"

# The 1.0.0 stack reads its settings from $WORK/.env only: compose.yaml's `env_file: .env` would otherwise need a .env
# in the repository root (absent in CI) or load the developer's own. Explicit -f files also skip compose.override.yaml.
printf 'services:\n  validator:\n    env_file: !override\n      - %s/.env\n' "$WORK" > "$WORK/compose.rehearsal.yaml"
DC="docker compose -p $PROJECT --env-file $WORK/.env -f compose.yaml -f $WORK/compose.rehearsal.yaml"

cleanup() {
  docker rm -f "${PROJECT}-old" > /dev/null 2>&1 || true
  $DC down -v > /dev/null 2>&1 || true
  docker volume rm "$OLD_VOLUME" > /dev/null 2>&1 || true
}
fail() { echo "REHEARSAL FAILED: $*" >&2; cleanup; exit 1; }

# Error-level issues of a validation answer ({valid, messages:[{severity, location, message}]}, the JSON shape of
# both versions), one "location | message" per line, sorted. FHIRPath "(@char N)" positions are dropped: the newer
# HL7 core library reports a different character offset in otherwise identical messages.
errors() {
  jq -r '.messages[] | select(.severity == "ERROR" or .severity == "FATAL") | .location + " | " + .message' "$1" \
    | sed 's/ (@char [0-9]*)//g' | sort
}

# Validates the dk Patient and a variant without identifier on $1 ("old" or "new"), declaring the dk profile in
# meta.profile (0.1.2 has no profile parameter); saves the error-level issues to $WORK/<name>.<variant>.errors.
validate_dk() {
  base=$1; name=$2; query=${3:-}
  for variant in complete no-identifier; do
    curl -fs -H 'Content-Type: application/fhir+json' -H 'Accept: application/json' --data-binary @"$WORK/patient-$variant.json" \
      "$base/R4/validate$query" > "$WORK/$name.$variant.json" || fail "validation of the $variant dk Patient on $name"
    errors "$WORK/$name.$variant.json" > "$WORK/$name.$variant.errors"
  done
  grep -qF "$DK_RULE" "$WORK/$name.no-identifier.errors" || fail "dk profile not in effect on $name (no '$DK_RULE')"
  ! grep -qF "$DK_RULE" "$WORK/$name.complete.errors" || fail "complete dk Patient reported '$DK_RULE' on $name"
}
trap cleanup EXIT

echo "==> old image"
if ! docker pull -q "$OLD_IMAGE" > /dev/null 2>&1; then
  echo "    pull failed; building v0.1.2 with the appuser fix (D1)"
  git worktree add -f "$WORK/old" v0.1.2 > /dev/null
  sed -i.bak 's/fhiruser:appuser/fhiruser:fhiruser/' "$WORK/old/Dockerfile"
  docker build -q -t "$OLD_IMAGE" "$WORK/old" > /dev/null
  git worktree remove --force "$WORK/old"
fi

echo "==> start 0.1.2 and register data"
docker volume create "$OLD_VOLUME" > /dev/null
# volume-nocopy: a fresh named volume would otherwise be pre-filled from the image (whose packaged cluster keeps
# its config in /etc), and the 0.1.x entrypoint would then skip initdb and fail to start PostgreSQL.
docker run -d --name "${PROJECT}-old" -p "127.0.0.1:${OLD_PORT}:8880" \
  --mount "type=volume,src=$OLD_VOLUME,dst=/var/lib/postgresql/16/main,volume-nocopy=true" "$OLD_IMAGE" > /dev/null
i=0; until curl -fs "http://127.0.0.1:${OLD_PORT}/health" > /dev/null; do i=$((i+2)); [ $i -ge 240 ] && fail "0.1.2 did not start"; sleep 2; done
curl -fs -F "file=@src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz;type=application/octet-stream" "http://127.0.0.1:${OLD_PORT}/igs/upload" > /dev/null || fail "upload on 0.1.2"
# 0.1.2 can only include IGs whose dependencies are all stored, so include a small dependency-free package.
mkdir -p "$WORK/pkg/package"
printf '%s' '{"name":"example.rehearsal","version":"1.0.0","fhirVersions":["4.0.1"],"dependencies":{"hl7.fhir.r4.core":"4.0.1"}}' > "$WORK/pkg/package/package.json"
cp src/test/resources/fhir/profiles/dk/StructureDefinition-dk-core-patient.json "$WORK/pkg/package/"
# COPYFILE_DISABLE: no macOS ._* metadata entries (0.1.2 tries to parse every .json entry)
COPYFILE_DISABLE=1 tar -czf "$WORK/example.rehearsal-1.0.0.tgz" -C "$WORK/pkg" package
curl -fs -F "file=@$WORK/example.rehearsal-1.0.0.tgz;type=application/octet-stream" "http://127.0.0.1:${OLD_PORT}/igs/upload" > /dev/null || fail "upload of the rehearsal package on 0.1.2"
curl -fs -H 'Content-Type: application/json' --data '{"igPackageId":"example.rehearsal","igPackageVersion":"1.0.0"}' "http://127.0.0.1:${OLD_PORT}/R4/include-ig" > /dev/null || fail "include on 0.1.2"
curl -fs -H 'Content-Type: application/json' --data @src/test/resources/fhir/profiles/dk/StructureDefinition-dk-core-patient.json "http://127.0.0.1:${OLD_PORT}/R4/register-profile" > /dev/null || fail "profile on 0.1.2"
cp src/test/resources/fhir/resources/dk/Patient.json "$WORK/patient-complete.json"
jq 'del(.identifier)' "$WORK/patient-complete.json" > "$WORK/patient-no-identifier.json"
validate_dk "http://127.0.0.1:${OLD_PORT}" old
echo "    0.1.2: dk profile in effect ($(wc -l < "$WORK/old.complete.errors" | tr -d ' ') / $(wc -l < "$WORK/old.no-identifier.errors" | tr -d ' ') error-level issues)"
docker stop -t 30 "${PROJECT}-old" > /dev/null; docker rm "${PROJECT}-old" > /dev/null

# Runs $1 against the old cluster in a throwaway container. PostgreSQL is stopped cleanly afterwards: a server killed
# with the container leaves postmaster.pid behind, and the next pg_ctl -w start then returns while the server is still
# recovering ("the database system is starting up").
old_db() {
  docker run --rm -i --entrypoint bash -v "$OLD_VOLUME":/var/lib/postgresql/16/main "$OLD_IMAGE" -c "
    bin=/usr/lib/postgresql/16/bin; data=/var/lib/postgresql/16/main
    gosu postgres \$bin/pg_ctl -D \$data -w -t 120 -l /tmp/pg.log start >/dev/null || exit 1
    i=0; until gosu postgres \$bin/pg_isready -q; do i=\$((i+1)); [ \$i -ge 120 ] && exit 1; sleep 1; done
    $1; rc=\$?
    gosu postgres \$bin/pg_ctl -D \$data -m fast -w stop >/dev/null
    exit \$rc"
}

echo "==> dump (guide step 2)"
old_db "gosu postgres psql -At -d fhir_validator -c \"$COUNT_SQL\"" > "$WORK/before.txt"
old_db "gosu postgres pg_dump -Fc -d fhir_validator" > "$WORK/fhir_validator.dump"
[ -s "$WORK/fhir_validator.dump" ] || fail "empty dump"
cat "$WORK/before.txt"

echo "==> restore into 1.0.0 (guide steps 3-4)"
cp .env.example "$WORK/.env"
sed -i.bak "s/^PG_PASSWORD=.*/PG_PASSWORD=rehearsal$(openssl rand -hex 8)/; s/^FV_ADMIN_API_KEY=.*/FV_ADMIN_API_KEY=rehearsal$(openssl rand -hex 20)/; s/^FV_HTTP_PORT=.*/FV_HTTP_PORT=18991/" "$WORK/.env"
$DC up -d --wait postgres > /dev/null
$DC exec -T postgres pg_restore -U postgres -d fhir_validator --no-owner --clean --if-exists < "$WORK/fhir_validator.dump" || true
$DC exec -T postgres psql -U postgres -At -d fhir_validator -c "$COUNT_SQL" > "$WORK/after.txt"
diff "$WORK/before.txt" "$WORK/after.txt" || fail "row counts differ after restore"

echo "==> start 1.0.0 (guide step 5)"
$DC up -d --build --wait --wait-timeout 300 > /dev/null || fail "1.0.0 did not become ready"
packages=$(curl -fs "http://127.0.0.1:18991/health/readiness" | jq -r '.versions.R4.packages | join(",")')
echo "    R4 packages after upgrade: $packages"
echo "$packages" | grep -q "example.rehearsal#1.0.0" || fail "included IG not active after upgrade"
validate_dk "http://127.0.0.1:18991" new "?profile=$DK_PROFILE"
jq -e --arg p "$DK_PROFILE" '.appliedProfiles | index($p)' "$WORK/new.complete.json" > /dev/null \
  || fail "appliedProfiles lacks $DK_PROFILE after upgrade"
for variant in complete no-identifier; do
  diff "$WORK/old.$variant.errors" "$WORK/new.$variant.errors" \
    || fail "error-level issues of the $variant dk Patient differ between 0.1.2 and 1.0.0"
done
echo "    1.0.0: dk profile in effect, same error-level issues as 0.1.2"

echo "REHEARSAL OK"
