# Quickstart: validating 4.2.0's version and access-control switches

**Feature**: `009-fhir-version-acl-toggles` | Contracts: [http-api.md](./contracts/http-api.md),
[operations.md](./contracts/operations.md) | Data: [data-model.md](./data-model.md)

## Prerequisites

- Docker (Testcontainers and a local PostgreSQL), JDK 17, Maven.
- `mvn -q -DskipTests package` builds `target/fhir-validation-service-4.2.0.jar`.
- A PostgreSQL for manual runs: `docker run -d --name fv-pg -e POSTGRES_PASSWORD=fv -p 5432:5432 postgres:16`, then
  `export FV_PG_HOST=localhost FV_PG_USER=postgres FV_PG_PASSWORD=fv FV_PG_DATABASE=postgres`.
- Two key values (32–128 characters of `[A-Za-z0-9_-]`):
  `export FV_ADMIN_API_KEY=$(openssl rand -hex 24) FV_VALIDATOR_API_KEY=$(openssl rand -hex 24)`.
- An R4 and an R5 IG package stored (e.g. `hl7.fhir.us.core#6.1.0` and an R5 IG), uploaded with the admin key as in
  README.

## Automated

```bash
mvn verify                                           # whole suite; must stay green with both settings unset (SC-001)
mvn -Dit.test='EnabledVersionsIT,ContextActivationIT,AclDisabledIT,AclModeSwitchIT' verify
mvn -Dtest='ConfigLoaderTest,ServedVersionsTest,ApiKeyAuthHandlerTest,NoSecretsInLogsTest' test
```

## Manual scenarios

Each scenario lists the spec acceptance items it proves.

### A. Only R4 is served (US1)

```bash
FV_ENABLED_FHIR_VERSIONS=r4 java -jar target/fhir-validation-service-4.2.0.jar &
curl -s localhost:8080/health | jq '.fhirVersions.served'                      # ["R4"]
curl -s localhost:8080/health/readiness | jq '.versions | keys'                # ["R4"]
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/R5/validate \
  -H "Authorization: Bearer $FV_VALIDATOR_API_KEY" -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Patient"}'
# 400 (unsupported-fhir-version)
```

`FV_ENABLED_FHIR_VERSIONS=R4,X1` → the process exits 1 with `Configuration error: … 'X1' …` (US1-4).

### B. Adding an R5 context is refused (US2)

```bash
curl -s -X POST localhost:8080/R5/validator -H "Authorization: Bearer $FV_ADMIN_API_KEY" \
  -H 'Content-Type: application/json' -d '{"igPackageId":"<r5 ig>"}' | jq '.status, .type'   # 422, "fhir-version-disabled"
psql -c "SELECT count(*) FROM fhir_validator_schema.fhir_validator_snapshots WHERE fhir_version='R5'"   # unchanged
```

POST `/R4/validator` for the R4 IG → 200, and the row has `is_active = true`.

### C. Deactivate and reactivate (US3)

1. Start with `FV_ENABLED_FHIR_VERSIONS=R4,R5` and include one R4 and one R5 context.
2. Restart with `R5` and `FV_FHIR_VERSIONS_CHANGE_CONFIRMED=true` (without it the start is refused, FR-033). The log shows one `Validator context deactivated: R4 …` WARN and a count line.
   `/health` → `fhirVersions.inactiveContexts.count = 1`. `psql` shows the R4 row with
   `is_active=false, inactive_reason='fhir-version-disabled'`.
3. While R4 is disabled: `PATCH /R4/validator?ig=…` with `{"shareable": false}` → 200; the row changes and stays
   inactive (US3-6).
4. Restart with `R4,R5` (confirmed again). You get the `reactivated` INFO, the R4 context is READY in `/health/readiness`, and
   `shareable=false` from step 3 is kept.

### D. Public mode (US4, US5)

```bash
FV_GLOBAL_DISABLED_ACL_SECURITY=yes FV_ACL_MODE_CHANGE_CONFIRMED=true java -jar … &
# Log: "ACCESS CONTROL IS DISABLED …", "FV_ADMIN_API_KEY is ignored …" (no values)
curl -s localhost:8080/health | jq '.security'                     # {"aclSecurity":"disabled"}
curl -s -X POST localhost:8080/R4/validator -H 'Authorization: Bearer garbage' \
  -H 'Content-Type: application/json' -d '{"igPackageId":"hl7.fhir.us.core"}' | jq '.owner'   # system organisation
curl -s -X POST localhost:8080/R4/validate -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Patient"}'  # 200
psql -c "SELECT api_key_id, org_id FROM fhir_validator_schema.validation_usage ORDER BY created_at DESC LIMIT 1"
# 00000000-0000-0000-0000-200000000002 / trial org
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/R4/validator -H "Authorization: Bearer $FV_ADMIN_API_KEY" -X DELETE
# works, but not because of the key: the header isn't read
```

The stored admin key hash ≠ `sha256($FV_ADMIN_API_KEY)` (US5-1). With `FV_AUTH_ADMIN_CIDRS=10.0.0.0/8` set, an admin
call from 127.0.0.1 → 403 (US4-7).

### E. Mode-switch guard and switching back (US6)

1. Restart with access control enabled and **without** the confirmation → exit 1, `access control mode change
   refused …`. `service_settings.acl_security` is still `disabled`.
2. Restart with the confirmation and both keys → `API keys re-synchronised from environment`. Admin calls work with
   `$FV_ADMIN_API_KEY`, and the context from D is reachable.
3. Restart with the same settings, confirmation still set → the `remove it` WARN, no resync line (US6-8).
4. Remove the confirmation and restart → no ACL lines at all (US6-3).

### F. Memory evidence (SC-002)

Start once with the variable unset and once with `R4`. After readiness, record `jvm_memory_used_bytes{area="heap"}`
after a forced GC (`jcmd <pid> GC.run`), and record the time to readiness for each run. Write both in
`perf/results-4.2.0.md`.
