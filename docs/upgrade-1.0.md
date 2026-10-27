# Upgrading to 1.0.0

1.0.0 removes the PostgreSQL server that earlier images bundled inside the application container. Data now lives in
a separate PostgreSQL service (the `postgres` service of `compose.yaml`, or your own database). This guide moves
the data of a 0.1.x installation (uploaded IG packages, custom profiles, included-IG configuration) into it.

> **Running 0.1.x and 1.0.0 against the same database at the same time is not supported.** Stop every 0.1.x
> instance first. The upgrade has a short planned downtime. Zero-downtime rolling restarts apply to 1.x → 1.x only.

Skipping this guide gives an empty but working 1.0.0 service: IGs and profiles would have to be registered again.

## 0. Prepare

- A shell with Docker and Compose v2, in a checkout of 1.0.0 (or the release assets `compose.yaml` + `.env.example`).
- `cp .env.example .env`, then set `PG_PASSWORD` and `FV_ADMIN_API_KEY` (32–128 characters of `[A-Za-z0-9_-]`).
  **Use a new database password.** The old `.env` was committed to git with `PG_PASSWORD=password`; treat that value
  as public.
- The name of the old data volume, usually `fhir_pgdata`: `docker volume ls | grep pgdata`
- The old image tag, e.g. `ghcr.io/nazrulworld/fhir-validation-service:0.1.2` (pull it if it is not local).

```bash
OLD_IMAGE=ghcr.io/nazrulworld/fhir-validation-service:0.1.2
OLD_VOLUME=fhir_pgdata
```

## 1. Stop all 0.1.x instances

In the old installation directory: `docker compose down` (this keeps the volume). Make sure no other 0.1.x
container uses the volume.

## 2. Record row counts and dump the old database

The old image contains PostgreSQL 16 and owns the data directory, so use it (with its entrypoint overridden) to
read the volume:

```bash
old_db() {  # run a command against the old database, then stop it
  docker run --rm -i --entrypoint bash -v "$OLD_VOLUME":/var/lib/postgresql/16/main "$OLD_IMAGE" -c \
    "gosu postgres /usr/lib/postgresql/16/bin/pg_ctl -D /var/lib/postgresql/16/main -w -l /tmp/pg.log start >/dev/null && $1"
}

COUNT_SQL="SELECT 'igs', count(*) FROM fhir_validator_schema.fhir_implementation_guides
     UNION ALL SELECT 'profiles', count(*) FROM fhir_validator_schema.fhir_profiles
     UNION ALL SELECT 'configs', count(*) FROM fhir_validator_schema.fhir_validator_logs ORDER BY 1"

old_db "gosu postgres psql -At -d fhir_validator -c \"$COUNT_SQL\"" | tee counts-before.txt
old_db "gosu postgres pg_dump -Fc -d fhir_validator" > fhir_validator.dump
ls -l fhir_validator.dump   # must not be empty
```

## 3. Start the new database and restore

```bash
docker compose up -d --wait postgres
docker compose exec -T postgres pg_restore -U postgres -d fhir_validator --no-owner --clean --if-exists < fhir_validator.dump
```

## 4. Verify the counts

```bash
docker compose exec -T postgres psql -U postgres -At -d fhir_validator -c "$COUNT_SQL" | tee counts-after.txt
diff counts-before.txt counts-after.txt && echo "counts match"
```

Do not continue unless the counts match.

## 5. Start 1.0.0

```bash
docker compose up -d --wait
curl -fsS http://127.0.0.1:8880/health/readiness
```

On first start, Flyway applies migrations V3 and V4: longer package versions, configuration revisions, API keys,
and a repair of JSON documents that earlier releases stored as JSON strings. The bootstrap admin key from
`FV_ADMIN_API_KEY` is installed. Administrative calls now need `Authorization: Bearer <FV_ADMIN_API_KEY>`.

## 6. Check behaviour

Validate a resource that uses one of your IGs or profiles, and compare it with the old service. Results can differ
where 1.0.0 fixes bugs, for example custom profiles that 0.1.x skipped on first use.

## Rollback

- Keep the old volume (`$OLD_VOLUME`) until 1.0.0 is verified. Steps 2–5 never write to it.
- 0.1.x cannot run against the migrated (V3/V4) database. To roll back, stop 1.0.0 (`docker compose down`) and start
  the old installation again on its untouched volume.
- Once satisfied, remove the old volume: `docker volume rm "$OLD_VOLUME"`.

## Rehearsal

`scripts/upgrade-rehearsal.sh` runs this guide end to end against a throwaway 0.1.2 installation and checks that the
counts match. It also checks that a registered profile is still in effect: the dk-core Patient profile is registered
on 0.1.2, a complete dk Patient and one without `identifier` are validated against it before the dump and after the
upgrade, and the error-level issues must be the same (FHIRPath character positions aside), including the
profile-only "Patient.identifier: minimum required = 1" for the variant. Run it before upgrading production; CI runs it
when the CI workflow is started manually with `upgrade-rehearsal` enabled.

Last result (2026-10-27, CI run 40552529996 on amd64 and a local run on Apple silicon, 0.1.2 image against 1.0.0
including migration V5): counts 2 IGs, 1 profile, 3 configurations before and after; dk profile in effect on both versions with identical
error-level issues (13 for the complete Patient, 11 for the variant); `REHEARSAL OK`.
