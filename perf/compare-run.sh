#!/bin/bash
# Spec 008 T071 (SC-007): one perf/validate.js run against a fresh PostgreSQL + service pair.
# usage: perf/compare-run.sh <label> <image> <sink: -|none|db> <out-dir>
# Optional (spec 012): IG=name#version includes that IG for R4 first (IG_TGZ="<.tgz> ..." uploads packages and
# IG_REGISTER="name#version ..." fetches them from the registries beforehand), and every
# request then names it in ?ig=. With IG2=name#version both are included as one two-IG context and requests name
# ig=IG|IG2. The context's build time and the memory before and after the include are recorded.
# Optional: VUS=<n> concurrent k6 virtual users (default 8). MAX_PENDING=<n> sets FV_VALIDATION_MAX_PENDING (default 4 ×
# workers = 16 here); keep VUS at or below it, or the extra requests are refused with 503 overloaded.
set -u
VUS=${VUS:-8}
LABEL=$1 IMAGE=$2 SINK=$3 OUT=$4
REPO=$(cd "$(dirname "$0")/.." && pwd)
NET=fvperf
ADMIN=perf-admin-key-0123456789abcdefghijklmnop
VKEY=perf-validator-key-0123456789abcdefghijkl
PG=fvperf-pg-$LABEL
SVC=fvperf-svc-$LABEL
mkdir -p "$OUT"
docker network inspect $NET >/dev/null 2>&1 || docker network create $NET >/dev/null
docker rm -f $PG $SVC >/dev/null 2>&1

docker run -d --name $PG --network $NET -e POSTGRES_PASSWORD=perfpw -e POSTGRES_DB=fhir_validator \
  postgres:16.8-alpine3.21 >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done

ENV=(-e FV_PG_HOST=$PG -e FV_PG_PASSWORD=perfpw -e FV_PG_SSL_MODE=disable -e FV_VALIDATION_WORKERS=4
     -e FV_ADMIN_API_KEY=$ADMIN -e FV_SHUTDOWN_PRE_DRAIN_MS=0)
[ -n "${MAX_PENDING:-}" ] && ENV+=(-e FV_VALIDATION_MAX_PENDING=$MAX_PENDING)
KEYARG=()
if [ "$SINK" != "-" ]; then
  ENV+=(-e FV_VALIDATOR_API_KEY=$VKEY -e FV_USAGE_SINK=$SINK)
  KEYARG=(-e FV_KEY=$VKEY)
fi
docker run -d --name $SVC --network $NET --cpus 4 --memory 4g -p 127.0.0.1:18880:8880 "${ENV[@]}" "$IMAGE" >/dev/null

ready=0
for i in $(seq 1 300); do
  if curl -fsS -o /dev/null http://127.0.0.1:18880/health/readiness 2>/dev/null; then ready=1; break; fi
  sleep 1
done
if [ $ready != 1 ]; then echo "$LABEL: not ready"; docker logs --tail 40 $SVC; exit 1; fi

# Spec 014 (1.4.0+): THROTTLE_CAP=<n> sets the trial key's, the trial organisation's and the default caps to n before the
# run (e.g. 1000000 so nothing is refused, or a low value to measure 429 throughput). Unset: the shipped caps (the trial
# key is limited to 30 a minute). Leave it unset for images before 1.4.0, which have no caps.
if [ -n "${THROTTLE_CAP:-}" ]; then
  docker exec $PG psql -U postgres -d fhir_validator -v ON_ERROR_STOP=1 -qc "
    UPDATE fhir_validator_schema.api_keys SET rate_limit_per_minute = $THROTTLE_CAP WHERE name = 'trial validator';
    UPDATE fhir_validator_schema.organisations SET rate_limit_per_minute = $THROTTLE_CAP WHERE name_short = 'trial';
    UPDATE fhir_validator_schema.service_settings SET value = '$THROTTLE_CAP'
      WHERE name IN ('throttle_default_key_per_minute', 'throttle_default_org_per_minute');" \
    || { echo "$LABEL: setting THROTTLE_CAP failed"; exit 1; }
  sleep 11   # FV_THROTTLE_CAPS_REFRESH_MS (10 s) + one sync interval
