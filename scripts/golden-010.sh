#!/bin/sh
# Spec 010 (T003): records the 1.0.0 results of the differential corpus (spec SC-004) as golden files.
#
# Builds tag v1.0.0 in a temporary git worktree, starts it against a throw-away PostgreSQL container, stores every
# locked test package (src/test/resources/ig/packages/010-packages.lock) plus the duplicate-canonical test package,
# includes the three R4 contexts, validates every corpus file in its context with Accept: application/fhir+json, and
# writes src/test/resources/corpus/010/golden/<group>/<context>/<file>.json. Each golden file keeps, per issue and in
# order, only severity, code, expression, location, diagnostics and details.text (the OperationOutcome's id, meta and
# narrative are ignored), the same projection as test/testsupport/GoldenOutcomes.java.
#
# usage: scripts/golden-010.sh        (needs git, docker, curl, jq, JDK 17; run scripts/fetch-test-packages.sh first)
set -eu

REPO=$(cd "$(dirname "$0")/.." && pwd)
CORPUS="$REPO/src/test/resources/corpus/010"
GOLDEN="$CORPUS/golden"
LOCK="$REPO/src/test/resources/ig/packages/010-packages.lock"
PKGS=${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}
TAG=${GOLDEN_TAG:-v1.0.0}
PORT=${GOLDEN_HTTP_PORT:-18880}
PG_PORT=${GOLDEN_PG_PORT:-15432}
ADMIN_KEY=golden-admin-key-0123456789abcdefghijklmnop
WORK=$(mktemp -d)
PG=fv-golden-pg-$$
JAR_PID=

cleanup() {
    [ -n "$JAR_PID" ] && kill "$JAR_PID" 2>/dev/null && wait "$JAR_PID" 2>/dev/null || true
    docker rm -f "$PG" >/dev/null 2>&1 || true
    git -C "$REPO" worktree remove --force "$WORK/src" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
# Ctrl-C or TERM: stop (the EXIT trap then cleans up) instead of carrying on against a stopped service.
trap 'exit 130' INT TERM

fail() {
    echo "golden-010: $*" >&2
    [ -f "$WORK/service.log" ] && tail -n 80 "$WORK/service.log" >&2
    exit 1
}

for id in $(grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$' | cut -d' ' -f1); do
    [ -f "$PKGS/$id.tgz" ] || fail "$PKGS/$id.tgz is missing: run scripts/fetch-test-packages.sh"
done

echo "==> building $TAG"
git -C "$REPO" worktree add --detach "$WORK/src" "$TAG" >/dev/null
(cd "$WORK/src" && ./mvnw -q -B -DskipTests package) || fail "build of $TAG failed"
JAR=$(ls "$WORK"/src/target/fhir-validation-service-*.jar | grep -v -e sources -e original | head -n 1)

echo "==> starting PostgreSQL"
docker run -d --rm --name "$PG" -e POSTGRES_PASSWORD=golden -p "127.0.0.1:$PG_PORT:5432" postgres:16 >/dev/null
i=0
until docker exec "$PG" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; do
    i=$((i + 1)); [ "$i" -gt 60 ] && fail "PostgreSQL did not start"; sleep 1
done

echo "==> starting $TAG"
FV_HTTP_PORT=$PORT FV_PG_HOST=127.0.0.1 FV_PG_PORT=$PG_PORT FV_PG_DATABASE=postgres FV_PG_USER=postgres \
FV_PG_PASSWORD=golden FV_ADMIN_API_KEY=$ADMIN_KEY FV_ENABLED_FHIR_VERSIONS=R4 FV_HTTP_MAX_BODY_MB=64 \
FV_PACKAGE_REGISTRY_ENABLED=false FV_USAGE_SINK=none \
    java -Xmx3g -jar "$JAR" >"$WORK/service.log" 2>&1 &
JAR_PID=$!
BASE="http://127.0.0.1:$PORT"
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health/readiness")" = "200" ]; do
    i=$((i + 2)); [ "$i" -gt 300 ] && fail "$TAG did not become ready"; sleep 2
done

upload() {
    code=$(curl -s -o "$WORK/upload.json" -w '%{http_code}' -H "Authorization: Bearer $ADMIN_KEY" \
        -F "file=@$1;type=application/gzip" "$BASE/igs/upload")
    case "$code" in 200|201) ;; *) fail "upload of $1 answered $code: $(cat "$WORK/upload.json")" ;; esac
}

echo "==> storing packages"
for id in $(grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$' | cut -d' ' -f1); do
    upload "$PKGS/$id.tgz"
done
upload "$CORPUS/duplicate-canonical/test.dup.canonical-1.0.0.tgz"

include() {
    code=$(curl -s -o "$WORK/include.json" -w '%{http_code}' -H "Authorization: Bearer $ADMIN_KEY" \
        -H 'Content-Type: application/json' -d "{\"igPackageId\":\"$1\",\"igPackageVersion\":\"$2\"}" "$BASE/R4/validator")
    case "$code" in 200|201) ;; *) fail "include of $1#$2 answered $code: $(cat "$WORK/include.json")" ;; esac
}

echo "==> including contexts"
include hl7.fhir.dk.core 3.5.0
include medcom.fhir.dk.core 4.0.0
include test.dup.canonical 1.0.0

# validate <file> <ig> <out>: waits while the context is still building (503), then writes the projection.
validate() {
    n=0
    while :; do
        code=$(curl -s -o "$WORK/outcome.json" -w '%{http_code}' -H "Authorization: Bearer $ADMIN_KEY" \
            -H 'Content-Type: application/fhir+json' -H 'Accept: application/fhir+json' \
            --data-binary "@$1" "$BASE/R4/validate?ig=$(printf '%s' "$2" | sed 's/#/%23/')")
        [ "$code" = "503" ] || break
        n=$((n + 1)); [ "$n" -gt 300 ] && fail "context $2 never became ready"; sleep 2
    done
    [ "$code" = "200" ] || fail "validating $1 against $2 answered $code: $(cat "$WORK/outcome.json")"
    mkdir -p "$(dirname "$3")"
    jq -S '[.issue[]? | {severity, code, expression, location, diagnostics, details: .details.text}]' \
        "$WORK/outcome.json" > "$3"
}

echo "==> validating the corpus"
rm -rf "$GOLDEN"
for group in examples invalid; do
    for context in dk-core medcom-core; do
        case "$context" in
            dk-core) ig="hl7.fhir.dk.core#3.5.0" ;;
            medcom-core) ig="medcom.fhir.dk.core#4.0.0" ;;
        esac
        for file in "$CORPUS/$group/$context"/*.json; do
            validate "$file" "$ig" "$GOLDEN/$group/$context/$(basename "$file")"
        done
    done
done
for file in "$CORPUS/duplicate-canonical/resources"/*.json; do
    validate "$file" "test.dup.canonical#1.0.0" "$GOLDEN/duplicate-canonical/$(basename "$file")"
done
echo "==> golden files written to $GOLDEN ($(find "$GOLDEN" -type f | wc -l | tr -d ' ') files)"
