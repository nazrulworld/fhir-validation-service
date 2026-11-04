#!/bin/sh
# Export custom profiles registered in a 1.x database as uploadable IG packages, one per FHIR version.
#
# Run this BEFORE upgrading to 2.0.0: the 2.0.0 migration (V6) permanently deletes the fhir_profiles table.
# Read-only against the database. Needs only sh, psql, tar and gzip.
#
# Connection: standard libpq variables (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD or ~/.pgpass). Any that are
# unset fall back to FV_PG_HOST, FV_PG_PORT, FV_PG_DATABASE, FV_PG_USER and FV_PG_PASSWORD. Passwords are never
# accepted as arguments.
#
# Usage:
#   scripts/export-custom-profiles.sh --check
#   scripts/export-custom-profiles.sh [--out DIR] [--name NAME] [--package-version VER] [--dependency NAME#VERSION]...
#
# Exit codes: 0 success (including "no custom profiles"), 1 bad arguments, existing output or a stored package clash,
#             2 database error (partial output removed), 3 fhir_profiles does not exist (already upgraded).
#
# Contract: specs/003-ig-only-profile-resolution/contracts/export-script.md
set -eu

SCHEMA=fhir_validator_schema
OUT=./custom-profiles-export
NAME=local.custom.profiles
PKG_VERSION=1.0.0
DEPENDENCIES=""
CHECK=0

usage() {
  sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' >&2
  exit 1
}

die() {
  echo "export-custom-profiles: $2" >&2
  exit "$1"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK=1 ;;
    --out) [ $# -ge 2 ] || usage; OUT=$2; shift ;;
    --name) [ $# -ge 2 ] || usage; NAME=$2; shift ;;
    --package-version) [ $# -ge 2 ] || usage; PKG_VERSION=$2; shift ;;
    --dependency)
      [ $# -ge 2 ] || usage
      case "$2" in
        *[!A-Za-z0-9._#-]* | *#*#* | \#* | *\#) die 1 "--dependency must be NAME#VERSION, got '$2'" ;;
        *#*) DEPENDENCIES="$DEPENDENCIES $2" ;;
        *) die 1 "--dependency must be NAME#VERSION, got '$2'" ;;
      esac
      shift ;;
    -h | --help) usage ;;
    *) echo "export-custom-profiles: unknown argument '$1'" >&2; usage ;;
  esac
  shift
done

case "$NAME" in *[!a-z0-9._-]* | "") die 1 "--name must use lowercase letters, digits, '.', '_' or '-'" ;; esac
case "$PKG_VERSION" in *[!A-Za-z0-9._-]* | "") die 1 "--package-version must not contain spaces or special characters" ;; esac

: "${PGHOST:=${FV_PG_HOST:-}}"
: "${PGPORT:=${FV_PG_PORT:-}}"
: "${PGDATABASE:=${FV_PG_DATABASE:-}}"
: "${PGUSER:=${FV_PG_USER:-}}"
: "${PGPASSWORD:=${FV_PG_PASSWORD:-}}"
for v in PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD; do
  eval "val=\${$v}"
  # shellcheck disable=SC2154
  if [ -n "$val" ]; then export "${v?}"; else unset "$v"; fi
done

command -v psql > /dev/null 2>&1 || die 2 "psql not found on PATH"

q() {
  psql -X -q -At -v ON_ERROR_STOP=1 -c "$1"
}

exists=$(q "SELECT to_regclass('$SCHEMA.fhir_profiles') IS NOT NULL") || die 2 "database connection or query failed"
if [ "$exists" != "t" ]; then
  die 3 "$SCHEMA.fhir_profiles does not exist: this database was already upgraded to 2.0.0, so there is nothing left to export (restore the pre-upgrade backup to recover profiles)"
fi

counts=$(q "SELECT fhir_version || ' ' || count(*) FROM $SCHEMA.fhir_profiles GROUP BY fhir_version ORDER BY fhir_version") \
  || die 2 "database query failed"
total=$(q "SELECT count(*) FROM $SCHEMA.fhir_profiles") || die 2 "database query failed"

if [ "$CHECK" = 1 ]; then
  [ -n "$counts" ] && printf '%s\n' "$counts"
  echo "total $total"
  exit 0
fi

if [ "$total" = 0 ]; then
  echo "No custom profiles found; nothing to export. You can upgrade without exporting."
  exit 0
fi

# Refuse before writing anything if a package this export would generate is already stored: uploading it would
# replace that package without warning (FR-014).
pairs=""
for fv in $(q "SELECT DISTINCT fhir_version FROM $SCHEMA.fhir_profiles ORDER BY 1") ; do
  case "$fv" in
    3.0.2) rel=stu3 ;; 4.0.1) rel=r4 ;; 4.3.0) rel=r4b ;; 5.0.0) rel=r5 ;;
    *) continue ;;
  esac
  pairs="$pairs${pairs:+, }('$NAME.$rel', '$PKG_VERSION')"
