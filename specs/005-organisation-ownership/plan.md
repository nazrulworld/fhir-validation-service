# Implementation Plan: Organisation Ownership (Tenants Own API Keys, IG Packages and Validator Snapshots)

**Branch**: `005-organisation-ownership` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/005-organisation-ownership/spec.md`

## Summary

Organisations become the owner of API keys, stored IG packages and validator snapshots. A caller can use a package
or snapshot when its organisation owns it, when it is shareable, or when the caller is the system organisation. For
a snapshot, every package in its resolved set must pass the same rule.

**Storage**:
- New table `organisations`, with exactly one built-in **system organisation** at a fixed UUID.
- `owner` on `api_keys` and `fhir_implementation_guides`, and `is_shareable` on packages.
- New child table `fhir_validator_snapshots` for per-snapshot ownership of included contexts. `fhir_validator_logs`
  is one row per FHIR version, so it can't hold per-snapshot ownership (research R1).
- Existing data defaults to system-owned and shareable, so single-tenant deployments see no change.

**Caller identity**: the auth handler resolves each key to a `Caller` (organisation, role, system or anonymous).
Deactivated organisations' keys get 403 within the existing auth-cache window.

**Access checks**: `AccessPolicy` checks validation in memory, after `ig` resolution and before the registry is
asked for a snapshot. It uses owner and flag data added to the `PackageCatalogView` and a per-version
`PinnedAccess` map. The check therefore always runs before an on-demand build and adds no database round trip.
Refusals look exactly like "not stored" (404, or 424 without naming the dependency), and versionless `ig` resolves
to the highest *visible* version.

**Writes**:
- register, upload and include record the caller's organisation as owner (shareable by default);
- uploads can't replace another organisation's package (409, enforced in the upsert itself);
- exclude is owner or system only.

**Changing access later**: two new PATCH operations, and matching command-line commands, change sharing (owner or
system) or transfer ownership (system only). They propagate through the existing catalog and per-version revisions;
a new `IG_ACCESS` notification kind swaps access without rebuilding.

**Command line**: the service jar gets `org`, `key`, `package` and `snapshot` commands. They take JSON file and
flag input, print JSON, return script-friendly exit codes, and work directly against the database with the
service's repositories.

**Release**: 3.1.0 (additive), migration `V8__organisations.sql`.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Vert.x 4.5.x (web, web-openapi, pg-client, config), HAPI FHIR 8.x, Flyway, Log4j2,
Micrometer Prometheus. No new dependencies; the command-line parser is written for this feature (research R9).

**Storage**: PostgreSQL, one Flyway migration `V8__organisations.sql` (data-model §1):
- new tables `organisations` and `fhir_validator_snapshots`;
- `owner` added to `api_keys` and `fhir_implementation_guides`, and `is_shareable` to `fhir_implementation_guides`;
- all with database-level defaults (system owner, shareable) for rolling-upgrade safety.

**Testing**: JUnit 5 and `vertx-junit5`; Testcontainers PostgreSQL (`BaseTestContainer`, `AppHarness`,
`CoreHarness`); `TestPackages` synthetic IGs; `StubRegistry` for register with dependencies; and two-instance
propagation tests in the `ConfigPropagationIT` style. The command line is tested in-process
(`AdminCli.run(args, stdout, stderr) → exitCode`) against Testcontainers.

**Target Platform**: Linux container (x86_64, arm64), JDK 17+ fat jar. The command line runs from the same jar or
image (through the `fv-admin` wrapper).

**Project Type**: single-module REST web service, plus an embedded admin command line.

**Performance Goals**:
- validation against a built context keeps p95 under 200 ms; the access check is a few in-memory hash lookups
  (SC-007, benchmark in the PR);
- deactivation is effective within `FV_AUTH_CACHE_TTL_MS` (default 30 s);
- access changes are effective within the 10 s propagation window.

**Constraints**:
- no database or network wait added to validation (FR-013);
- the check runs before build admission (FR-013);
- refusals can't be told apart from "not stored", including in metrics, because `/metrics` is open (research R5);
- curated errors (Principle IV);
- the command line never blocks an event loop; it waits on a latch in `main` (Principle I).

**Scale/Scope**:
- **New production classes, about 12**: `core.org.{Organisation, OrganisationRepository, Organisations}`,
  `core.security.{Caller, AccessPolicy, Access}`, `core.validator.PinnedAccess`, `core.npm.PackageOwnership`,
  `cli.{AdminCli, CliArgs, OrgCommands, KeyCommands, AccessCommands}`.
- **Changed classes, about 14**: `ApiKeyRepository`, `ApiKeyAuthHandler`, `PackageCatalogView`,
  `PostgresPackageCacheManager`, `IgPackageService`, `ConfigRevisionRepository`, `ConfigSync`, `ValidatorRegistry`
  (`details`), `FhirValidationService`, `IgReference`, `ValidationApi`, `IgPackageApi`, `OutcomeRenderer`/
  `ValidationResponse`, `ProblemException`, `ConfigLoader`/`ApplicationConfig`, `Bootstrap`, `ServiceRegistry`.
- **Tests**: about 9 new test classes and about 8 extended.
- **Docs**: `openapi.yaml`, `CHANGELOG.md`, `docs/upgrade-3.1.md`, README (organisations, keys, command line),
  `Dockerfile` (`fv-admin`), `.env.example` (`FV_SYSTEM_ORG_*`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Non-blocking event loop | **Pass.** Every new repository is pg-client `Future`-based. The access check is pure and in memory. The command line runs Flyway through `DatabaseService.migrate` (worker pool), and `main` waits on a `CountDownLatch`, never `.get()`/`.join()` and never on an event loop (the existing `Bootstrap.shutdown` pattern). |
| II. Stateless, horizontally scalable | **Pass.** PostgreSQL stays the source of truth: organisations, owners, flags and pinned snapshot access. The in-memory catalog view, `PinnedAccess` and auth cache are derived and rebuildable. Cross-instance consistency is spelled out (data-model §4): the catalog revision with `PACKAGE` notify for package access and organisation renames; the per-version revision with the new `IG_ACCESS` kind for snapshot access; the auth-cache TTL for key and organisation state; the poll fallback for missed notifications. No new infrastructure. |
| III. FHIR conformance | **Pass.** Validation semantics are unchanged. Access decides *whether* a context may be used, never what it reports. Dependency resolution is still `DependencyResolver`. |
| IV. Contract-first API | **Pass.** `openapi.yaml` changes land first (contracts/http-api.md): new optional request fields, new response fields and headers, two new PATCH operations, and documented 403/404/409/424 meanings. All additive, so a MINOR bump to 3.1.0 with a changelog entry and an upgrade note. Errors stay curated problem JSON. Non-visible content reuses the existing texts. |
| V. Test-first with real dependencies | **Pass.** Each requirement has a failing test first (quickstart §1), against Testcontainers PostgreSQL for repositories, migration, propagation and the command line. `AccessPolicyTest` is a pure unit test. No Mockito for I/O. |
| VI. Performance and capacity | **Pass.** Latency and memory impact are stated (research R14). The hot path changes (the access check), so a benchmark is required in the PR. No new caches; the existing bounds stay. Command-line pool size is fixed and small. |
| VII. Observability | **Pass.** Organisation and key changes are logged at INFO by the command line, without key values (`NoSecretsInLogsTest` extended). Refusal reasons go to DEBUG with the request id. No new metric label that would reveal private content (research R5). `FV_SYSTEM_ORG_*` comes through `ConfigLoader` and the environment. |
| VIII. Simplicity and dependencies | **Pass.** No new dependency (picocli rejected, research R9). Schema changes only through `V8__organisations.sql`. One child table instead of reshaping `fhir_validator_logs` (research R1). |
| Security and compliance | **Pass, with explicit fixes.** `ADMIN_PATHS` gains `/igs/{name}/{version}` (PATCH), with an auth regression test. The health details no longer list private contexts (research R12). Upload replacement across organisations is refused atomically (research R7). Keys are generated with `SecureRandom`, shown once and stored only as SHA-256 hashes. The `bootstrap` name stays reserved. |
| Technology constraints | **Pass (constitution 1.1.1).** The jar's entry point is `nzi.fhir.validator.Bootstrap`, which the 1.1.1 PATCH amendment now names (1.1.0 still named the `web.MainVerticle` removed in spec 001). `Bootstrap.main` dispatching the command line (T056) is within that wording. |

**Gate result**: PASS. No violations, and Complexity Tracking is empty.

**Re-check after Phase 1**: PASS. The design adds two tables and about 12 small classes, and no infrastructure or
dependencies. One noted deviation from the *input* (not the constitution): ownership of included snapshots lives in
`fhir_validator_snapshots`, not as columns on `fhir_validator_logs`, because that table is one row per FHIR version
(research R1). The spec (FR-008) prescribes no storage, so the spec is unaffected.

## Project Structure

### Documentation (this feature)

```text
specs/005-organisation-ownership/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0: R1–R14
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1: validation guide and test map
├── contracts/
│   ├── http-api.md      # Phase 1: openapi.yaml changes, notification kinds
│   └── cli.md           # Phase 1: command-line contract
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── Bootstrap.java                              # main(): dispatch to AdminCli; installSystemOrganisation before bootstrap key
├── ServiceRegistry.java                        # wire OrganisationRepository, PinnedAccess supplier, AccessPolicy use
├── cli/                                        # NEW
│   ├── AdminCli.java                           # run(args, out, err) → exit code; Vertx + pool + migrate; latch wait
│   ├── CliArgs.java                            # --flag value, repeatable flags, --file JSON merge
│   ├── OrgCommands.java                        # org create|update|deactivate|activate|show|list
│   ├── KeyCommands.java                        # key create|revoke|list
│   └── AccessCommands.java                     # package share|transfer, snapshot share|transfer
├── core/
│   ├── config/{ApplicationConfig,ConfigLoader}.java   # FV_SYSTEM_ORG_* (validated at load)
│   ├── org/                                    # NEW
│   │   ├── Organisation.java                   # record + validate()
│   │   ├── Organisations.java                  # SYSTEM_ID, type enum
│   │   └── OrganisationRepository.java         # CRUD, setActive, applySystemConfig, rename → catalog bump
│   ├── security/
│   │   ├── Caller.java, Access.java, AccessPolicy.java  # NEW
│   │   ├── ApiKeyRepository.java               # KeyInfo + owner/org fields; save(..., owner); list; bootstrap → SYSTEM_ID
│   │   └── ApiKeyAuthHandler.java              # Caller in context; 403 inactive org; ADMIN_PATHS + /igs/{n}/{v}
│   ├── npm/
│   │   ├── PackageOwnership.java               # NEW
│   │   ├── PackageCatalogView.java             # Entry + Access (join organisations)
│   │   └── PostgresPackageCacheManager.java    # ownership-aware store/storeFile/loadPackage; conditional upsert → 409; changeAccess
│   ├── service/
│   │   ├── ConfigRevisionRepository.java       # snapshot rows with include/exclude; changeAccess (IG_ACCESS); read PinnedAccess
│   │   ├── FhirValidationService.java          # validate(caller, …): resolve → AccessPolicy → registry.snapshot
│   │   └── IgPackageService.java               # include/exclude/register/upload with Caller; changePackageAccess/changeSnapshotAccess
│   └── validator/
│       ├── PinnedAccess.java                   # NEW
│       ├── ConfigSync.java                     # load + swap PinnedAccess; IG_ACCESS kind without rebuild
│       ├── IgReference.java                    # resolve(…, caller): highest visible version; visibility before FHIR-version check
│       ├── ValidatorRegistry.java              # details(): private contexts summarised
│       └── OutcomeRenderer.java                # igOwner, igShareable
├── web/ProblemException.java                   # dependencyUnavailable(), conflict(), organisationInactive()
└── web/endpoint/{ValidationApi,IgPackageApi}.java   # Caller from context; shareable inputs; PATCH routes; headers

