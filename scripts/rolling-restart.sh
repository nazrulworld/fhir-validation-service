#!/bin/sh
# Rolling restart under load (SC-008, FR-029): two validators behind the `cluster` load balancer, steady k6 load,
# restart one instance at a time, and fail if 0.1% or more of the requests failed.
# Usage: sh scripts/rolling-restart.sh   (from the repository root; needs docker compose and jq)
#   ROLLING_VUS (default 8, keep it at or below the admission limit), ROLLING_DURATION (default 150s, at least 2m)
set -eu

PROJECT="${COMPOSE_PROJECT_NAME:-fhir}"
VUS="${ROLLING_VUS:-8}"
DURATION="${ROLLING_DURATION:-150s}"
READY_TIMEOUT="${ROLLING_READY_TIMEOUT:-180}"
MAX_FAILED_RATE="0.001"
K6_IMAGE="grafana/k6:0.54.0"

out=$(mktemp -d)
cleanup() {
  [ -n "${k6_pid:-}" ] && kill "$k6_pid" 2> /dev/null || true
  rm -rf "$out"
}
trap cleanup EXIT

fail() {
  echo "ROLLING RESTART FAILED: $*" >&2
  docker compose --profile cluster logs --no-color --tail=100 validator validator-2 lb >&2 || true
  exit 1
}

[ -f .env ] || fail ".env missing (cp .env.example .env and set PG_PASSWORD)"

# Readiness of one instance, asked from inside its container (validator-2 has no host port).
wait_ready() {
  i=0
  until docker compose exec -T "$1" curl -fsS -o /dev/null http://127.0.0.1:8880/health/readiness 2> /dev/null; do
    i=$((i + 2))
    [ "$i" -ge "$READY_TIMEOUT" ] && fail "$1 not ready after ${READY_TIMEOUT}s"
    sleep 2
  done
}

echo "==> starting the cluster profile"
docker compose --profile cluster up -d --build --wait --wait-timeout "$READY_TIMEOUT" || fail "cluster did not become healthy"
wait_ready validator
wait_ready validator-2

echo "==> load: $VUS VUs for $DURATION through the load balancer"
chmod 777 "$out"
docker run --rm --network "${PROJECT}_default" -v "$PWD":/work:ro -v "$out":/out "$K6_IMAGE" run -q \
  -e BASE=http://lb:80 -e VUS="$VUS" -e DURATION="$DURATION" \
  --summary-export /out/summary.json /work/perf/validate.js > "$out/k6.log" 2>&1 &
k6_pid=$!
sleep 20

for instance in validator validator-2; do
  echo "==> restarting $instance"
  docker compose restart -t 30 "$instance" > /dev/null || fail "restart of $instance failed"
  wait_ready "$instance"
  echo "    $instance ready again"
  sleep 10
done

echo "==> waiting for the load run to finish"
wait "$k6_pid" || true
k6_pid=""
[ -s "$out/summary.json" ] || fail "k6 produced no summary: $(cat "$out/k6.log")"

requests=$(jq '.metrics.http_reqs.count' "$out/summary.json")
failed_rate=$(jq '.metrics.http_req_failed.value' "$out/summary.json")
p95=$(jq '.metrics.http_req_duration["p(95)"]' "$out/summary.json")
echo "requests=$requests failed_rate=$failed_rate p95_ms=$p95"
jq -e --argjson max "$MAX_FAILED_RATE" '.metrics.http_req_failed.value < $max' "$out/summary.json" > /dev/null \
  || fail "failed-request rate $failed_rate is not below $MAX_FAILED_RATE"

echo "ROLLING RESTART OK"
