#!/bin/bash
# Spec 014 T052 run 3 (SC-006): the trial key at its cap (600/min, 8 VUs) while a key of another organisation validates
# at a steady low rate (1 VU), compared with that key alone. Same container limits as perf/compare-run.sh.
set -u
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=$REPO/target/perf014
IMAGE=fhir-validation-service:014-api-throttling
NET=fvperf PG=fvperf-pg-atcap SVC=fvperf-svc-atcap
ADMIN=perf-admin-key-0123456789abcdefghijklmnop
VKEY=perf-validator-key-0123456789abcdefghijkl
docker network inspect $NET >/dev/null 2>&1 || docker network create $NET >/dev/null
docker rm -f $PG $SVC >/dev/null 2>&1
docker run -d --name $PG --network $NET -e POSTGRES_PASSWORD=perfpw -e POSTGRES_DB=fhir_validator postgres:16.8-alpine3.21 >/dev/null
for i in $(seq 1 60); do docker exec $PG pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
docker run -d --name $SVC --network $NET --cpus 4 --memory 4g -e FV_PG_HOST=$PG -e FV_PG_PASSWORD=perfpw \
  -e FV_PG_SSL_MODE=disable -e FV_VALIDATION_WORKERS=4 -e FV_ADMIN_API_KEY=$ADMIN -e FV_VALIDATOR_API_KEY=$VKEY \
  -e FV_USAGE_SINK=db -e FV_SHUTDOWN_PRE_DRAIN_MS=0 $IMAGE >/dev/null
for i in $(seq 1 300); do docker exec $SVC sh -c 'wget -qO- http://127.0.0.1:8880/health/readiness >/dev/null 2>&1 || curl -fsS -o /dev/null http://127.0.0.1:8880/health/readiness' && break; sleep 1; done
docker exec $SVC fv-admin org create --name-short other --name-long "Other org" --type COMMERCIAL >/dev/null
OKEY=$(docker exec $SVC fv-admin key create --org other --name other-key --role VALIDATE | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')
# Trial key at 600/min; everything else far above its rate.
docker exec $SVC fv-admin limit set --key "trial validator" --per-minute 600 >/dev/null
docker exec $SVC fv-admin limit set --org trial --per-minute 1000000 >/dev/null
docker exec $SVC fv-admin limit set --default-key --per-minute 1000000 >/dev/null
docker exec $SVC fv-admin limit set --default-org --per-minute 1000000 >/dev/null
sleep 11
K6=(docker run --rm --network $NET -v "$REPO":/work -w /work/perf grafana/k6:0.54.0 run --quiet -e BASE=http://$SVC:8880)
"${K6[@]}" -e FV_KEY=$OKEY -e VUS=8 -e DURATION=15s validate.js >/dev/null 2>&1   # warm-up (other key, uncapped)
# Bystander alone.
"${K6[@]}" -e FV_KEY=$OKEY -e VUS=1 -e DURATION=60s --summary-export /work/target/perf014/other-alone.json validate.js > $OUT/other-alone.k6.txt 2>&1
sleep 61   # let the warm-up leave every window
# Trial key at its cap, bystander alongside.
"${K6[@]}" -e FV_KEY=$VKEY -e VUS=8 -e DURATION=60s --summary-export /work/target/perf014/trial-at-cap.json validate.js > $OUT/trial-at-cap.k6.txt 2>&1 &
TRIAL=$!
sleep 2
"${K6[@]}" -e FV_KEY=$OKEY -e VUS=1 -e DURATION=55s --summary-export /work/target/perf014/other-during.json validate.js > $OUT/other-during.k6.txt 2>&1
wait $TRIAL
docker exec $SVC sh -c 'wget -qO- http://127.0.0.1:8880/metrics 2>/dev/null || curl -fsS http://127.0.0.1:8880/metrics' > $OUT/atcap.metrics.txt
docker exec $PG psql -U postgres -d fhir_validator -tAc "SELECT http_status, count(*) FROM fhir_validator_schema.validation_usage u JOIN fhir_validator_schema.api_keys k ON k.id = u.api_key_id WHERE k.name = 'trial validator' GROUP BY 1 ORDER BY 1" > $OUT/atcap.usage.txt
docker rm -f $SVC $PG >/dev/null 2>&1
echo "atcap: done"
