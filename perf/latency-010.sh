#!/bin/sh
# Spec 010 T069 (SC-007): p95 of single-resource validation on a warm context with no terminology server.
#
# Starts one jar against a throw-away PostgreSQL (same JVM settings for every run), includes DK Core 3.5.0 from the
# locked test packages, warms up, then runs perf/validate.js with the grafana/k6 image twice: on the base context and
# on the DK Core context. Run it once with the v1.0.0 jar and once with this branch's jar on the same machine.
#
# usage: perf/latency-010.sh <jar> <label> [duration]   (needs docker, curl; run scripts/fetch-test-packages.sh first)
set -eu
JAR=$1
LABEL=$2
DURATION=${3:-60s}
REPO=$(cd "$(dirname "$0")/.." && pwd)
PKGS=${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}
LOCK="$REPO/src/test/resources/ig/packages/010-packages.lock"
PORT=${LATENCY_HTTP_PORT:-18891}
PG_PORT=${LATENCY_PG_PORT:-15434}
KEY=latency-admin-key-0123456789abcdefghijklmnop
PG=fv-latency-pg-$$
WORK=$(mktemp -d)
PID=

cleanup() {
    [ -n "$PID" ] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null || true
    docker rm -f "$PG" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

docker run -d --rm --name "$PG" -e POSTGRES_PASSWORD=latency -p "127.0.0.1:$PG_PORT:5432" postgres:16 >/dev/null
until docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1; do sleep 1; done

FV_HTTP_PORT=$PORT FV_PG_HOST=127.0.0.1 FV_PG_PORT=$PG_PORT FV_PG_DATABASE=postgres FV_PG_USER=postgres \
FV_PG_PASSWORD=latency FV_ADMIN_API_KEY=$KEY FV_ENABLED_FHIR_VERSIONS=R4 FV_HTTP_MAX_BODY_MB=64 \
FV_PACKAGE_REGISTRY_ENABLED=false FV_USAGE_SINK=none FV_VALIDATION_WORKERS=4 \
    java -Xms3g -Xmx3g -XX:+UseG1GC -jar "$JAR" >"$WORK/service.log" 2>&1 &
PID=$!
BASE="http://127.0.0.1:$PORT"
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health/readiness")" = "200" ]; do sleep 2; done
for id in $(grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$' | cut -d' ' -f1); do
    curl -s -o /dev/null -H "Authorization: Bearer $KEY" -F "file=@$PKGS/$id.tgz;type=application/gzip" "$BASE/igs/upload"
done
curl -s -o /dev/null -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
    -d '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.5.0"}' "$BASE/R4/validator"
until [ "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KEY" -H 'Content-Type: application/fhir+json' \
    --data '{"resourceType":"Patient"}' "$BASE/R4/validate?ig=hl7.fhir.dk.core%233.5.0")" = "200" ]; do sleep 1; done

k6() {
    docker run --rm -v "$REPO:/repo:ro" grafana/k6:0.54.0 run --quiet --summary-trend-stats "avg,p(95),p(99)" \
        -e BASE="http://host.docker.internal:$PORT" -e FV_KEY="$KEY" -e VUS=4 -e DURATION="$1" $2 /repo/perf/validate.js 2>&1 \
        | grep -E 'http_req_duration|http_reqs|http_req_failed' | sed "s/^/$LABEL $3 /"
}
k6 20s "" warmup-base >/dev/null
k6 20s "-e IG=hl7.fhir.dk.core#3.5.0" warmup-ig >/dev/null
k6 "$DURATION" "" base
k6 "$DURATION" "-e IG=hl7.fhir.dk.core#3.5.0" dk-core