src/main/resources/
├── openapi.yaml                                # contract changes (first)
├── config.json                                 # systemOrg.* defaults
└── db/migration/V8__organisations.sql          # NEW

src/test/java/nzi/fhir/validator/
├── core/security/AccessPolicyTest.java                         # NEW
├── core/org/{OrganisationTest, OrganisationRepositoryIT}.java  # NEW
├── core/npm/PackageOwnershipIT.java                            # NEW
├── core/validator/{OwnershipOnDemandIT, IncludeOwnershipIT}.java  # NEW
├── web/{OwnershipValidationIT, AccessChangeIT}.java            # NEW
├── cli/{AdminCliIT, CliArgsTest}.java                          # NEW
└── (extended) ApiKeyAuthIT, ApiKeyAuthHandlerTest, MigrationUpgradeIT, ConfigPropagationIT, ConfigLoaderTest,
    NoSecretsInLogsTest, ValidationApiContractTest, IgPackageApiContractTest, HealthApiTest, PackageCatalogViewTest

Dockerfile (fv-admin wrapper), .env.example, docs/upgrade-3.1.md, CHANGELOG.md, README.md
```

**Structure decision**: the existing single-module Maven layout. Two new packages, `core.org` (domain) and `cli`
(entry point), and no new modules or artifacts.

## Implementation phases (input for /speckit-tasks)

1. **Contract and schema first**: the `openapi.yaml` changes (contracts/http-api.md), `V8__organisations.sql`, and
   `MigrationUpgradeIT` (empty database and 3.0.0 → 3.1.0 with keys, packages and two included IGs). Version bump
   to 3.1.0.
2. **Organisations domain**: `Organisation` validation, `OrganisationRepository`, `FV_SYSTEM_ORG_*` configuration,
   and `installSystemOrganisation` in `Bootstrap` (US4).
3. **Caller identity**: `KeyInfo` with organisation fields, `Caller` in the context, 403 for inactive organisations,
   bootstrap key owner, and the `ADMIN_PATHS` extension (FR-006, FR-014).
4. **Access policy in memory**: `Access`/`AccessPolicy` with exhaustive unit tests, owner and flag in
   `PackageCatalogView`, and `PinnedAccess` loaded by `ConfigSync` (FR-008, FR-010, FR-011).
5. **Validation path**: `IgReference` visible-version resolution and check order, the check in
   `FhirValidationService` before `registry.snapshot`, 404/424 texts, response fields and headers, and metrics
   outcome reuse (US1, FR-012, FR-013, FR-020).
6. **Writes**: ownership-aware store with a conditional upsert (409), register, upload and URL `shareable`, and
   the read endpoints' visibility (US2, FR-015 to FR-017).
7. **Include and exclude**: child rows in the same transaction, the already-included-by-another-organisation rule,
   the private-only-for-package-owner rule, and exclude authorisation (FR-018).
8. **Access changes**: both PATCH operations, `IG_ACCESS` propagation without rebuild, package change via catalog
   revision, organisation rename refresh, and a two-instance test (FR-019).
9. **Command line**: `AdminCli`/`CliArgs`, org, key and access commands, exit codes, JSON-only stdout, the
   `fv-admin` wrapper, and the CLI ITs (US3, FR-022 to FR-026).
10. **Health and observability**: private contexts summarised, no owners, and the DEBUG refusal logs (FR-021).
11. **Release**:
    - CHANGELOG 3.1.0 and `docs/upgrade-3.1.md`: create organisations only after every instance runs 3.1; the
      rolling-upgrade defaults; command-line usage;
    - README sections on organisations and keys, replacing the raw-SQL key instructions;
    - `.env.example`;
    - benchmark before and after.

## Complexity Tracking

No constitution violations, so this section is empty.
