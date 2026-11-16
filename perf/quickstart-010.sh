#!/bin/sh
# Spec 010 T075: quickstart scenarios A (setting change without rebuild), B (per-request choice) and E (shared memory
# released) against one real 1.1.0 jar, the official HL7 terminology registry and the servers it lists (network
# needed). Scenarios C, D and F are the automated differential test and perf/measure-010.sh / perf/latency-010.sh.
#
# usage: perf/quickstart-010.sh <jar>      (needs docker, curl, jq, jcmd; run scripts/fetch-test-packages.sh first)
set -eu
JAR=$1
REPO=$(cd "$(dirname "$0")/.." && pwd)
LOCK="$REPO/src/test/resources/ig/packages/010-packages.lock"
PKGS=${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}
PORT=${QS_HTTP_PORT:-18892}
PG_PORT=${QS_PG_PORT:-15435}
KEY=quickstart-admin-key-0123456789abcdefghijklmn
PG=fv-qs-pg-$$
WORK=$(mktemp -d)
PID=
TX=${QS_TX:-http://tx.fhir.org/r4}

cleanup() {
    [ -n "$PID" ] && kill "$PID" 2>/dev/null && wait "$PID" 2>/dev/null || true
    docker rm -f "$PG" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
# Ctrl-C or TERM: stop (the EXIT trap then cleans up) instead of carrying on against a stopped service.
trap 'exit 130' INT TERM
pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*"; [ -f "$WORK/service.log" ] && tail -n 40 "$WORK/service.log"; exit 1; }
# wait_for <seconds> <what> <command...>: runs the command every second until it succeeds, or fails after <seconds>.
wait_for() {
    limit=$1; what=$2; shift 2; n=0
    until "$@"; do
        n=$((n + 1)); [ "$n" -ge "$limit" ] && fail "timed out after ${limit}s waiting for $what"
        kill -0 "${PID:-$$}" 2>/dev/null || fail "the service stopped while waiting for $what"
        sleep 1
    done
}
ready() { [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/health/readiness")" = "200" ]; }

docker run -d --rm --name "$PG" -e POSTGRES_PASSWORD=qs -p "127.0.0.1:$PG_PORT:5432" postgres:16 >/dev/null
wait_for 60 "PostgreSQL" sh -c "docker exec $PG pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1"
FV_HTTP_PORT=$PORT FV_PG_HOST=127.0.0.1 FV_PG_PORT=$PG_PORT FV_PG_DATABASE=postgres FV_PG_USER=postgres \
FV_PG_PASSWORD=qs FV_ADMIN_API_KEY=$KEY FV_ENABLED_FHIR_VERSIONS=R4 FV_HTTP_MAX_BODY_MB=64 \
FV_PACKAGE_REGISTRY_ENABLED=false FV_USAGE_SINK=none \
    java -Xmx3g -jar "$JAR" >"$WORK/service.log" 2>&1 &
PID=$!
BASE="http://127.0.0.1:$PORT"
AUTH="Authorization: Bearer $KEY"
wait_for 180 "readiness" ready
# Wait until the registry list is fetched (source "registry"), so tx.fhir.org is on the allowlist.
i=0
until curl -s "$BASE/health" | jq -e '.terminology.allowlist.R4.source == "registry"' >/dev/null; do
    i=$((i + 2)); [ "$i" -gt 120 ] && fail "the registry list was not fetched (network?)"; sleep 2
done
for id in $(grep -v '^#' "$LOCK" | grep -v '^[[:space:]]*$' | cut -d' ' -f1); do
    [ -f "$PKGS/$id.tgz" ] || fail "$PKGS/$id.tgz is missing: run scripts/fetch-test-packages.sh"
    code=$(curl -s -o "$WORK/upload" -w '%{http_code}' -H "$AUTH" -F "file=@$PKGS/$id.tgz;type=application/gzip" "$BASE/igs/upload")
    case "$code" in 200|201) ;; *) fail "upload of $id answered $code: $(cat "$WORK/upload")" ;; esac
done

OBS='{"resourceType":"Observation","status":"final","code":{"coding":[{"system":"http://loinc.org","code":"8867-4","display":"Heart rate"}]}}'
validate() { # $1 = ig (or ""), $2 = extra query; prints "status mode server source"
    q=""; [ -n "$1" ] && q="ig=$(printf '%s' "$1" | sed 's/#/%23/')&"
    curl -s -D "$WORK/h" -o "$WORK/b" -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/fhir+json' \
        --data "$OBS" "$BASE/R4/validate?$q$2" >"$WORK/code"
    echo "$(cat "$WORK/code") $(grep -i '^x-tx-mode:' "$WORK/h" | tr -d '\r' | cut -d' ' -f2) \
$(grep -i '^x-tx-server:' "$WORK/h" | tr -d '\r' | cut -d' ' -f2) $(grep -i '^x-tx-source:' "$WORK/h" | tr -d '\r' | cut -d' ' -f2)"
}
builds() { grep -c 'Built validator for' "$WORK/service.log" || true; }
validates() {
    [ "$(curl -s -o /dev/null -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/fhir+json' \
        --data '{"resourceType":"Patient"}' "$BASE/R4/validate?ig=$(printf '%s' "$1" | sed 's/#/%23/')")" = "200" ]
}
wait_built() { wait_for 300 "$1 to be built" validates "$1"; }
DK=hl7.fhir.dk.core#3.5.0
MC=medcom.fhir.dk.core#4.0.0

