# Upgrading to 2.0.0

> **⚠️ Export first: this upgrade permanently deletes custom profiles.**
>
> 2.0.0 removes `POST /{version}/register-profile`. On its first start it runs database migration V6, which **drops
> the `fhir_profiles` table unconditionally**, even when it still holds profiles. There is no confirmation step and no
> way to recover them from the upgraded database. If you ever registered a profile, export it with step 2 **before**
> you start 2.0.0. If you have none, the upgrade loses nothing.

In 2.0.0, profiles come only from IG packages that are included for a FHIR version, the same model as the HL7
validator CLI's `-ig` and `-profile`. A custom profile becomes part of an IG package that you upload and include.
Profiles are never downloaded from their canonical URL.

Order of steps: **check → export → back up → stop every 1.x instance → start 2.0.0 → upload and include → verify**.
`scripts/upgrade-rehearsal-2.0.sh` runs this guide end to end against a throwaway 1.2.0 installation.

## 0. Prepare

- A checkout of 2.0.0 (for `scripts/export-custom-profiles.sh`), `psql`, `tar` and `gzip`.
- Network access from that shell to the **1.x database**. With the bundled compose stack the database is not
  published, so either run the commands inside the `postgres` container or publish it on loopback temporarily
  (`ports: ["127.0.0.1:54329:5432"]` in `compose.override.yaml`).
- Connection settings as libpq variables, or the `FV_PG_*` values the service already uses:

```bash
export PGHOST=127.0.0.1 PGPORT=54329 PGDATABASE=fhir_validator PGUSER=postgres
export PGPASSWORD=...      # or use ~/.pgpass; the script never takes a password argument
```

## 1. Check whether you have custom profiles

```bash
scripts/export-custom-profiles.sh --check
```

```text
4.0.1 2
total 2
```

`total 0` means there is nothing to export: skip to step 3.

## 2. Export them as IG packages

```bash
scripts/export-custom-profiles.sh --out ./custom-profiles-export \
  --dependency hl7.fhir.dk.core#3.4.0     # every IG your profiles build on, see below
```

This writes one uploadable package per FHIR version, for example
`custom-profiles-export/local.custom.profiles.r4-1.0.0.tgz`, plus `MANIFEST.txt` listing each archive and profile
URL. Check that the manifest has as many lines as `--check` reported.

- **`--dependency NAME#VERSION`** (repeatable): if a profile derives from, or uses extensions and value sets of, an IG
  profile (for example a dk-core profile), name that IG. When the package is included, its dependencies are resolved
  and included with it. Without it, snapshot generation and validation may fail for those profiles.
- `--name` and `--package-version` change the generated package name (default `local.custom.profiles.<release>`) and
  version (default `1.0.0`).
- Profiles stored under a FHIR version 2.0.0 does not support are written unpackaged to `unsupported-<version>/`, and
  a warning is printed.
- If a package the export would generate (for example `local.custom.profiles.r4#1.0.0`) is already stored, the export
  stops with exit code 1 and writes nothing. Uploading a package with an existing name and version replaces the stored
  one without warning, so choose another `--name` or `--package-version` and run the export again.
- Exit code 3 means `fhir_profiles` no longer exists: the database was already upgraded. Restore the backup (step 8)
  to get the profiles back.

Keep the export somewhere safe. The upgrade deletes the only other copy.

## 3. Back up the database (mandatory)

```bash
docker compose exec -T postgres pg_dump -Fc -U postgres -d fhir_validator > fhir_validator-pre-2.0.dump
```

Restoring this backup is the **only** way back to 1.x (see step 8). There is no down-migration.

## 4. Stop every 1.x instance

```bash
docker compose stop validator        # and validator-2 / any other instance on this database
```

**Running 1.x and 2.0.0 against the same database at the same time is not supported.** Once V6 has run, 1.x
instances can no longer load their configuration. Do **not** use `scripts/rolling-restart.sh` for this upgrade. It
refuses to cross a major version. The upgrade has a short planned downtime.

## 5. Start 2.0.0

```bash
FV_TAG=2.0.0 docker compose up -d --wait
```

The first start applies V6. It drops `fhir_profiles` and the never-used `fhir_validator_logs.included_profiles` column,
and keeps IG packages, included-IG configuration and API keys.

## 6. Upload and include the exported packages

```bash
AUTH="Authorization: Bearer $FV_ADMIN_API_KEY"
curl -H "$AUTH" -F "file=@custom-profiles-export/local.custom.profiles.r4-1.0.0.tgz;type=application/gzip" \
  http://127.0.0.1:8880/igs/upload
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"local.custom.profiles.r4","igPackageVersion":"1.0.0"}' http://127.0.0.1:8880/R4/include-ig
```

Repeat for each FHIR version you exported. The `--dependency` IGs must already be stored (`/igs/upload` or
`/igs/register`).

## 7. Verify

```bash
curl -s -H 'Content-Type: application/fhir+json' --data @patient.json \
  "http://127.0.0.1:8880/R4/validate?profile=<one of the exported profile URLs>" | jq '{valid, appliedProfiles}'
```

`appliedProfiles` lists the profile. A **400 `unknown-profile`** means its package is not included for that version.

## 8. Rollback

Stop 2.0.0, restore the backup from step 3 into an empty database, and start 1.2.0:

```bash
docker compose stop validator
docker compose exec -T postgres dropdb -U postgres --force fhir_validator
docker compose exec -T postgres createdb -U postgres fhir_validator
docker compose exec -T postgres pg_restore -U postgres -d fhir_validator --no-owner < fhir_validator-pre-2.0.dump
FV_TAG=1.2.0 docker compose up -d --wait
```

A 1.x instance pointed at an upgraded (V6) database is not supported.

## What else changed

- **`register-profile` is gone.** `POST /{version}/register-profile` answers 404. Package profiles as an IG, then
  upload and include it.
- **Unknown requested profiles fail the request.** If any URL in `?profile=` is not defined by the core specification
  or an included IG (and its dependencies), validation answers **400 `unknown-profile`** with `unresolvedProfiles`, and
  nothing is validated. 1.x silently dropped an unknown profile when another one resolved, and could answer
  `valid: true` without checking it.
- **Exact canonical match.** `?profile=` must be the profile's `url` or `url|version`. Bare ids and partial URLs no
  longer resolve.
- **Limits on `?profile=`.** At most 10 distinct profiles of at most 1024 characters each. Beyond that the answer is
  **400 `invalid-request`**.
- **`appliedProfiles` is truthful.** It lists only profiles that were resolved and applied: the requested ones first,
  then resolvable `meta.profile` entries.
- **Unchanged:** an unknown `meta.profile` inside the resource is still reported as an ERROR issue (200, `valid: false`).
- **Metrics:** `fv_profile_count` is removed, and `fv_validations_total` has a new `outcome="unknown_profile"` value.
  Update dashboards and alerts.
- **Health:** the readiness reason "profile changes could not be applied" no longer exists.
- **Admin operations** (`FV_ADMIN_MAX_PENDING`, the admin key) now cover upload, register and include-ig.
