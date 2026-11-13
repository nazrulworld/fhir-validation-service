# Implementation Plan: Operator Switches for Enabled FHIR Versions and Global Access Control

**Branch**: `009-fhir-version-acl-toggles` | **Date**: 2026-11-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-fhir-version-acl-toggles/spec.md`; original notes
`var/security_enables,disables.txt`

## Summary

Two independent operator switches ship together as **4.2.0**, a minor release with no breaking changes.

**A. `FV_ENABLED_FHIR_VERSIONS`: serve only some FHIR versions.**
- `ConfigLoader` parses the setting into a `ServedVersions` set. A bad name is a configuration error (exit 1).
- That set replaces the static `SupportedFhirVersion.enabled()` / `parseEnabled()` wherever the instance decides what
  it serves: `ValidatorRegistry` base slots (so readiness), `ConfigSync`, `ConfigRevisionRepository.current`,
  `ServiceMetrics` and the `/{version}` path parameters.
- `fhir_validator_snapshots` gains `is_active` and `inactive_reason` (V14). At startup, one transaction:
  - deactivates contexts of unserved versions;
  - reactivates those deactivated only for that reason;
  - bumps the version revisions and notifies the other instances.
- `ConfigSync` skips inactive keys.
- `POST /{version}/validator` for a supported but unserved version → **422 `fhir-version-disabled`**.
- `DELETE` and `PATCH` still work on inactive contexts.
- Other version-scoped requests keep today's 400.

**B. `FV_GLOBAL_DISABLED_ACL_SECURITY`: public mode.**
- `ApiKeyAuthHandler` keeps the admin CIDR check. In disabled mode it skips the `Authorization` header and sets
  `Caller` to a cached fixed caller:
  - `system administrator` for admin paths;
  - `trial validator` for `/validate` and the package reads.

  Ownership, access checks and `UsageMeter` work unchanged.
- A new startup step `AclModeStartup` replaces the two key-install steps. It runs as one transaction, locked on the
  `service_settings.acl_security` row (V15):
  - refuses a mode change without `FV_ACL_MODE_CHANGE_CONFIRMED`;
  - in disabled mode, installs 256-bit random values for both fixed keys;
  - in enabled mode, installs the configured values as 4.1 does, with the "re-synchronised" line after a disabled run;
  - stores the mode.
- Keyed paths answer 503 in disabled mode until that step has committed, so an instance about to be refused never
  serves public admin calls.
- `/health` reports the served versions, inactive contexts and `security.aclSecurity`.

## Technical Context

**Language/Version**: Java 17 (service 4.1.0 → 4.2.0)

**Primary Dependencies**:
- Vert.x 4.5.x (core, web, web-openapi, pg-client), HAPI FHIR 8.x, Log4j2, Micrometer (Prometheus).
- `java.security.SecureRandom` (JDK).
- **No new Maven dependencies.**

**Storage**: PostgreSQL, through Flyway:
- `V14__validator_context_active.sql`: two columns, two checks and a partial index on `fhir_validator_snapshots`, and
  a backfill of missing rows (data-model §1);
- `V15__service_settings.sql`: the new `service_settings` table (data-model §2).

**Testing**:
- JUnit 5 with `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`), `AppHarness` / `CoreHarness`,
  `LogCapture`.
- New ITs:
  - `EnabledVersionsIT` (US1, US2);
  - `ContextActivationIT` (US3, including the 4.1 → 4.2 migration with data and the two-instance notification);
  - `AclDisabledIT` (US4, US5);
  - `AclModeSwitchIT` (US6, FR-029 refusal, concurrent starts).
- New unit tests: `ServedVersionsTest`, `ConfigLoaderTest` (new entries, FLAG parsing, R6, `X1`),
  `ApiKeyAuthHandlerTest` (disabled mode never reads the header, path → fixed caller, CIDR still first, 503 gate).
- Extended: `NoSecretsInLogsTest` (random values and ignored env values never logged), `MigrationUpgradeIT`
  (V13 → V15 on a populated schema), `SupportedFhirVersionTest`.

**Target Platform**: Linux containers (x86_64, arm64), horizontally scaled behind a load balancer

**Project Type**: Web service with an administrative CLI (single Maven module)

**Performance Goals**:
- No change on the validation hot path. In disabled mode the auth step costs less: no hashing and no key lookup,
  just a cached fixed caller.
- With one version served: less heap at rest (three fewer core packages) and faster readiness (three fewer base
  contexts), measured for SC-002.
- `/health` adds one indexed count query plus up to 100 rows.

**Constraints**:
- Both settings must be identical across a fleet. The ACL mode is enforced at startup; the version set is documented.
- The startup ACL step and the reconciliation are single transactions.
- No key value is ever logged, returned or stored in readable form.
- A rolling upgrade from 4.1 is allowed (additive schema).

**Scale/Scope**:
- Up to 4 served versions.
- Inactive contexts are bounded by the stored contexts (tens to hundreds). Health lists at most 100.
- Two fixed callers.

## Constitution Check

*GATE: checked before Phase 0 and again after Phase 1. The results below reflect the final design.*

| Principle | Status | How the design complies |
|---|---|---|
| **I. Non-blocking event loop** | PASS | Reconciliation and the ACL step are async pg `withTransaction` `Future` chains in `Bootstrap` warm-up. `SecureRandom.nextBytes` (32 bytes, twice per start) runs once in the warm-up chain. The default `NativePRNG` reads the non-blocking `/dev/urandom` for `nextBytes`. The generator is created ahead of time in `ServiceRegistry`, so its one-time seeding happens before any event-loop callback. The auth handler's fixed-caller cache refresh uses the async pool. No `.get()` or `.join()`. |
| **II. Stateless, horizontally scalable** | PASS | Shared state lives in PostgreSQL: `is_active` and `inactive_reason` per context, and the `service_settings` mode. Cache consistency: reconciliation bumps the revision of every affected version and sends `pg_notify` (`IG_ACTIVATION`) on commit; the existing poll catches missed notifications through the revision. The fixed-caller cache has the auth-cache TTL. Concurrent starts serialise on row locks (`FOR UPDATE` on the settings row; UPDATE row locks on snapshots) and are idempotent. Fleet-wide settings: the ACL mode mismatch is refused at startup (FR-029); a version-set mismatch is documented as unsupported (spec Assumptions). |
| **III. FHIR conformance** | PASS | Validation results don't change. Every supported version stays handled through `SupportedFhirVersion`; the operator setting only narrows what one deployment serves, and no version is dropped from the release (spec Context). R6 stays unserved. |
| **IV. Contract-first API** | PASS | The `openapi.yaml` changes land first ([contracts/http-api.md](./contracts/http-api.md)): the 422 response on include, new optional `HealthResponse` fields, and description texts. All changes are additive, and no existing status code changes for an existing case, so this is a MINOR release (4.2.0) with no Complexity Tracking entry. Errors use the existing problem format. |
| **V. Test-first, real dependencies** | PASS | Every story has a Testcontainers IT, including migration on a populated 4.1 schema and two `AppHarness` instances on one database (notification, concurrent starts). Parsing, path-to-caller mapping and the gate are pure unit tests. Every endpoint whose behaviour changes (`/{v}/validator` POST/DELETE/PATCH, `/health`, every keyed path in disabled mode) has IT coverage. |
| **VI. Performance budgets** | PASS | Impact is stated above. The hot path doesn't change, so no k6 run is required. SC-002 memory and readiness figures go in `perf/results-4.2.0.md`. New bounds: health list ≤ 100 items; fixed-caller cache = 2 entries. Readiness still reflects warm-up, now only for served versions. |
| **VII. Observability** | PASS | The startup log names the served versions, every (de)activated context and the ACL mode, with key values never shown. `/health` reports both. New gauges: `fv_acl_security_disabled`, `fv_validator_contexts_inactive` (no high-cardinality tags). All three settings come from env / `FV_CONFIG_FILE`. |
| **VIII. Simplicity, dependencies** | PASS | No new dependencies or infrastructure. Two new forward-only migrations (V14, V15); nothing applied is edited. `ServedVersions` replaces a static rather than adding a layer. |
| **Security & compliance** | PASS (deliberate, opt-in weakening) | Disabled mode is an explicit operator choice, and its WARN, health field and metric make it visible. The admin network allowlist stays enforced (FR-030). Ownership stays enforced (FR-031). The 503 gate prevents public access before the mode is committed. Random keys use a CSPRNG with 256 bits. PHI handling is unchanged. |
| **Workflow gate: migrations on the previous release's schema** | PASS | `MigrationUpgradeIT` migrates a populated V13 (4.1.0) schema to V15 and checks that every context is active (FR-007, US3-4). A rolling upgrade is documented (operations.md §6). |

**Post-design re-check**: no violations. Complexity Tracking is empty.

## Project Structure

### Documentation (this feature)

```text
specs/009-fhir-version-acl-toggles/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R16
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── http-api.md      # openapi.yaml changes, disabled-mode request table
│   └── operations.md    # env vars, startup outcomes, log lines, metrics, upgrade/switch procedure
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── Bootstrap.java                              # CHANGE: warm-up = migrate → orgs → system org → AclModeStartup
│                                               #   (replaces installBootstrapKey/installTrialValidatorKey)
│                                               #   → ContextActivation.reconcile → terminology → configSync;
│                                               #   logs served versions
├── ServiceRegistry.java                        # CHANGE: pass ServedVersions to registry/sync/metrics/APIs;
│                                               #   FixedCallers + AclMode into ApiKeyAuthHandler
├── core/enums/ServedVersions.java              # NEW: immutable served set, parse(), ALL, contains(), label()
├── core/config/ConfigLoader.java               # CHANGE: FV_ENABLED_FHIR_VERSIONS, FV_GLOBAL_DISABLED_ACL_SECURITY,
│                                               #   FV_ACL_MODE_CHANGE_CONFIRMED; Kind.FLAG; skip key format check
│                                               #   when disabled
├── core/config/ApplicationConfig.java          # CHANGE: servedVersions(), aclMode(), aclModeChangeConfirmed()
├── core/validator/ValidatorRegistry.java       # CHANGE: base slots from ServedVersions
├── core/validator/ConfigSync.java              # CHANGE: states from ServedVersions; IG_ACTIVATION notification;
│                                               #   resolveAll skips inactive keys
├── core/validator/ContextActivation.java       # NEW: startup reconcile (one transaction, notify, logs)
├── core/service/ConfigRevisionRepository.java  # CHANGE: read is_active/inactive_reason; ConfigRow.inactive();
│                                               #   KIND_IG_ACTIVATION; current() filtered by ServedVersions
├── core/service/IgPackageService.java          # CHANGE: include() refuses unserved (422); exclude/changeSnapshotAccess
│                                               #   skip LocalApplyWait for unserved versions
├── core/service/HealthService.java             # CHANGE: fhirVersions + security in checkHealth()
├── core/security/ApiKeyAuthHandler.java        # CHANGE: AclMode, FixedCallers, one keyed-path table (admin vs validate+reads),
│                                               #   503 gate until ACL step commits; CIDR check first in both modes
├── core/security/FixedCallers.java             # NEW: loads/caches the two fixed KeyInfo by id
├── core/security/AclModeStartup.java           # NEW: settings row lock, FR-029 guard, random/env key install, store mode
├── core/security/ApiKeyRepository.java         # CHANGE: upsertWellKnown(SqlClient, …) overload; findById(UUID)
├── core/settings/ServiceSettingsRepository.java # NEW: get-for-update / upsert of service_settings
├── core/metrics/ServiceMetrics.java            # CHANGE: served versions; two new gauges
├── web/ProblemException.java                   # CHANGE: unsupportedVersion lists served versions; fhirVersionDisabled()
├── web/endpoint/VersionParams.java             # NEW: served / servedForInclude / supported
├── web/endpoint/ValidationApi.java             # CHANGE: VersionParams.served
├── web/endpoint/ValidatorApi.java              # CHANGE: POST servedForInclude, DELETE/PATCH supported
└── cli/AccessCommands.java                     # CHANGE: snapshot share|transfer accept any supported version

