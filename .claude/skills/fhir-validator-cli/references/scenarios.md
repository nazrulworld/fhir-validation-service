# Scenarios

Each scenario gives a command and what it changes in the chain. Section numbers refer to `validation-chain.md`. The commands were derived from source and not run, so confirm them on a sample first.

Contents: base resource · version not stated · IG profile · several profiles · meta.profile only · local profiles · Bundles · IPS · terminology modes · offline · references · Questionnaire · CI · server · suppressing issues · troubleshooting.

## Base resource, known version

```bash
java -jar validator_cli.jar patient.json -version 4.0.1
```

Loads R4 core, `hl7.terminology` and extensions. Runs the base definition, plus any `meta.profile`, then the Patient checks. Uses the network for packages and terminology.

## Version not stated

```bash
java -jar validator_cli.jar patient.json
```

Scan finds nothing in a plain instance, so it uses R5 (section 3). The usual symptom is structural errors on valid R4 content, such as `Encounter.class`, which changed shape in R5. Fix it with `-version`.

## Validate against an IG profile

```bash
java -jar validator_cli.jar patient.json \
  -version 4.0.1 \
  -ig hl7.fhir.us.core#6.1.0 \
  -profile http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient
```

Package and non-core dependencies load before validation. Validation runs against the profile, and `start` also covers `meta.profile`. Global profiles from a package are not applied (section 5), so name each profile you need. Pin the version, because an unpinned `-ig` follows the cache or latest release.

## Several profiles

```bash
java -jar validator_cli.jar obs.json -version 4.0.1 -ig ig-a#1.0.0 -ig ig-b#2.0.0 \
  -profile http://example.org/StructureDefinition/a \
  -profile http://example.org/StructureDefinition/b
```

`validateResource` runs once for each profile, so the resource must meet **all** of them, and messages are merged and deduplicated.

## Only meta.profile is present

```bash
java -jar validator_cli.jar claims-us-core.json -version 4.0.1 -ig hl7.fhir.us.core#6.1.0
```

Handled in `start`, step 3. If the profile is missing because the `-ig` was forgotten, you get a **warning** and exit 0, with no profile validation at all (section 8). For enforcement, add `-profile`, which fails hard when unresolved.

## Local profiles under development

```bash
java -jar validator_cli.jar instance.json -version 4.0.1 \
  -ig ./fsh-generated/resources \
  -ig hl7.fhir.us.core#6.1.0 \
  -profile http://example.org/StructureDefinition/my-profile \
  -watch-mode all
```

Folder resources load into the context, snapshots are generated, and watch mode revalidates on save. Add `-recurse` for subfolders. Use `[4.0]./folder` if a folder's version is ambiguous.

Quick alternative: list the profile as a source too, since round 1 loads sources as dependencies (section 6). The file is then validated as well.

## Bundle with per-entry profiles

```bash
java -jar validator_cli.jar bundle.json -version 4.0.1 -ig my.ig#1.0.0 \
  -profile http://example.org/StructureDefinition/my-bundle \
  -bundle Composition:0 http://example.org/StructureDefinition/my-composition \
  -bundle Patient http://example.org/StructureDefinition/my-patient
```

Rules apply during `BundleValidator` (section 12). Note that a `-profile` for a non-Bundle type, given a Bundle, silently applies to the **first entry only** (section 8). Use `-bundle` to target entries deliberately.

## IPS document

```bash
java -jar validator_cli.jar ips.json -ips
java -jar validator_cli.jar ips.json '-ips#2.0.0'
```

Preset expansion happens before parsing, and includes `-version 4.0`, so do not repeat it. Quote `#` and `$` forms for the shell.

## Terminology modes

| Goal | Command | Effect |
|---|---|---|
| Default ecosystem | no flag | Routes through the registry; needs internet. |
| Own server only | `-tx https://tx.example.org` | All requests there, ecosystem off. |
| Own server, keep routing | `-tx https://tx.example.org -ecosystem https://ecosystem.hl7.org/tx-reg` | Master only. |
| Local or internal server | `-tx http://tx.internal:8080/fhir -fhir-settings settings.json`, with a `servers` entry using `allowHttp` and `allowPrivateNetwork` | Without the entry, SSRF rules block it. Includes `localhost`. See `validate-options.md`. |
| None | `-tx n/a` | Fast and offline-friendly. External codes are not validated, and some slicing may fail. |
| Reproducible | `-txCache ./tx-cache -clear-tx-cache` | Isolated cache, cleared first. |
| Debug routing | `-tx-routing -txLog tx.log` | Shows which server answered. |
| Fixed versions | `-expansion-parameters exp.json` | Pins code system versions. |
| SNOMED edition | `-sct us` | Sets the edition for SNOMED lookups. |

## Air-gapped run