echo "== A: a setting change does not rebuild (US1, SC-001)"
curl -s -o /dev/null -H "$AUTH" -H 'Content-Type: application/json' \
    -d "{\"igPackageId\":\"hl7.fhir.dk.core\",\"igPackageVersion\":\"3.5.0\",\"terminology\":{\"mode\":\"server\",\"server\":\"$TX\"}}" "$BASE/R4/validator"
wait_built "$DK"
r=$(validate "$DK" ""); echo "   $r"; case "$r" in "200 server $TX context") pass "A1 default server reported";; *) fail "A1 $r";; esac
before=$(builds)
code=$(curl -s -o "$WORK/p" -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/json' -X PATCH \
    -d '{"terminology":{"mode":"none"}}' "$BASE/R4/validator?ig=hl7.fhir.dk.core%233.5.0")
[ "$code" = "200" ] || fail "A2 PATCH answered $code: $(cat "$WORK/p")"
i=0; until [ "$(validate "$DK" "" | cut -d' ' -f2)" = "none" ]; do i=$((i+1)); [ "$i" -gt 20 ] && fail "A2 not applied"; sleep 1; done
pass "A2 the next request uses mode none"
[ "$(builds)" = "$before" ] && pass "A3 no build after the change ($before builds in total)" || fail "A3 a build ran"

echo "== B: per-request choice (US2, SC-002, SC-003)"
curl -s -o /dev/null -H "$AUTH" -H 'Content-Type: application/json' -X PATCH \
    -d "{\"terminology\":{\"mode\":\"server\",\"server\":\"$TX\"}}" "$BASE/R4/validator?ig=hl7.fhir.dk.core%233.5.0"
sleep 2
r=$(validate "$DK" ""); case "$r" in "200 server $TX context") pass "B1 no parameters: the context's server";; *) fail "B1 $r";; esac
r=$(validate "$DK" "txMode=none"); case "$r" in "200 none  request") pass "B2 txMode=none: offline";; *) fail "B2 $r";; esac
code=$(curl -s -o "$WORK/b" -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/fhir+json' --data "$OBS" \
    "$BASE/R4/validate?ig=hl7.fhir.dk.core%233.5.0&tx=https%3A%2F%2Fexample.org%2Ffhir")
[ "$code" = "400" ] && jq -e '.type | endswith("tx-server-not-registered")' "$WORK/b" >/dev/null \
    && pass "B3 unlisted tx: 400 tx-server-not-registered" || fail "B3 $code $(cat "$WORK/b")"
code=$(curl -s -o "$WORK/b" -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/fhir+json' --data "$OBS" \
    "$BASE/R4/validate?ig=hl7.fhir.dk.core%233.5.0&txMode=none&tx=$(printf '%s' "$TX" | jq -sRr @uri)")
[ "$code" = "400" ] && pass "B4 txMode=none with tx: 400" || fail "B4 $code"
code=$(curl -s -o "$WORK/b" -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/fhir+json' --data "$OBS" \
    "$BASE/R4/validate?ig=hl7.fhir.dk.core%233.5.0&txMode=fast")
[ "$code" = "400" ] && pass "B5 txMode=fast: 400" || fail "B5 $code"
code=$(curl -s -o "$WORK/b" -w '%{http_code}' -H "$AUTH" -H 'Content-Type: application/fhir+json' --data "$OBS" \
    "$BASE/R4/validate?txMode=server")
[ "$code" = "400" ] && pass "B6 base context, txMode=server without a server: 400" || fail "B6 $code"
r=$(validate "" "txMode=ecosystem"); case "$r" in "200 ecosystem  request") pass "B7 base context, txMode=ecosystem";; *) fail "B7 $r";; esac
[ "$(builds)" = "$before" ] && pass "B8 still no build" || fail "B8 a build ran"

echo "== E: shared memory is released (SC-008)"
curl -s -o /dev/null -H "$AUTH" -H 'Content-Type: application/json' \
    -d '{"igPackageId":"medcom.fhir.dk.core","igPackageVersion":"4.0.0"}' "$BASE/R4/validator"
wait_built "$MC"
entries() { curl -s "$BASE/metrics" | sed -n 's/^fv_packages_shared_entries{fhir_version="R4".*} \(.*\)$/\1/p'; }
echo "   shared entries with both contexts: $(entries)"
grep 'Loaded IG' "$WORK/service.log" | grep -c 'reused' | sed 's/^/   packages reused by the second build: /'
curl -s -o /dev/null -H "$AUTH" -X DELETE "$BASE/R4/validator?ig=hl7.fhir.dk.core%233.5.0"
curl -s -o /dev/null -H "$AUTH" -X DELETE "$BASE/R4/validator?ig=medcom.fhir.dk.core%234.0.0"
i=0
until [ "$(entries)" = "0.0" ]; do
    jcmd "$PID" GC.run >/dev/null; i=$((i + 2)); [ "$i" -gt 60 ] && fail "E entries still $(entries) after 60 s"; sleep 2
done
pass "E shared entries back to 0 within $i s of excluding both contexts"
echo "ALL QUICKSTART SCENARIOS PASSED"
