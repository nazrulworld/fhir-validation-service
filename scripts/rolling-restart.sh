#!/bin/sh
# Rolling restart under load (SC-008, FR-029): two validators behind the `cluster` load balancer, steady k6 load,
# restart one instance at a time, and fail if 0.1% or more of the requests failed.
# Usage: sh scripts/rolling-restart.sh   (from the repository root; needs docker compose and jq)
#   Validates with FV_KEY, else FV_VALIDATOR_API_KEY or FV_ADMIN_API_KEY from .env (4.1.0: validation is key-only).
#   Refuses to run when the running validator image and the version being built differ in major version.
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

# A rolling restart must never cross a major version: 2.0.0 drops tables 1.x reads, and a mixed 1.x/2.x fleet on one
# database is not supported (spec 003, FR-015c). Compare a running validator's image with the version being built.
target_version=$(sed -n 's:^    <version>\(.*\)</version>:\1:p' pom.xml | head -n 1)
running=$(docker compose ps -q validator 2> /dev/null | head -n 1)
if [ -n "$running" ] && [ -n "$target_version" ]; then
  running_version=$(docker inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$running" 2> /dev/null || true)
  # Only released images carry a numeric version; local builds are labelled "dev" and are not compared.
  case "$running_version" in [0-9]*) ;; *) running_version="" ;; esac
  if [ -n "$running_version" ] && [ "${running_version%%.*}" != "${target_version%%.*}" ]; then
    echo "ROLLING RESTART REFUSED: running $running_version, target $target_version." >&2
    echo "Rolling restart across major versions is not supported; see docs/upgrade-2.0.md" >&2
    exit 1
  fi
  # 4.0 → 4.1 is a minor release, but migration V11 re-keys the system organisation while 4.0 instances still use the
  # old id: every 4.0 instance must be stopped first (spec 008, docs/upgrade-4.1.md).
  case "$running_version" in
    4.0.*)
      case "$target_version" in
        4.0.*) ;;
        4.*)
          echo "ROLLING RESTART REFUSED: running $running_version, target $target_version." >&2
          echo "The 4.1 upgrade needs every 4.0 instance stopped first; see docs/upgrade-4.1.md" >&2
          exit 1
          ;;
      esac
      ;;
  esac
fi

# Since 4.1.0 every validation needs a key: FV_KEY, else the trial validator key from .env, else the admin key.
env_value() { sed -n "s/^$1=//p" .env | tail -n 1; }
KEY="${FV_KEY:-$(env_value FV_VALIDATOR_API_KEY)}"
[ -n "$KEY" ] || KEY=$(env_value FV_ADMIN_API_KEY)
[ -n "$KEY" ] || fail "no key to validate with: set FV_VALIDATOR_API_KEY (or FV_ADMIN_API_KEY) in .env, or FV_KEY"

echo "==> starting the cluster profile"
docker compose --profile cluster up -d --build --wait --wait-timeout "$READY_TIMEOUT" || fail "cluster did not become healthy"
wait_ready validator
wait_ready validator-2

echo "==> load: $VUS VUs for $DURATION through the load balancer"
chmod 777 "$out"
docker run --rm --network "${PROJECT}_default" -v "$PWD":/work:ro -v "$out":/out "$K6_IMAGE" run -q \
  -e BASE=http://lb:80 -e VUS="$VUS" -e DURATION="$DURATION" -e FV_KEY="$KEY" \
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
