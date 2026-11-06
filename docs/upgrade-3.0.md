# Upgrading to 3.0.0

> **⚠️ Requests without `ig` now use the core specification only.**
>
> In 2.x, a validation request was checked against every IG included for its FHIR version. In 3.0.0 each request is
> validated against **one** IG package that it names with `?ig=name#version` (plus that IG's dependencies and the core
> specification), or against the core specification alone when it names none. Clients that relied on included IGs
> without naming one must add `ig` to keep their results. Nothing is lost from the database.

3.0.0 follows the HL7 validator CLI's `-ig` model per request: `validator_cli.jar -version 4.0.1 -ig
hl7.fhir.us.core#6.1.0` becomes `POST /R4/validate?ig=hl7.fhir.us.core%236.1.0`. Different requests can use
different IGs, or different versions of one IG, in the same deployment without affecting each other.

Order of steps: **back up → stop every 2.x instance → start 3.0.0 → verify → update clients**.
`scripts/upgrade-rehearsal-3.0.sh` runs this guide end to end against a throwaway 2.0.0 installation.

## 1. Back up the database (mandatory)

```bash
docker compose exec -T postgres pg_dump -Fc -U postgres -d fhir_validator > fhir_validator-pre-3.0.dump
```

Restoring this backup is the **only** way back to 2.x (see step 6). There is no down-migration.

## 2. Stop every 2.x instance

```bash
docker compose stop validator        # and validator-2 / any other instance on this database
```

**Running 2.x and 3.0.0 against the same database at the same time is not supported.** Migration V7 drops
`fhir_validator_logs.resolved_ig_packages`, which 2.x reads, so a 2.x instance cannot load its configuration once V7
has run. Do **not** use `scripts/rolling-restart.sh` for this upgrade; it refuses to cross a major version. The
upgrade has a short planned downtime.

## 3. Start 3.0.0

```bash
FV_TAG=3.0.0 docker compose up -d --wait
```

The first start applies V7:

- every IG already included for a FHIR version stays included and becomes its **own pinned context**, pre-built on
  every instance (2.x combined them into one validator per version);
- the combined `resolved_ig_packages` column is dropped (each instance now resolves every included IG from the stored
  packages);
- a single-row `fhir_package_catalog` table is added, whose revision tells instances when stored packages change.

Stored packages, the included-IG configuration, its revision and API keys are kept.

## 4. Verify

```bash
curl -s http://127.0.0.1:8880/health | jq '.readiness.versions.R4.contexts[] | {key, kind, state}'
curl -si -H 'Content-Type: application/fhir+json' --data @patient.json \
  'http://127.0.0.1:8880/R4/validate?ig=<an included name>%23<version>' | grep -i x-validation-context
```

Every previously included IG is listed with `kind: "pinned"` and `state: "ACTIVE"`, and the validation answers with
`X-Validation-Context: <name#version>`.

## 5. Update clients

- **Name the IG on every request that needs one**: `?ig=name#version`. URL-encode `#` as `%23`: an unencoded `#` is
  cut off by the client, and the bare name then resolves to the highest stored version (the response reports which).
  `?ig=name` or `?ig=name#latest` deliberately uses the highest stored version.
- **Read the context back**: every 200 carries `X-Validation-Context` (`name#version`, or `base`); the JSON response
  has `igPackage` (`null` for the base context). The OperationOutcome body is unchanged.
- **`?profile=`** now resolves only from the chosen IG, its dependencies and the core specification. A profile from
  another IG, even an included one, answers 400 `unknown-profile`.

## 6. Rollback

Stop 3.0.0, restore the backup from step 1 into an empty database, and start 2.0.0:

```bash
docker compose stop validator
docker compose exec -T postgres dropdb -U postgres --force fhir_validator
docker compose exec -T postgres createdb -U postgres fhir_validator
docker compose exec -T postgres pg_restore -U postgres -d fhir_validator --no-owner < fhir_validator-pre-3.0.dump
FV_TAG=2.0.0 docker compose up -d --wait
```

A 2.x instance pointed at an upgraded (V7) database is not supported.

## What else changed

- **Include adds, it no longer replaces.** `POST /{version}/include-ig` for `P#2` when `P#1` is included keeps both,
  each pre-built and usable through `ig`. The response has a `key {fhirVersion, igPackage}`, and `resolved` lists only
  that IG's packages.
- **New: exclude.** `DELETE /{version}/include-ig?ig=name#version` (ADMIN key) stops pre-building one IG version on
  every instance. The package stays stored and can still be named in `ig`; it is then built on demand.
- **Any stored package can be named in `ig`**, included or not. A package that is not included is built on the first
  request that names it, from stored packages only (nothing is downloaded). Including a package only pre-builds and
  pins it.
- **New errors on validate:** 404 `package-not-found` (the `ig` package is not stored), 424 `dependency-not-found` (a
  dependency of it is not stored), 400 `invalid-request` (malformed `ig`, or a package for another FHIR version), 503
  `insufficient-capacity` (no memory left next to the pinned contexts; `Retry-After: 30`), 413 `package-too-large` (the
  context can never fit on this instance), and 503 `not-ready` while an `ig` context is still being built
  (`Retry-After: 5`).
- **`POST /igs/register` with `latest`** (or no version) now asks the package registries for the newest release and
  downloads it if it is new. If no registry can be consulted, or none lists the package, it returns the highest stored
  version with a warning. The response has `downloaded` and `warnings`.
- **Replacing a stored package** (same `name#version`, new content) now invalidates every validator built from it, on
  every instance. 2.x kept validating with the old content.
- **`/health` details** changed shape: per version, `base` and a `contexts` list (`key`, `kind` pinned|on-demand,
  `state`, `packages`, `packageBytes`, `lastUsed`, `reason`), plus a top-level `catalogRevision`. Readiness counts the
  base and pinned contexts only.
- **New settings:** `FV_IG_MAX_ON_DEMAND` (default 4, range 1–64): most on-demand contexts per instance, least recently
  used evicted first. `FV_IG_BUILD_WAIT_MS` (default `min(10000, FV_HTTP_REQUEST_TIMEOUT_MS / 2)`, range 1000–120000):
  how long a request waits for an on-demand build before answering 503 while the build continues.
- **Metrics:** `fv_validator_contexts{fhir_version,kind}`, `fv_validator_context_builds_total{fhir_version,kind,outcome}`,
  `fv_validator_context_evictions_total{fhir_version,reason}`, `fv_validator_context_lookups_total{fhir_version,result}`,
  and `fv_validations_total` outcome `package_not_found`. `fv_snapshot_packages` and `fv_snapshot_package_size_bytes`
  now sum the base and pinned contexts of a version.

## Sizing

Each pinned IG now holds its own copy of its dependencies' definitions; in 2.x, IGs that shared dependencies shared one
validator. Plan memory for one context per included IG, plus up to `FV_IG_MAX_ON_DEMAND` on-demand contexts. The heap
check still refuses a build that would not fit: an include answers 413 before it is committed, and on-demand contexts
are evicted (least recently used first) before a new one is refused. Raise `FV_MEMORY_LIMIT` together with
`FV_IG_MAX_ON_DEMAND`.