done
if [ -n "$pairs" ]; then
  clashes=$(q "SELECT ig_package_id || '#' || ig_package_version FROM $SCHEMA.fhir_implementation_guides
               WHERE (ig_package_id, ig_package_version) IN ($pairs) ORDER BY 1") || die 2 "database query failed"
  for c in $clashes; do
    echo "export-custom-profiles: Package $c is already stored; uploading the export would replace it." >&2
  done
  [ -z "$clashes" ] || die 1 "Choose another --name or --package-version"
fi

mkdir -p "$OUT" || die 1 "cannot create output directory $OUT"
if ls "$OUT"/*.tgz > /dev/null 2>&1 || [ -e "$OUT/MANIFEST.txt" ]; then
  die 1 "$OUT already contains an export; choose another --out"
fi

WORK=$(mktemp -d)
CREATED=""
cleanup() {
  rm -rf "$WORK"
}
fail_db() {
  for f in $CREATED; do rm -rf "$f"; done
  cleanup
  die 2 "database query failed; partial output removed"
}
trap cleanup EXIT

: > "$OUT/MANIFEST.txt"
CREATED="$OUT/MANIFEST.txt"
skipped=0

for fv in $(q "SELECT DISTINCT fhir_version FROM $SCHEMA.fhir_profiles ORDER BY 1") ; do
  case "$fv" in
    3.0.2) rel=stu3; core=hl7.fhir.r3.core ;;
    4.0.1) rel=r4; core=hl7.fhir.r4.core ;;
    4.3.0) rel=r4b; core=hl7.fhir.r4b.core ;;
    5.0.0) rel=r5; core=hl7.fhir.r5.core ;;
    *)
      # Not a version 2.0.0 can validate: keep the raw documents so nothing is lost, but do not package them.
      raw="$OUT/unsupported-$(printf '%s' "$fv" | tr -c 'A-Za-z0-9.' '_')"
      mkdir -p "$raw"; CREATED="$CREATED $raw"
      n=0
      q "SELECT profile_json::text FROM $SCHEMA.fhir_profiles WHERE fhir_version = '$(printf '%s' "$fv" | sed "s/'/''/g")' ORDER BY url" \
        > "$WORK/raw.jsonl" || fail_db
      while IFS= read -r line; do n=$((n + 1)); printf '%s\n' "$line" > "$raw/StructureDefinition-$n.json"; done < "$WORK/raw.jsonl"
      echo "warning: $n profile(s) stored for unsupported FHIR version '$fv' written unpackaged to $raw" >&2
      skipped=$((skipped + n))
      continue ;;
  esac

  pkgname="$NAME.$rel"
  archive="$OUT/$pkgname-$PKG_VERSION.tgz"
  dir="$WORK/$rel/package"
  mkdir -p "$dir"

  q "SELECT url FROM $SCHEMA.fhir_profiles WHERE fhir_version = '$fv' ORDER BY url" > "$WORK/$rel.urls" || fail_db
  q "SELECT profile_json::text FROM $SCHEMA.fhir_profiles WHERE fhir_version = '$fv' ORDER BY url" > "$WORK/$rel.jsonl" || fail_db

  n=0
  while IFS= read -r line; do
    n=$((n + 1))
    printf '%s\n' "$line" > "$dir/StructureDefinition-$n.json"
  done < "$WORK/$rel.jsonl"

  deps="\"$core\": \"$fv\""
  for d in $DEPENDENCIES; do
    deps="$deps, \"${d%%#*}\": \"${d#*#}\""
  done
  cat > "$dir/package.json" <<JSON
{
  "name": "$pkgname",
  "version": "$PKG_VERSION",
  "type": "fhir.ig",
  "fhirVersions": ["$fv"],
  "dependencies": { $deps }
}
JSON

  COPYFILE_DISABLE=1 tar -czf "$archive" -C "$WORK/$rel" package || die 1 "cannot write $archive"
  CREATED="$CREATED $archive"
  while IFS= read -r url; do printf '%s %s\n' "$(basename "$archive")" "$url" >> "$OUT/MANIFEST.txt"; done < "$WORK/$rel.urls"
  echo "$fv: $n profile(s) -> $archive"
done

echo "Exported $((total - skipped)) of $total profile(s). Manifest: $OUT/MANIFEST.txt"
echo "Next: back up the database, stop every 1.x instance, upgrade, then upload and include each archive (docs/upgrade-2.0.md)."
