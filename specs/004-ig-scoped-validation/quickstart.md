# Quickstart: Validating IG-Scoped Validation

**Feature**: `004-ig-scoped-validation`. Contracts are in [contracts/http-api.md](./contracts/http-api.md), and the
data model is in [data-model.md](./data-model.md).

## Prerequisites

- JDK 17, Maven, and Docker (for Testcontainers PostgreSQL).
- Test packages from `TestPackages` (synthetic IGs built in tests). For the manual run, `hl7.fhir.us.core#6.1.0` and
  `#7.0.0` must be stored, by registering them while a registry is reachable or by uploading the archives.

## 1. Automated checks

```bash
mvn verify                                   # unit + Testcontainers integration tests
mvn -Dtest='IgScopedValidationIT,OnDemandContextIT,ContextEvictionIT,IncludeExcludeIT,PackageInvalidationIT,RegisterLatestIT,MigrationUpgradeIT' verify
```

Expected: everything passes, with no `BlockedThreadChecker` warnings in the test logs.

| Scenario | Test (new or extended) | Spec |
|---|---|---|
| No `ig` → base context, IG-only profile issues absent | `IgScopedValidationIT` | US1-2, SC-002 |
| `ig=A` vs `ig=B` → only that IG's profiles apply | `IgScopedValidationIT` | US1-1, SC-001 |
| Unknown `ig` → 404, nothing built | `IgScopedValidationIT` | US1-5, SC-004 |
| Malformed `ig`, wrong FHIR version | `IgScopedValidationIT`, `IgReferenceTest` | US1-6, US1-7 |
| Versionless `ig` → highest stored, reported | `IgScopedValidationIT` | US1-10 |
| `profile` scoped to the chosen IG | `ProfileResolutionIT` (extended) | US1-8, US1-9 |
| 50 concurrent first requests → 1 build | `OnDemandContextIT` | US1-4, SC-003 |
| Build longer than the build wait → 503, later success | `OnDemandContextIT` (build hook) | FR-012 |
| LRU and memory eviction, 503 `insufficient-capacity`, 413 | `ContextEvictionIT`, `HeapGuardIT` (extended) | FR-015, FR-016, SC-008 |
| Include a second version → first not rebuilt | `IncludeExcludeIT` | US2-1, SC-005 |
| Include and exclude propagate to a second instance within 10 s, poll fallback | `ConfigPropagationIT` (extended) | US2-2, US2-6, SC-006 |
| Exclude without a key → 401, VALIDATE key → 403 | `ApiKeyAuthIT` (extended) | FR-026 |
| Replaced package → contexts invalidated on both instances | `PackageInvalidationIT` | FR-025 |
| Register latest: stored-latest, newer-in-registry, registry down, not listed | `RegisterLatestIT` (stub registry), `PackageLatestResolutionIT` | US3, SC-009 |
| 2.0.0 database with 2 included IGs → 2 pinned contexts after V7 | `MigrationUpgradeIT` (extended) | US4-1, SC-010 |
| Contract matches `openapi.yaml` | `ValidationApiContractTest`, `IgPackageApiContractTest` | FR-034 |

## 2. Manual end-to-end run

```bash
docker run -d --name fv-pg -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=fhir_validator -p 5432:5432 postgres:16
./mvnw -B package -DskipTests
FV_PG_PASSWORD=postgres FV_ADMIN_API_KEY=dev-admin java -jar target/fhir-validation-service-3.0.0.jar &
H='Authorization: Bearer dev-admin'
```

1. **Two versions side by side** (US2):

   ```bash
   curl -s -H "$H" -H 'Content-Type: application/json' -d '{"igPackageId":"hl7.fhir.us.core","igPackageVersion":"6.1.0"}' localhost:8880/R4/include-ig
   curl -s -H "$H" -H 'Content-Type: application/json' -d '{"igPackageId":"hl7.fhir.us.core","igPackageVersion":"7.0.0"}' localhost:8880/R4/include-ig
   curl -s localhost:8880/health | jq '.readiness.versions.R4.contexts[] | {key, kind, state}'
   ```

   Expected: two `pinned` contexts, both `ACTIVE`. The second include response lists both keys in `included`, and
   its `resolved` covers only 7.0.0's packages.

2. **Choose per request** (US1):

   ```bash
   curl -si -H 'Content-Type: application/fhir+json' --data @patient.json \
     'localhost:8880/R4/validate?ig=hl7.fhir.us.core%236.1.0&profile=http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient'
   curl -si -H 'Content-Type: application/fhir+json' --data @patient.json 'localhost:8880/R4/validate'
   ```

   Expected: the first response has `X-Validation-Context: hl7.fhir.us.core#6.1.0` and `igPackage` set. The second
   has `X-Validation-Context: base`, and no US Core issues appear.

3. **Versionless and unknown**:

   ```bash
   curl -s -H 'Content-Type: application/fhir+json' --data @patient.json 'localhost:8880/R4/validate?ig=hl7.fhir.us.core' | jq .igPackage   # "hl7.fhir.us.core#7.0.0"
   curl -s -H 'Content-Type: application/fhir+json' --data @patient.json 'localhost:8880/R4/validate?ig=no.such.ig%231.0.0' | jq .type      # "package-not-found" (404)
   ```

4. **Exclude** (US2-6):

   ```bash
   curl -s -X DELETE -H "$H" 'localhost:8880/R4/include-ig?ig=hl7.fhir.us.core%236.1.0'
   curl -s -X DELETE 'localhost:8880/R4/include-ig?ig=hl7.fhir.us.core%236.1.0' -o /dev/null -w '%{http_code}\n'   # 401
   ```

   Expected: 6.1.0 shows `kind: on-demand` in `/health`, and `?ig=hl7.fhir.us.core%236.1.0` still validates.

5. **Register latest** (US3): with only an older version stored, run
   `curl -s -H "$H" -H 'Content-Type: application/json' -d '{"name":"hl7.fhir.us.core"}' localhost:8880/igs/register`.
   Expected: `version` is the registry's newest release and `downloaded: true`. Repeat the call: `downloaded: false`.
   Then restart with `FV_PACKAGE_REGISTRY_ENABLED=false` and call again: the highest stored version comes back with a
   warning.

## 3. Performance evidence for the PR (Principle VI, SC-007)

There is no load-test script in `scripts/` yet. Add a small one, for example `scripts/bench-validate.sh` using `hey`
or `wrk`, and run it against the 2.0.0 tag and against this branch for single-resource R4 validation, (a)
without `ig` and (b) with `ig` naming a pinned IG. Attach p50, p95 and p99 for each. Expected: p95 stays under
200 ms, and the difference is within the noise of the runs.
