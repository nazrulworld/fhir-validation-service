#!/bin/sh
# Spec 010 T062 (SC-005, SC-006): memory and build time of a second context whose packages are shared.
#
# Starts one jar against a throw-away PostgreSQL, stores the locked test packages, includes DK Core 3.5.0 (first
# context), takes the live heap after a full collection, includes MedCom Core 4.0.0 (second context, sharing ten
# packages), takes it again, and prints the difference plus the second build's time from the log. Run it once with the
# v1.0.0 jar and once with this branch's jar; the same JVM settings are used for both.
#
# usage: perf/measure-010.sh <jar> <label>     (needs docker, curl, jcmd; run scripts/fetch-test-packages.sh first)
set -eu
JAR=$1
LABEL=$2
REPO=$(cd "$(dirname "$0")/.." && pwd)
LOCK="$REPO/src/test/resources/ig/packages/010-packages.lock"
PKGS=${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}
PORT=${MEASURE_HTTP_PORT:-18890}
PG_PORT=${MEASURE_PG_PORT:-15433}
KEY=measure-admin-key-0123456789abcdefghijklmnop
PG=fv-measure-pg-$$
WORK=$(mktemp -d)
PID=

cleanup() {
    [ -n "$PID" ] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null || true
    docker rm -f "$PG" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
# Ctrl-C or TERM: stop (the EXIT trap then cleans up) instead of carrying on against a stopped service.
trap 'exit 130' INT TERM

docker run -d --rm --name "$PG" -e POSTGRES_PASSWORD=measure -p "127.0.0.1:$PG_PORT:5432" postgres:16 >/dev/null
until docker exec "$PG" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; do sleep 1; done

FV_HTTP_PORT=$PORT FV_PG_HOST=127.0.0.1 FV_PG_PORT=$PG_PORT FV_PG_DATABASE=postgres FV_PG_USER=postgres \
FV_PG_PASSWORD=measure FV_ADMIN_API_KEY=$KEY FV_ENABLED_FHIR_VERSIONS=R4 FV_HTTP_MAX_BODY_MB=64 \
FV_PACKAGE_REGISTRY_ENABLED=false FV_USAGE_SINK=none \
    java -Xms3g -Xmx3g -XX:+UseG1GC -jar "$JAR" >"$WORK/service.log" 2>&1 &
PID=$!
BASE="http://127.0.0.1:$PORT"
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health/readiness")" = "200" ]; do sleep 2; done

for id in $(grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$' | cut -d' ' -f1); do
    curl -s -o /dev/null -H "Authorization: Bearer $KEY" -F "file=@$PKGS/$id.tgz;type=application/gzip" "$BASE/igs/upload"
done

include_and_wait() {
    curl -s -o /dev/null -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
        -d "{\"igPackageId\":\"$1\",\"igPackageVersion\":\"$2\"}" "$BASE/R4/validator"
    until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" -H 'Content-Type: application/fhir+json' \
        --data '{"resourceType":"Patient"}' "$BASE/R4/validate?ig=$1%23$2")" = "200" ]; do sleep 1; done
}

live_heap() {
    jcmd "$PID" GC.run >/dev/null
    sleep 2
    jcmd "$PID" GC.run >/dev/null
    # "garbage-first heap   total 3145728K, used 812345K" -> bytes
    jcmd "$PID" GC.heap_info | sed -n 's/.*used \([0-9]*\)K.*/\1/p' | head -n 1 | awk '{print $1 * 1024}'
}

include_and_wait hl7.fhir.dk.core 3.5.0
before=$(live_heap)
include_and_wait medcom.fhir.dk.core 4.0.0
after=$(live_heap)
build_ms=$(grep 'Built validator for R4/medcom.fhir.dk.core#4.0.0\|Built validator for .*medcom.fhir.dk.core#4.0.0' "$WORK/service.log" \
    | sed -n 's/.* in \([0-9]*\) ms.*/\1/p' | tail -n 1)
first_ms=$(grep 'Built validator for .*hl7.fhir.dk.core#3.5.0' "$WORK/service.log" | sed -n 's/.* in \([0-9]*\) ms.*/\1/p' | tail -n 1)
echo "$LABEL second_context_live_heap_bytes=$((after - before)) first_build_ms=$first_ms second_build_ms=$build_ms"
