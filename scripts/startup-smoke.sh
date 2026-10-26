#!/bin/sh
# Startup smoke test for an image built for another platform (contracts/container.md §3), run under QEMU in CI:
#   start the image against a native PostgreSQL, wait for liveness and the schema migration, stop it gracefully.
# Readiness and validation are left to scripts/smoke.sh on the native platform: warming the validators for four
# FHIR versions under emulation takes too long for CI.
# Usage: sh scripts/startup-smoke.sh <image> <platform>   e.g. fhir-validation-service:arm64 linux/arm64
set -eu

IMAGE="$1"
PLATFORM="$2"
PORT="${SMOKE_PORT:-18880}"
TIMEOUT="${SMOKE_STARTUP_TIMEOUT:-300}"
NAME="fv-startup-smoke-$$"
PASSWORD="smoke-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"

cleanup() {
  docker rm -f "$NAME-app" "$NAME-pg" > /dev/null 2>&1 || true
  docker network rm "$NAME" > /dev/null 2>&1 || true
}
fail() {
  echo "STARTUP SMOKE FAILED: $*" >&2
  docker logs --tail=200 "$NAME-app" >&2 || true
  cleanup
  exit 1
}
trap cleanup EXIT

docker network create "$NAME" > /dev/null

echo "==> starting postgres (native platform)"
docker run -d --name "$NAME-pg" --network "$NAME" --network-alias postgres \
  -e POSTGRES_DB=fhir_validator -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD="$PASSWORD" \
  -v "$(pwd)/docker/postgres-init:/docker-entrypoint-initdb.d:ro" \
  postgres:16.8-alpine > /dev/null
i=0
until docker exec "$NAME-pg" pg_isready -U postgres -d fhir_validator > /dev/null 2>&1; do
  i=$((i + 1))
  [ "$i" -ge 60 ] && fail "postgres not ready after 60s"
  sleep 1
done

echo "==> starting $IMAGE ($PLATFORM)"
docker run -d --name "$NAME-app" --network "$NAME" --platform "$PLATFORM" \
  -e FV_PG_HOST=postgres -e FV_PG_PASSWORD="$PASSWORD" \
  -p "127.0.0.1:$PORT:8880" "$IMAGE" > /dev/null
arch=$(docker exec "$NAME-app" uname -m) || fail "container did not start"
echo "    container architecture: $arch"

echo "==> waiting for liveness and the schema migration"
start=$(date +%s)
until [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/health/liveness")" = "200" ] \
  && docker logs "$NAME-app" 2>&1 | grep -q "Successfully applied\|Schema .* is up to date"; do
  [ "$(docker inspect -f '{{.State.Running}}' "$NAME-app")" = "true" ] || fail "container exited"
  [ $(( $(date +%s) - start )) -ge "$TIMEOUT" ] && fail "not live and migrated after ${TIMEOUT}s"
  sleep 3
done
echo "    live after $(( $(date +%s) - start ))s"

echo "==> graceful stop (SIGTERM)"
docker stop -t 60 "$NAME-app" > /dev/null
exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$NAME-app")
# 143 = terminated by SIGTERM after the shutdown hooks ran (JVM convention); anything else is a crash.
[ "$exit_code" = "0" ] || [ "$exit_code" = "143" ] || fail "exited with code $exit_code"
logs=$(docker logs "$NAME-app" 2>&1)
echo "$logs" | grep -q "drain started" || fail "no 'drain started' log line"
echo "$logs" | grep -q "drain complete" || fail "no 'drain complete' log line"

echo "STARTUP SMOKE OK ($PLATFORM)"
