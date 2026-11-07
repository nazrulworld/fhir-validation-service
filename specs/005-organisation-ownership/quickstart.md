# Quickstart: Validating Organisation Ownership

**Feature**: `005-organisation-ownership` | Contracts: [http-api.md](./contracts/http-api.md),
[cli.md](./contracts/cli.md) | Model: [data-model.md](./data-model.md)

## 1. Automated checks

```bash
./mvnw verify            # unit + Testcontainers ITs (needs Docker)
```

| Spec item | Test (new unless marked) | Proves |
|---|---|---|
| FR-010, FR-011 | `core/security/AccessPolicyTest` | owner, other organisation, system, anonymous × shareable and private; dependency set rule |
| FR-012, R5 | `web/OwnershipValidationIT` | invisible `ig` gives the same status, type and text as not stored; versionless resolves to the highest visible version; FHIR-version error never shown for invisible; 424 without a dependency name |
| FR-013, SC-003, SC-004 | `core/validator/OwnershipOnDemandIT` | 0 builds for a refused caller; an A-built private snapshot is never served to B |
| FR-014, SC-005 | `web/ApiKeyAuthIT` (extended) | inactive organisation gives 403 within the TTL; reactivation restores; `Caller` set for keys and anonymous |
| FR-016, FR-017 | `core/npm/PackageOwnershipIT` | register and upload set owner and flag (default shareable); dependencies too; stored version unchanged; 409 on another organisation's `name#version`; concurrent store race |
| FR-018, US2 | `core/validator/IncludeOwnershipIT` | include owner and flag; already included by another organisation (visible: 200, owner kept; private: 404); private only for the package owner; exclude by non-owner gives 404 |
| FR-019 | `web/AccessChangeIT` | both PATCH operations; transfer by the non-system owner gives 403, by a non-owner 404; propagation to a second instance under 10 s with no rebuild (`ConfigPropagationIT`-style two instances) |
| FR-020 | `web/ValidationApiContractTest`, `IgPackageApiContractTest` (extended) | `owner`/`shareable` fields and `X-Validation-Context-Owner`/`-Shareable` headers match `openapi.yaml` |
| FR-021 | `web/endpoint/HealthApiTest` (extended) | private contexts only counted; no owners |
| FR-022 to FR-026 | `cli/AdminCliIT`, `cli/CliArgsTest` | file plus flag merge; exit codes 0/2/3/4; key printed once and stored hashed; system organisation protections; JSON only on stdout; no key in logs (`NoSecretsInLogsTest` extended) |
| FR-003, FR-004, FR-009, SC-001 | `core/service/MigrationUpgradeIT` (extended) | V8 on an empty database and on a 3.0.0 database: one system organisation, all rows owned by it and shareable, child rows backfilled; env values applied at start |
| US4 | the whole existing suite unchanged | defaults preserve 3.0 behaviour |
| SC-007 | `scripts/bench-validate.sh` | p95 before and after on a built context (result in the PR) |

## 2. Manual end-to-end (local compose)

**Prerequisites**: `make up` (PostgreSQL and service), `FV_ADMIN_API_KEY` set, and a small IG package archive
named `acme.core-1.0.0.tgz` (any IG works; replace the name in the commands below).

```bash
APP="docker compose run --rm validator fv-admin"   # or: java -jar target/fhir-validation-service-3.1.0.jar
$APP org create --name-short acme --name-long "Acme" --type COMMERCIAL --country DK     # exit 0, JSON
$APP org create --name-short beta --name-long "Beta" --type NON_COMMERCIAL             # exit 0
ACME_ADMIN=$($APP key create --org acme --name acme-admin --role ADMIN | jq -r .key)
BETA_VAL=$($APP key create --org beta --name beta-val --role VALIDATE | jq -r .key)
```

1. **Private upload**:
   `curl -H "Authorization: Bearer $ACME_ADMIN" -F file=@acme.core-1.0.0.tgz -F shareable=false :8880/igs/upload`
   → 200, `owner: "acme"`, `shareable: false`.
2. **Owner validates**: `POST /R4/validate?ig=acme.core%231.0.0` with an Acme key → 200, header
   `X-Validation-Context-Owner: acme`.
3. **Other organisation refused**: the same request with `$BETA_VAL` → 404 `package-not-found`. The body equals the
   one for `?ig=does.not.exist%231.0.0`.
4. **Anonymous refused**: the same request without a key → 404.
5. **Share it**: `curl -X PATCH -H "Authorization: Bearer $ACME_ADMIN" -d '{"shareable":true}'
   :8880/igs/acme.core/1.0.0` → 200. Within 10 s, step 3 returns 200.
6. **Transfer is system-only**: PATCH with `{"owner":"beta"}` using `$ACME_ADMIN` → 403. With `$FV_ADMIN_API_KEY`
   → 200, `owner: "beta"`.
7. **Upload conflict**: Beta uploads an archive with the same `acme.core#1.0.0` (now owned by Beta). Acme's upload →
   409 `conflict`.
8. **Deactivate**: `$APP org deactivate --name-short acme`. Within 30 s, any call with `$ACME_ADMIN` → 403.
   `$APP org activate --name-short acme` restores it.
9. **System protection**: `$APP org deactivate --name-short system` → exit 4.
10. **Health**: `GET /health` lists no private context keys and no owners.

Expected: every step matches. A difference is a defect.

## 3. Benchmark (SC-007)

```bash
sh scripts/bench-validate.sh http://localhost:8880 'acme.core#1.0.0'   # on main and on the branch (add key support
                                                                         # to the script if anonymous validation is off)
```

Record p50 and p95 in the PR. p95 must stay under 200 ms, with no measurable regression.