src/main/resources/
├── openapi.yaml                                # CHANGE: 422 FhirVersionDisabled, HealthResponse fields, descriptions
└── db/migration/V14__validator_context_active.sql, V15__service_settings.sql   # NEW

src/test/java/nzi/fhir/validator/
├── core/enums/ServedVersionsTest.java                 # NEW
├── core/config/ConfigLoaderTest.java                  # CHANGE
├── core/config/NoSecretsInLogsTest.java               # CHANGE
├── core/security/ApiKeyAuthHandlerTest.java           # CHANGE
├── core/service/MigrationUpgradeIT.java               # CHANGE: V13 → V15 with data
├── web/EnabledVersionsIT.java                         # NEW (US1, US2)
├── core/validator/ContextActivationIT.java            # NEW (US3, two instances)
├── web/AclDisabledIT.java                             # NEW (US4, US5)
├── BootstrapAclModeIT.java                            # NEW (US6, FR-029, concurrent starts)
└── testsupport/AppHarness.java                        # CHANGE: start with extra env (served versions, ACL mode)

pom.xml                                                # CHANGE: 4.1.0 → 4.2.0
CHANGELOG.md, README.md, docs/upgrade-4.2.md           # CHANGE/NEW: settings, security warning, switch procedure
perf/results-4.2.0.md                                  # NEW: SC-002 heap/readiness figures
```

**Structure Decision**: this is the existing single-module layout. Version handling stays in `core/enums` and
`core/validator`, next to the registry and sync it changes. ACL code stays in `core/security`. The generic settings
table gets a small `core/settings` package, because it isn't specific to security and later features may use it.

## Implementation Order (for /speckit-tasks)

1. **Contract and schema first**:
   - `openapi.yaml` (contracts/http-api.md);
   - V14 and V15;
   - `MigrationUpgradeIT` from a populated 4.1 schema.
2. **Part A foundation**: `ServedVersions` and `ConfigLoader` (tests first), then thread it through `ServiceRegistry`,
   `ValidatorRegistry`, `ConfigSync`, `ConfigRevisionRepository`, `ServiceMetrics`. With the variable unset, the whole
   suite must stay green (SC-001).
3. **US1 + US2**: `VersionParams`, `ValidatorApi` and `ValidationApi`, the 422 in `IgPackageService.include`,
   `EnabledVersionsIT`.
4. **US3**: `ConfigRow.inactive`, `ContextActivation.reconcile`, `IG_ACTIVATION` handling, exclude/PATCH on inactive
   contexts, `ContextActivationIT`.
5. **Part B foundation**: FLAG parsing, `AclMode`, `ServiceSettingsRepository`, `ApiKeyRepository.findById` and the
   transaction-aware `upsertWellKnown`.
6. **US5 + US6**: the disabled-mode 503 gate in `ApiKeyAuthHandler` (T040) first, then `AclModeStartup` replacing
   the two install steps; `BootstrapAclModeIT` (refusal, confirmation,
   resync, unset or revoked env value, concurrent starts).
7. **US4**: `FixedCallers`, `ApiKeyAuthHandler` fixed-caller resolution in disabled mode, `AclDisabledIT` (ownership, usage,
   CIDR, private contexts 404, `Bearer garbage`).
8. **Visibility**: `/health` fields, gauges, log lines, `NoSecretsInLogsTest`.
9. **Release**: README, `docs/upgrade-4.2.md`, CHANGELOG 4.2.0, `pom.xml` 4.2.0, `perf/results-4.2.0.md`.

Steps 2–4 (part A) and 5–7 (part B) are independent and can run in parallel after step 1.

## Complexity Tracking

No constitution violations. Nothing to justify.
