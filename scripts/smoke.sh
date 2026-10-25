#!/bin/sh
# Container smoke test (contracts/container.md §3):
#   start the stack, wait for readiness, validate a fixture, stop the validator gracefully.
# Usage: sh scripts/smoke.sh   (from the repository root; needs docker compose, curl, jq)
set -eu

BASE="http://127.0.0.1:${FV_HTTP_PORT:-8880}"
FIXTURE="src/test/resources/fhir/resources/dk/Patient.json"
READY_TIMEOUT="${SMOKE_READY_TIMEOUT:-180}"

fail() {
  echo "SMOKE FAILED: $*" >&2
  docker compose logs --no-color --tail=200 >&2 || true
  exit 1
}

[ -f .env ] || fail ".env missing (cp .env.example .env and set PG_PASSWORD)"

echo "==> starting stack"
docker compose up -d --build --wait --wait-timeout "$READY_TIMEOUT" || fail "stack did not become healthy"

echo "==> waiting for readiness"
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health/readiness")" = "200" ]; do
  i=$((i + 2))
  [ "$i" -ge "$READY_TIMEOUT" ] && fail "readiness not 200 after ${READY_TIMEOUT}s"
  sleep 2
done

echo "==> validating fixture"
body=$(mktemp)
code=$(curl -s -o "$body" -w '%{http_code}' -H 'Content-Type: application/fhir+json' \
  --data-binary "@$FIXTURE" "$BASE/R4/validate")
[ "$code" = "200" ] || fail "validate returned HTTP $code: $(cat "$body")"
jq -e '.valid | type == "boolean"' "$body" > /dev/null || fail "response has no boolean 'valid': $(cat "$body")"
rm -f "$body"

echo "==> graceful stop (SIGTERM)"
# `stop` sends SIGTERM and, unlike `kill`, keeps the restart policy from restarting the container.
start=$(date +%s)
docker compose stop -t 30 validator > /dev/null
[ $(( $(date +%s) - start )) -lt 30 ] || fail "validator did not stop within 30s of SIGTERM"
exit_code=$(docker inspect -f '{{.State.ExitCode}}' "$(docker compose ps -a -q validator)")
# 143 = terminated by SIGTERM after the shutdown hooks ran (JVM convention); anything else is a crash.
[ "$exit_code" = "0" ] || [ "$exit_code" = "143" ] || fail "validator exited with code $exit_code"

echo "SMOKE OK"
