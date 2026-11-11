#!/bin/bash
# Spec 008 T071 (SC-007): one perf/validate.js run against a fresh PostgreSQL + service pair.
# usage: perf/compare-run.sh <label> <image> <sink: -|none|db> <out-dir>
set -u
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

K6=(docker run --rm --network $NET -v "$REPO":/work -w /work/perf grafana/k6:0.54.0 run --quiet
    -e BASE=http://$SVC:8880 ${KEYARG[@]+"${KEYARG[@]}"} -e VUS=8)
"${K6[@]}" -e DURATION=15s validate.js >/dev/null 2>&1   # warm-up
"${K6[@]}" -e DURATION=60s --summary-export /work/target/perf-$LABEL.json validate.js > "$OUT/$LABEL.k6.txt" 2>&1
mv "$REPO/target/perf-$LABEL.json" "$OUT/$LABEL.json"

curl -fsS http://127.0.0.1:18880/metrics > "$OUT/$LABEL.metrics.txt" 2>/dev/null
if [ "$SINK" = "db" ]; then
  sleep 2
  docker exec $PG psql -U postgres -d fhir_validator -tAc \
    "SELECT count(*) FROM fhir_validator_schema.validation_usage" > "$OUT/$LABEL.rows.txt"
fi
docker rm -f $SVC $PG >/dev/null 2>&1
echo "$LABEL: done"