fi

memory() {  # the container's memory in use, and the service's estimate of the heap its package sets hold (R4)
  echo "container $(docker stats --no-stream --format '{{.MemUsage}}' $SVC)"
  curl -fsS http://127.0.0.1:18880/metrics | grep -E '^fv_packages_shared_(retained_estimate|expanded)_bytes\{fhir_version="R4"'
}
for tgz in ${IG_TGZ:-}; do
  curl -fsS -H "Authorization: Bearer $ADMIN" -F "file=@$tgz;type=application/gzip" \
    http://127.0.0.1:18880/igs/upload >/dev/null || { echo "$LABEL: upload of $tgz failed"; exit 1; }
done
# Spec 012: IG_REGISTER="name#version ..." fetches packages (with dependencies) from the configured registries.
for ref in ${IG_REGISTER:-}; do
  curl -fsS -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
    --data "{\"name\":\"${ref%%#*}\",\"version\":\"${ref#*#}\"}" \
    http://127.0.0.1:18880/igs/register >/dev/null || { echo "$LABEL: register of $ref failed"; exit 1; }
done
if [ -n "${IG:-}" ]; then
  if [ -n "${IG2:-}" ]; then
    # Spec 012: IG2=name#version includes IG and IG2 as one two-IG context; requests then name ig=IG|IG2.
    BODY="{\"igPackages\":[{\"igPackageId\":\"${IG%%#*}\",\"igPackageVersion\":\"${IG#*#}\"},{\"igPackageId\":\"${IG2%%#*}\",\"igPackageVersion\":\"${IG2#*#}\"}]}"
    KEY="$IG|$IG2"
  else
    BODY="{\"igPackageId\":\"${IG%%#*}\",\"igPackageVersion\":\"${IG#*#}\"}"
    KEY="$IG"
  fi
  sleep 5
  memory > "$OUT/$LABEL.memory-before.txt"
  curl -fsS -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' --data "$BODY" \
    http://127.0.0.1:18880/R4/validator > "$OUT/$LABEL.include.json" || { echo "$LABEL: include failed"; exit 1; }
  # The build time this instance logs for the new context, and the memory in use once it is built.
  for i in $(seq 1 300); do
    docker logs $SVC 2>&1 | grep -F "Built validator for" | grep -F "$KEY" > "$OUT/$LABEL.build.txt" && break
    sleep 1
  done
  [ -s "$OUT/$LABEL.build.txt" ] || { echo "$LABEL: context not built"; docker logs --tail 40 $SVC; exit 1; }
  sleep 5
  memory > "$OUT/$LABEL.memory-after.txt"
  curl -fsS http://127.0.0.1:18880/health > "$OUT/$LABEL.health.json"
  KEYARG+=(-e "IG=$KEY")
fi
K6=(docker run --rm --network $NET -v "$REPO":/work -w /work/perf grafana/k6:0.54.0 run --quiet
    -e BASE=http://$SVC:8880 ${KEYARG[@]+"${KEYARG[@]}"} -e VUS=$VUS)
"${K6[@]}" -e DURATION=15s validate.js >/dev/null 2>&1   # warm-up
"${K6[@]}" -e DURATION=60s --summary-export /work/target/perf-$LABEL.json validate.js > "$OUT/$LABEL.k6.txt" 2>&1
mv "$REPO/target/perf-$LABEL.json" "$OUT/$LABEL.json"

curl -fsS http://127.0.0.1:18880/metrics > "$OUT/$LABEL.metrics.txt" 2>/dev/null
grep -E '^fv_throttle_refused_total' "$OUT/$LABEL.metrics.txt" > "$OUT/$LABEL.refused.txt" 2>/dev/null || true
if [ "$SINK" = "db" ]; then
  sleep 2
  docker exec $PG psql -U postgres -d fhir_validator -tAc \
    "SELECT count(*) FROM fhir_validator_schema.validation_usage" > "$OUT/$LABEL.rows.txt"
fi
docker rm -f $SVC $PG >/dev/null 2>&1
echo "$LABEL: done"