```bash
# online, once
java -jar validator_cli.jar install -version 4.0.1 -ig hl7.fhir.us.core#6.1.0
# offline
java -jar validator_cli.jar patient.json -version 4.0.1 \
  -ig hl7.fhir.us.core#6.1.0 -tx n/a -no-http-access
```

`install` fills `~/.fhir/packages`, and `-no-http-access` prohibits any fetch. Also cache the core, `hl7.terminology` and extensions packages, which the online run does as a side effect. Any missing package fails the run, which is the point.

## Checking references

```bash
java -jar validator_cli.jar obs.json -version 4.0.1 \
  -check-references -resolution-context file:./fixtures
```

Policy becomes `CHECK_VALID` (section 11). `Patient/1` is looked for as `fixtures/Patient/1`, `.json`, `.xml`, then `Patient-1.json` or `.xml`, then by scanning. Without a context, relative targets produce errors. For a server, use `-resolution-context https://fhir.example.org`.

## Questionnaire

```bash
java -jar validator_cli.jar qr.json -version 4.0.1 -ig ./questionnaires \
  -questionnaire required -launch-context patient:patient.json
```

`required` errors if there is no questionnaire. The default `check` only validates if the Questionnaire can be found.

## CI gate

```bash
set -euo pipefail
shopt -s nullglob
files=(resources/*.json)
(( ${#files[@]} > 0 )) || { echo "no resources found"; exit 2; }

java -jar validator_cli.jar "${files[@]}" \
  -version 4.0.1 \
  -ig hl7.fhir.us.core#6.1.0 \
  -jurisdiction uv \
  -txCache .tx-cache \
  -output-style eslint-compact -output results.txt \
  -level warnings
```

Guards against the empty-match exit 0, and fixes the jurisdiction so results do not depend on the runner's locale. Pin the jar, IGs and FHIR version, and record the jar version, since rules tighten between releases. Upgrade the jar deliberately: upstream only supports current versions, and old ones may stop working. Keep an eye on `-max-validation-messages` and `-validation-timeout`, which can truncate silently. Use your own `-tx` for pipelines, as `tx.fhir.org` has no SLA.

## Server for many validations

```bash
java -jar validator_cli.jar server 8080 -version 4.0.1 -ig hl7.fhir.us.core#6.1.0 -txCache ./tx-cache
java -jar validator_cli.jar client -port 8080 -level errors patient.json
java -jar validator_cli.jar client -port 8080 -stop
```

Startup cost is paid once. Only instance options vary per call. Engine choices such as FHIR version, IGs and terminology need a restart or `/loadIG`. Do not rely on instance flags given at server startup.

## Suppressing known issues

```bash
java -jar validator_cli.jar patient.json -version 4.0.1 -verbose   # find ids
java -jar validator_cli.jar patient.json -version 4.0.1 -advisor-file suppress.txt
```

Scope rules with `id@path` so they do not hide new problems. Keep the file in version control with reasons in comments. Upstream intends this for managed pipelines facing new errors after an upgrade: the hidden problems still exist, so the resources are not valid.

## Troubleshooting

| Symptom | Likely cause | Action |
|---|---|---|
| Valid R4 reports structural errors | Ran as R5 | Add `-version`; check the `Loading FHIR v` log line |
| Clean result but profile "ignored" | `meta.profile` unresolved, so only a warning | Add `-ig`, or `-profile` |
| `Unable to resolve profile` | Profile not loaded | Add its `-ig`, or check the canonical URL |
| Unknown extension error | Extension definition not loaded | Add the defining IG; `-extension <domain>` only as a stopgap |
| Works online, fails offline | Terminology or package gap | Run `install`; use a dedicated `-txCache` |
| Different results across machines | Unpinned IG, different cache or jar | Pin all; compare `Package Summary` log lines |
| Non-existent reference passes | Default `IGNORE` policy | Add `-check-references` |
| Local or internal `-tx` blocked | SSRF rules | Add a `servers` entry with `allowPrivateNetwork`, plus `allowHttp` for plain HTTP |
| `http://` server contacted as `https://` | Automatic upgrade | Add `allowHttp` for that server |
| Unknown option error for a documented flag | Flag is on the Confluence page but not in the CLI | See `docs-vs-source.md` |
| Fails on one machine only | Jurisdiction from locale | Set `-jurisdiction uv` or `-locale` |
| `-native` shows "not done yet" | Stub | Remove it |
| `-check-display` changes nothing | Value is not read | Use `-display-issues-are-warnings` if needed |
| Exit 0 with nothing validated | Empty wildcard | Add a file-count guard |
| Server ignores `-profile` | Startup instance options unused | Send `profile` on the request |
| `Parameters are inconsistent` | `-version` conflicts with a core `-ig` | Make them match or remove the core `-ig` |
| `Multiple versions found` | Mixed IG versions, no `-version` | Add `-version` |
| Slow startup | Package loading and snapshots | Use `server`; reuse the package cache |
| Out of memory | Large IG sets | Raise `-Xmx` |
