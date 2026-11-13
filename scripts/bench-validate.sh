#!/bin/sh
# Single-resource validation latency against a running instance (constitution Principle VI; spec 004 SC-007).
# Runs two scenarios with the same Patient: (a) no ig (core-only base context) and (b) ig naming an IG, which should be
# included (pinned) so its context is pre-built. Prints p50/p95/p99 per scenario.
#
# Usage: sh scripts/bench-validate.sh [BASE_URL] [IG] [REQUESTS] [CONCURRENCY]
#   BASE_URL     default http://127.0.0.1:8880
#   IG           default hl7.fhir.dk.core#3.4.0 (store and include it first)
#   REQUESTS     default 2000
#   CONCURRENCY  default 8
#   FV_KEY       (env, required) a VALIDATE or ADMIN key; validation is key-only
# Needs one of: hey (preferred) or ab (ApacheBench). Run it against the previous release and this branch on the same
# machine and attach both outputs to the PR.
set -eu

BASE=${1:-http://127.0.0.1:8880}
IG=${2:-hl7.fhir.dk.core#3.4.0}
N=${3:-2000}
C=${4:-8}
KEY=${FV_KEY:?set FV_KEY to a VALIDATE or ADMIN key (validation needs a key)}
IG_QUERY=$(printf '%s' "$IG" | sed 's/#/%23/')
BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT
cat > "$BODY" <<'JSON'
{"resourceType":"Patient","id":"bench","active":true,"name":[{"family":"Bench","given":["Mark"]}],"gender":"female","birthDate":"1980-01-01"}
JSON

warm() {
  for _ in 1 2 3 4 5; do
    curl -fs -o /dev/null -H 'Content-Type: application/fhir+json' -H "Authorization: Bearer $KEY" --data-binary @"$BODY" "$1" || {
      echo "warm-up request to $1 failed; is the service up and (for the ig scenario) the IG stored?" >&2
      exit 1
    }
  done
}

run() {
  label=$1
  url=$2
  warm "$url"
  echo "== $label ($N requests, concurrency $C)"
  if command -v hey > /dev/null 2>&1; then
    hey -n "$N" -c "$C" -m POST -T 'application/fhir+json' -H "Authorization: Bearer $KEY" -D "$BODY" "$url" \
      | awk '/Latency distribution/{p=1} p && /(50|95|99)% in/{print "   p" $1 " " $3 " s"} /Status code distribution/{s=1} s && /\[/{print "   status " $0}'
  elif command -v ab > /dev/null 2>&1; then
    ab -q -n "$N" -c "$C" -p "$BODY" -T 'application/fhir+json' -H "Authorization: Bearer $KEY" "$url" \
      | awk '/Non-2xx responses/{print "   " $0} /Failed requests/{print "   " $0} / +50% /{print "   p50 " $2 " ms"} / +95% /{print "   p95 " $2 " ms"} / +99% /{print "   p99 " $2 " ms"}'
  else
    echo "needs hey or ab" >&2
    exit 1
  fi
}

run "no ig (base context)" "$BASE/R4/validate"
run "ig=$IG" "$BASE/R4/validate?ig=$IG_QUERY"
