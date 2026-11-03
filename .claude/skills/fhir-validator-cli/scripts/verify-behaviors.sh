#!/usr/bin/env bash
# Runs synthetic resources through validator_cli.jar and reports whether the
# behaviours this skill documents from source hold for that jar.
#
# Usage: verify-behaviors.sh /path/to/validator_cli.jar [work-dir]
#
# Needs network access for packages and tx.fhir.org. Uses a private user.home,
# so ~/.fhir is untouched. Expect several minutes on the first run.
set -uo pipefail

jar="${1:?usage: verify-behaviors.sh /path/to/validator_cli.jar [work-dir]}"
[[ -f "$jar" ]] || { echo "jar not found: $jar" >&2; exit 2; }
jar="$(cd "$(dirname "$jar")" && pwd)/$(basename "$jar")"
work="${2:-$(mktemp -d)}"
mkdir -p "$work/in" "$work/out" "$work/home"
cd "$work/in" || exit 2

narrative='"text":{"status":"generated","div":"<div xmlns=\"http://www.w3.org/1999/xhtml\">x</div>"}'
cat > enc-r4.json <<E
{"resourceType":"Encounter","id":"e1","status":"finished","class":{"system":"http://terminology.hl7.org/CodeSystem/v3-ActCode","code":"AMB"},$narrative}
E
cat > pat-bad.json <<E
{"resourceType":"Patient","id":"p1","gender":"robot",$narrative}
E
cat > pat-ok.json <<E
{"resourceType":"Patient","id":"p2","gender":"male",$narrative}
E
cat > pat-noid.json <<E
{"resourceType":"Patient","gender":"male",$narrative}
E
cat > pat-metaprofile.json <<E
{"resourceType":"Patient","id":"p3","meta":{"profile":["http://example.org/StructureDefinition/unknown"]},$narrative}
E
cat > obs-ref.json <<E
{"resourceType":"Observation","id":"o1","status":"final","code":{"text":"x"},"subject":{"reference":"Patient/nope"},$narrative}
E

passes=0 fails=0
vc() { java -Duser.home="$work/home" -Xmx3g -jar "$jar" "$@"; }

# check <name> <expected-exit|any> <log-regex|-> -- args...
check() {
  local name="$1" want_exit="$2" want_log="$3"; shift 4
  local log="$work/out/$name.log" got ok=1
  vc "$@" > "$log" 2>&1; got=$?
  [[ "$want_exit" == any || "$got" == "$want_exit" ]] || ok=0
  [[ "$want_log" == - ]] || grep -Eq -- "$want_log" "$log" || ok=0
  if (( ok )); then
    passes=$((passes + 1)); printf 'PASS  %-34s exit=%s\n' "$name" "$got"
  else
    fails=$((fails + 1)); printf 'FAIL  %-34s exit=%s (wanted exit %s, log /%s/) see %s\n' "$name" "$got" "$want_exit" "$want_log" "$log"
  fi
}

check no-version-plain-is-r5        any 'Loading FHIR v5\.0'  -- enc-r4.json -tx n/a
check explicit-version-r4           0   'Loading FHIR v4\.0'  -- enc-r4.json -version 4.0 -tx n/a
check core-ig-no-version-is-r5      any 'Loading FHIR v5\.0'  -- enc-r4.json -ig hl7.fhir.r4.core#4.0.1 -tx n/a
check us-core-ig-infers-r4          any 'Loading FHIR v4\.0'  -- pat-ok.json -ig hl7.fhir.us.core#6.1.0 -tx n/a
check core-ig-version-conflict      1   'inconsistent'        -- pat-ok.json -version 5.0 -ig hl7.fhir.r4.core#4.0.1 -tx n/a
check errors-exit-1                 1   -                     -- pat-bad.json -version 4.0
check clean-exit-0                  0   -                     -- pat-ok.json -version 4.0 -tx n/a
check unknown-option-exit-1         1   -                     -- pat-ok.json -version 4.0 -tx n/a -nonsense
check check-display-case-sensitive  1   -                     -- pat-ok.json -version 4.0 -tx n/a -check-display check
check id-rule-required              1   -                     -- pat-noid.json -version 4.0 -tx n/a -resource-id-rule required
check meta-profile-unknown-warns    0   -                     -- pat-metaprofile.json -version 4.0 -tx n/a
check cli-profile-unknown-fails     1   -                     -- pat-ok.json -version 4.0 -tx n/a -profile http://example.org/StructureDefinition/unknown
check bad-ref-ignored-by-default    0   -                     -- obs-ref.json -version 4.0 -tx n/a
check bad-ref-with-check-references 1   -                     -- obs-ref.json -version 4.0 -tx n/a -check-references
check empty-wildcard-exit-0         0   -                     -- './nomatch*.json' -version 4.0 -tx n/a
check show-message-ids-remapped     any 'no longer in use'    -- pat-ok.json -version 4.0 -tx n/a -show-message-ids
check preset-plus-version-fails     1   -                     -- pat-ok.json -ips -version 4.0 -tx n/a
check version-before-subcommand     any -                     -- -version 4.0 snapshot pat-ok.json -output "$work/out/ignored.json"
check doc-flag-check-references-to  1   -                     -- pat-ok.json -version 4.0 -tx n/a -check-references-to http://hapi.fhir.org
check doc-flag-lowercase-txcache    1   -                     -- pat-ok.json -version 4.0 -tx n/a -txcache "$work/out/tx"
check doc-flag-current              1   -                     -- pat-ok.json -version 4.0 -tx n/a -current
check native-is-a-stub              0   'not done yet'        -- pat-ok.json -version 4.0 -tx n/a -native
check repeated-language-fails       1   -                     -- pat-ok.json -version 4.0 -tx n/a -language fr -language de
check ig-url-as-profile-fails       1   -                     -- pat-ok.json -version 4.0 -tx n/a -ig hl7.fhir.us.core#6.1.0 -profile http://hl7.org/fhir/us/core

# Output format follows the extension.
vc pat-bad.json -version 4.0 -tx n/a -output "$work/out/oo.json" >/dev/null 2>&1
if head -c 1 "$work/out/oo.json" 2>/dev/null | grep -q '{'; then
  passes=$((passes + 1)); echo 'PASS  output-json-extension'
else
  fails=$((fails + 1)); echo 'FAIL  output-json-extension'
fi

echo
echo "passed=$passes failed=$fails logs=$work/out"
echo 'Update the skill for any FAIL. version-before-subcommand always passes; read its log to see which version loaded.'
(( fails == 0 ))
