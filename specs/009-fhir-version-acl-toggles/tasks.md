---

description: "Task list for 009-fhir-version-acl-toggles"
---

# Tasks: Operator Switches for Enabled FHIR Versions and Global Access Control

**Input**: Design documents from `specs/009-fhir-version-acl-toggles/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R16), data-model.md, contracts/http-api.md,
contracts/operations.md, quickstart.md

**Tests**: Included. Constitution V requires test-first development, real PostgreSQL (Testcontainers) for persistence
and migrations, and a contract or integration test for every endpoint whose behaviour changes. Write each test task
first and confirm it fails before the matching implementation task.

**Organization**: Tasks are grouped by the user stories in spec.md (US1–US6). Part A (US1–US3) and part B (US4–US6)
are independent. Part B phases follow **dependency order**: US5 (the request gate, then the startup ACL step that installs
random keys and opens the gate) comes before US6 (switch guard and re-sync), and both come before US4 (public request
handling). A disabled-mode request needs the fixed keys installed and the gate opened.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: The user story the task belongs to (US1–US6)

## Path Conventions

- Production code: `src/main/java/nzi/fhir/validator/` (abbreviated `main/` below)
- Tests: `src/test/java/nzi/fhir/validator/` (abbreviated `test/` below)
- Contract: `src/main/resources/openapi.yaml`
- Migrations: `src/main/resources/db/migration/`
- Run one test with `./mvnw -q test -Dtest=<Class>`; run an IT with `./mvnw -q verify -Dit.test=<Class>`
- `AppHarness.start(vertx, config)` takes a `FV_CONFIG_FILE`-style `JsonObject`. Pass the new settings by their paths
  (`fhir.enabledVersions`, `auth.globalDisabledAclSecurity`, `auth.aclModeChangeConfirmed`, data-model §5).

---

## Phase 1: Setup (contract and schema)

**Purpose**: Contract-first (Principle IV) and the two forward-only migrations every story builds on.

- [X] T001 Bump the project version 4.1.0 → 4.2.0 in `pom.xml`, and add an empty `## [4.2.0]` heading under
  `## [Unreleased]` in `CHANGELOG.md`.
- [X] T002 [P] Update `src/main/resources/openapi.yaml` as in contracts/http-api.md:
  - §1: add the `components.responses.FhirVersionDisabled` response (422, problem+json, `type: fhir-version-disabled`,
    example detail) and reference it as `'422'` on `validatorApiIncludeIg`, appending the 4.2.0 sentence to that
    operation's description;
  - §2: append the 4.2.0 inactive-context sentence to the descriptions of `validatorApiExcludeIg` and
    `validatorApiChangeIncludedIg`;
  - §3: update the `FhirVersion` schema description;
  - §4: add the optional `fhirVersions` and `security` properties to `HealthResponse` (`items` `maxItems: 100`,
    `reason` enum `[fhir-version-disabled]`, `aclSecurity` enum `[enabled, disabled]`, `adminNetworkRestricted`
    boolean);
  - §5: append the 4.2.0 paragraph to `components.securitySchemes.apiKey.description`.

  Do not change any operation's `security` declaration.
- [X] T003 [P] Create `src/main/resources/db/migration/V14__validator_context_active.sql` (data-model §1):
  1. `ALTER TABLE fhir_validator_schema.fhir_validator_snapshots ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL
     DEFAULT TRUE, ADD COLUMN IF NOT EXISTS inactive_reason VARCHAR(32) NULL`;
  2. insert a system-owned, shareable row for every package in `fhir_validator_logs.included_ig_packages` that has no
     snapshot row, as in V8 (`INSERT … SELECT fhir_version, unnest(included_ig_packages) … ON CONFLICT DO NOTHING`);
  3. add `CONSTRAINT fhir_validator_snapshots_active_chk CHECK (is_active = (inactive_reason IS NULL))`;
  4. add `CONSTRAINT fhir_validator_snapshots_reason_chk CHECK (inactive_reason IS NULL OR inactive_reason IN
     ('fhir-version-disabled'))`;
  5. add `CREATE INDEX IF NOT EXISTS fhir_validator_snapshots_inactive_idx ON
     fhir_validator_schema.fhir_validator_snapshots (fhir_version) WHERE NOT is_active`.
- [X] T004 [P] Create `src/main/resources/db/migration/V15__service_settings.sql` (data-model §2):
  - table `fhir_validator_schema.service_settings` with columns:
    - `name VARCHAR(64) PRIMARY KEY`,
    - `value VARCHAR(256) NOT NULL`,
    - `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`,
    - `updated_by VARCHAR(128) NULL`;
  - `CONSTRAINT service_settings_acl_chk CHECK (name <> 'acl_security' OR value IN ('enabled','disabled'))`;
  - no seed row.
- [X] T005 Extend `test/core/service/MigrationUpgradeIT.java`: migrate a populated V13 (4.1.0) schema (contexts for
  R4 and R5, one included package without a snapshot row) to V15. Assert:
  - every snapshot row has `is_active = true` and `inactive_reason IS NULL` (FR-007, US3-4);
  - the missing row was inserted;
  - `service_settings` exists and is empty;
  - both check constraints reject bad values.
- [X] T006 [P] Add a test in `test/web/` (or extend the existing contract test that loads `openapi.yaml`) asserting
  that `validatorApiIncludeIg` declares `422` and that `HealthResponse` has `fhirVersions` and `security`.

**Checkpoint**: the contract and schema are in. `./mvnw verify` is green, because the new columns have defaults and
the new table is unused.

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: configuration parsing and the `ServedVersions` plumbing for part A; settings and key repository support
for part B. With all new settings unset, behaviour must equal 4.1 (SC-001).

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests first

- [X] T007 [P] Write `test/core/enums/ServedVersionsTest.java`:
  - `parse("r4, R5 ,R4")` → `[R4, R5]` in declaration order;
  - `parse(null)`, `parse("")` and `parse(" , ")` → `ALL` (= `SupportedFhirVersion.enabled()`);
  - `parse("R4,X1")` throws `IllegalStateException` naming `X1` and listing `STU3, R4, R4B, R5`;
  - `parse("R6")` throws with "known but not served by this release";
  - `contains()`, `label()` ("R4, R5"), and `isAll()`.
- [X] T008 [P] Extend `test/core/config/ConfigLoaderTest.java`:
  - `FV_ENABLED_FHIR_VERSIONS` and `fhir.enabledVersions` produce `servedVersions()`, and a bad value fails `load()`;
  - `Kind.FLAG` for `FV_GLOBAL_DISABLED_ACL_SECURITY` and `FV_ACL_MODE_CHANGE_CONFIRMED`: `true`/`1`/`yes`/`YES `
    mean true; unset or empty means false; `maybe` means false and logs a WARN naming the variable and value (use
    `LogCapture`);
  - with the mode disabled, a malformed `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY` does **not** fail `load()`
    (research R13); with the mode enabled it still fails as in 4.1.
- [X] T009 [P] Write `test/core/settings/ServiceSettingsRepositoryIT.java` (Testcontainers):
  - `lockForUpdate(tx, "acl_security")` returns empty on a fresh database;
  - `upsert(tx, name, value, updatedBy)` inserts and then updates `updated_at`;
  - two concurrent transactions serialise: the second sees the first's committed value.
- [X] T010 [P] Extend `test/core/security/ApiKeyRepositoryIT.java`:
  - `findById(UUID)` returns `KeyInfo` with owner active and revoked state, and empty for an unknown id;
  - `upsertWellKnown(SqlClient tx, …)` inside a rolled-back transaction leaves the stored hash unchanged;
  - inside a committed one it replaces it.

### Implementation

- [X] T011 [P] Create `main/core/enums/ServedVersions.java` (research R1, R2): an immutable wrapper over an `EnumSet`
  of `SupportedFhirVersion` with `ALL`, `parse(String)`, `contains(v)`, `asList()` (declaration order), `isAll()` and
  `label()`. Parsing:
  - split on `,`, trim, drop empty items, upper-case with `Locale.ROOT`, de-duplicate;
  - nothing left → `ALL`;
  - `R6` → `IllegalStateException("FV_ENABLED_FHIR_VERSIONS: 'R6' is known but not served by this release
    (accepted: STU3, R4, R4B, R5)")`;
  - any other unknown name → `IllegalStateException("FV_ENABLED_FHIR_VERSIONS: '<v>' is not a FHIR version this
    service serves (accepted: STU3, R4, R4B, R5)")`.
- [X] T012 Update `main/core/config/ConfigLoader.java` and `main/core/config/ApplicationConfig.java`:
  - add `Kind.FLAG` (true for `true`/`1`/`yes`, case-insensitive and trimmed; false otherwise; WARN `<VAR>='<value>'
    is not recognised; …` for a non-empty unrecognised value);
  - add the entries `FV_ENABLED_FHIR_VERSIONS` (`fhir.enabledVersions`, STRING, parsed through
    `ServedVersions.parse`), `FV_GLOBAL_DISABLED_ACL_SECURITY` (`auth.globalDisabledAclSecurity`, FLAG) and
    `FV_ACL_MODE_CHANGE_CONFIRMED` (`auth.aclModeChangeConfirmed`, FLAG);
  - add `ApplicationConfig.servedVersions()`, `aclMode()` (new enum `core/security/AclMode { ENABLED, DISABLED }`)
    and `aclModeChangeConfirmed()`;
  - skip the 32–128 format check of both key variables when `aclMode() == DISABLED`.

  Makes T007/T008 pass.
- [X] T013 Thread `ServedVersions` through the instance (research R1). Replace `SupportedFhirVersion.enabled()` /
  `parseEnabled()` with the config's set in:
  - `main/core/validator/ValidatorRegistry.java` (base slots, line ~122);
  - `main/core/validator/ConfigSync.java` (`states` map, line ~89; `onNotification` parse, line ~170);
  - `main/core/service/ConfigRevisionRepository.java` (`current()` keeps only served versions, via a new
    `current(SqlClient, ServedVersions)`);
  - `main/core/metrics/ServiceMetrics.java` (lines ~71, ~144).

  Pass the set from `main/ServiceRegistry.java`. Leave `TxRegistry` and `TxServerAllowlist` on
  `SupportedFhirVersion.enabled()` (research R6). Keep `SupportedFhirVersion.enabled()` itself unchanged.
- [X] T014 [P] Create `main/core/settings/ServiceSettingsRepository.java`:
  - `Future<Optional<String>> lockForUpdate(SqlClient tx, String name)`: `SELECT value … WHERE name = $1 FOR UPDATE`
    (it never inserts);
  - `Future<Boolean> insertIfAbsent(SqlClient tx, String name, String value)`: `INSERT … ON CONFLICT DO NOTHING`,
    returning whether a row was inserted;
  - `Future<Void> upsert(SqlClient tx, String name, String value, String updatedBy)` (`INSERT … ON CONFLICT (name)
    DO UPDATE SET value, updated_at = now(), updated_by`);
  - constant `ACL_SECURITY = "acl_security"`.

  Makes T009 pass.
- [X] T015 [P] Update `main/core/security/ApiKeyRepository.java`:
  - add `Future<Optional<KeyInfo>> findById(UUID id)`, using the same columns and owner join as `findActive` and
    filtering revoked and expired keys;
  - add the overload `upsertWellKnown(SqlClient client, UUID id, String name, String key, Role role, UUID owner,
    Instant expiresAt)`;
  - make the existing pool overload delegate to it, keeping the unique-violation retry and `StartupException`
    mapping.

  Makes T010 pass.
- [X] T016 Run `./mvnw verify` with no new settings and fix any regression (SC-001).

**Checkpoint**: everything behaves as 4.1 when the settings are unset. Part A and part B can now proceed in parallel.

---

## Phase 3: User Story 1 - Serve only the FHIR versions the operator chooses (Priority: P1) 🎯 MVP

**Goal**: an instance loads and serves only the configured versions. Other versions get today's 400.

**Independent Test**: start with `fhir.enabledVersions = "R4"`. `/health/readiness` lists only R4, `/health` →
`fhirVersions.served = ["R4"]`, and `POST /R5/validate` → 400 `unsupported-fhir-version`. With the setting unset, all
four versions are served.

### Tests for User Story 1

- [X] T017 [P] [US1] Write `test/web/EnabledVersionsIT.java` (AppHarness + Testcontainers). Cases:
  - unset → readiness versions = STU3, R4, R4B, R5 (US1-1);
  - `R4` → readiness keys = `[R4]`, and readiness is UP only after R4's base context is READY (US1-2);
  - `r4, R5 ,R4` → R4 and R5 (US1-3);
  - `R4`: `POST /R5/validate` with the validator key → 400 `unsupported-fhir-version`, with detail listing `R4`
    (US1-6, FR-005);
  - `GET /health` → `fhirVersions.served`.
- [X] T018 [P] [US1] Add a case to `test/BootstrapFatalExitTest.java` (or `ConfigLoaderTest`): `R4,X1` → `Bootstrap`
  prints `Configuration error: …'X1'…` and exits 1 before HTTP starts (US1-4).

### Implementation for User Story 1

- [X] T019 [P] [US1] Create `main/web/endpoint/VersionParams.java` with `served(String param, ServedVersions)` →
  served version or `ProblemException.unsupportedVersion(param, served)`. Change
  `main/web/ProblemException.java#unsupportedVersion` to list the served versions (`"FHIR version 'R5' is not
  supported. Supported versions: R4"`), keeping status 400 and type `unsupported-fhir-version`.
- [X] T020 [US1] Switch `main/web/endpoint/ValidationApi.java` (line ~143) and every other `/{version}` read to
  `VersionParams.served`.
- [X] T021 [US1] In `main/core/service/HealthService.java#checkHealth`, add
  `fhirVersions.served = servedVersions.asList()` (the `inactiveContexts` part comes in US3). In
  `main/Bootstrap.java`, log at INFO at the start of warm-up `FHIR versions served: <label> (FV_ENABLED_FHIR_VERSIONS)`
  or `… (all; FV_ENABLED_FHIR_VERSIONS not set)` (contracts/operations.md §3).
- [X] T022 [US1] Run `EnabledVersionsIT` and `HeapGuardIT` and make them pass. Confirm that an R4-only instance never
  builds an R5 base context: `ValidatorRegistry.details()` has no R5 key.

**Checkpoint**: US1 is shippable on its own. A one-version deployment uses less memory.

---

## Phase 4: User Story 2 - Refuse new validator contexts for disabled versions (Priority: P1)

**Goal**: `POST /{version}/validator` for a supported but unserved version → 422, with nothing stored.

**Independent Test**: with `R4`, `POST /R5/validator` → 422 `fhir-version-disabled`; no snapshot row, no
configuration revision bump, no notification. `POST /R4/validator` → 200 and the row has `is_active = true`.

### Tests for User Story 2

- [X] T023 [P] [US2] Extend `test/web/EnabledVersionsIT.java`:
  - with `R4` and an R5 IG stored, `POST /R5/validator {"igPackageId": …}` → 422, `type = fhir-version-disabled`,
    and a detail naming `R5` and `R4` (FR-008);
  - the `fhir_validator_snapshots` R5 row count and the R5 `fhir_validator_logs.revision` are unchanged (FR-008,
    SC-003);
  - `POST /R9/validator` → 400 `unsupported-fhir-version`;
  - `POST /R4/validator` for an R4 IG → 200 and `is_active = true` (FR-009, US2-2);
  - unset → `POST /R5/validator` works as in 4.1 (US2-3).
- [X] T024 [P] [US2] Extend `test/core/service/IgPackageServiceTest.java` (or `IncludeExcludeIT`): calling
  `IgPackageService.include(caller, R5, …)` directly on an R4-only service fails with a 422 `ProblemException` before
  any transaction starts.

### Implementation for User Story 2

- [X] T025 [P] [US2] Add `ProblemException.fhirVersionDisabled(SupportedFhirVersion v, ServedVersions served)` in
  `main/web/ProblemException.java`: status 422, type `fhir-version-disabled`, detail `"IG validator contexts for
  <v> can't be added: this service serves <served.label()> only (FV_ENABLED_FHIR_VERSIONS)"`.
- [X] T026 [US2] Add `VersionParams.servedForInclude(param, served)`: unknown → 400; supported but not served → 422;
  served → the version. Use it in `main/web/endpoint/ValidatorApi.java#handleCreateValidator` **before** reading the
  body.
- [X] T027 [US2] In `main/core/service/IgPackageService.java#include` (all overloads), check
  `servedVersions.contains(fhirVersion)` first and fail with `fhirVersionDisabled` (defence in depth, research R4).
  Inject `ServedVersions` through `ServiceRegistry`.

**Checkpoint**: US1 + US2 make the P1 MVP.

---

## Phase 5: User Story 3 - Deactivate stored contexts when a version is turned off (Priority: P2)

**Goal**: at startup, contexts of unserved versions become inactive, and those inactive only for that reason become
active again, in one transaction, logged, and propagated. Admin actions keep working on inactive contexts.

**Independent Test**: store R4 and R5 contexts, restart with `R5`, and check the rows, the logs, `/health` and the
loaded contexts. PATCH the inactive R4 context. Restart with `R4,R5`: it is reactivated with the PATCHed settings.

### Tests for User Story 3

- [X] T028 [P] [US3] Write `test/core/validator/ContextActivationIT.java` (Testcontainers, `LogCapture`). Cases:
  - start `R4,R5` and include an R4 and an R5 context; restart `R5` → the R4 row has `is_active=false,
    inactive_reason='fhir-version-disabled'`, the R5 row is unchanged, and R4 is absent from readiness (US3-1,
    FR-010);
  - logs: one WARN `Validator context deactivated: R4 <pkg> (owner <short>)…` per context plus one WARN count line;
    no WARN when nothing changed (US3-2, FR-012);
  - all or nothing: a failing statement in the transaction (e.g. a test hook that drops the R4 revision row) →
    `StartupException`, and no row changed (US3-3, FR-011);
  - restart `R4,R5` → R4 active again, INFO per context plus a count, R4 context READY (US3-5, FR-014);
  - the reactivation condition only matches `inactive_reason = 'fhir-version-disabled'`: assert it in a unit test of
    the statement built by `ContextActivation` (a context made inactive for a future reason is never reactivated);
  - `/health` → `fhirVersions.inactiveContexts.count` and `items` (≤ 100, ordered by version then package) (FR-015).
- [X] T029 [P] [US3] Two instances in `ContextActivationIT`: A runs `R4,R5` with an R4 context built; B starts with
  `R5`. A drops the R4 pinned context within the propagation window, both by notification and with notifications
  disabled (poll fallback, `ConfigSync.setNotificationsEnabled(false)`) (FR-013, SC-004).
- [X] T030 [P] [US3] FR-032 cases in `ContextActivationIT`, with `R5` and an inactive R4 context:
  - `PATCH /R4/validator?ig=…` with `{"shareable": false}` → 200 and the row changes;
  - a transfer by the system organisation → 200;
  - a terminology change to a registered server → 200;
  - `DELETE /R4/validator?ig=…` → 200 and the row is gone;
  - `POST /R4/validator` for the same package → 422, even though an inactive row exists;
  - restart `R4,R5` → reactivated with `shareable=false` kept (US3-6).
- [X] T031 [P] [US3] Extend `test/cli/AdminCliIT.java`: `snapshot share|transfer R4 …` works on an inactive R4 context
  (contracts/operations.md §5).

### Implementation for User Story 3

- [X] T032 [US3] Update `main/core/service/ConfigRevisionRepository.java`:
  - select `s.is_active, s.inactive_reason` in `SELECT`;
  - add `Map<String, String> inactive` (key → reason) to `ConfigRow` with an accessor `inactive()`;
  - add `KIND_IG_ACTIVATION = "IG_ACTIVATION"`.

  Existing constructors default `inactive` to empty.
- [X] T033 [US3] Update `main/core/validator/ConfigSync.java`:
  - `resolveAll(row, included, …)` skips keys in `row.inactive()` (FR-006);
  - `onNotification` treats `IG_ACTIVATION` like `IG` (sync that version).
- [X] T034 [US3] Create `main/core/validator/ContextActivation.java` with `Future<Result> reconcile(Pool pool,
  ServedVersions served)` (research R5). Inside `pool.withTransaction`:
  1. deactivate: `UPDATE … SET is_active = false, inactive_reason = 'fhir-version-disabled' WHERE is_active AND NOT
     (fhir_version = ANY($1)) RETURNING fhir_version, ig_package, owner`;
  2. reactivate: `UPDATE … SET is_active = true, inactive_reason = NULL WHERE inactive_reason =
     'fhir-version-disabled' AND fhir_version = ANY($1) RETURNING …`;
  3. for each touched version, `UPDATE fhir_validator_logs SET revision = revision + 1 …` and
     `pg_notify(CHANNEL, {"kind":"IG_ACTIVATION","fhirVersion":v})`.

  After commit, log per contracts/operations.md §3, resolving owner short names in the RETURNING join. Map a failure to
  `StartupException("validator context activation could not be stored: …")`.
- [X] T035 [US3] In `main/Bootstrap.java` warm-up, call `ContextActivation.reconcile` after the key and ACL step and
  before `services.startTerminology()` / `services.configSync().start()`.
- [X] T036 [US3] Admin actions on inactive contexts (FR-032, research R4):
  - add `VersionParams.supported(param)` (any of `SupportedFhirVersion.enabled()`, otherwise 400) and use it in
    `main/web/endpoint/ValidatorApi.java#handleRemoveValidator` and `#handleChangeValidatorAccess`;
  - in `main/core/service/IgPackageService.java#exclude` and `#changeSnapshotAccess`, skip `LocalApplyWait` when the
    version isn't served;
  - in `main/cli/AccessCommands.java` (line ~55), accept any supported version.
- [X] T037 [US3] In `main/core/service/HealthService.java#checkHealth`, add `fhirVersions.inactiveContexts = {count,
  items}` from `SELECT s.fhir_version, s.ig_package, o.name_short, s.inactive_reason, count(*) OVER () FROM snapshots s
  JOIN organisations o … WHERE NOT s.is_active ORDER BY 1, 2 LIMIT 100` (it uses the partial index). On a query error,
  omit the field rather than failing health.

**Checkpoint**: part A is complete (US1–US3).

---

## Phase 6: User Story 5 - Make known key values useless while access control is off (Priority: P2)

**Goal**: the startup ACL step installs random fixed-caller keys in disabled mode, ignores the env values, stores the
mode and opens the request gate. (US5 is ordered before US4 because public requests need this step; see Organization.)

**Independent Test**: start disabled with `FV_ADMIN_API_KEY` set. The stored hash ≠ `sha256(value)`, the log says the
variable was ignored and never contains the value, and `service_settings.acl_security = disabled`.

### Tests for User Story 5

- [X] T038 [P] [US5] Write `test/BootstrapAclModeIT.java` (Testcontainers, `LogCapture`), disabled-mode cases:
  - fresh database, disabled → both fixed keys exist with their fixed ids, names, roles and organisations (US5-3);
  - their `key_hash` ≠ the hash of the configured env values (US5-1);
  - the hashes differ from the previous start's (re-randomised each start, FR-021);
  - `service_settings.acl_security = 'disabled'` with `updated_by` set;
  - INFO `FV_ADMIN_API_KEY is ignored: access control is disabled` (FR-022);
  - a malformed `FV_ADMIN_API_KEY` doesn't stop the start (research R13);
  - a revoked `system administrator` key is un-revoked by the new random value;
  - the WARN `ACCESS CONTROL IS DISABLED …` names `FV_AUTH_ADMIN_CIDRS` or "open from any network" (FR-023, FR-030).
- [X] T039 [P] [US5] Extend `test/core/config/NoSecretsInLogsTest.java`: across a disabled start and a
  disabled → enabled start, no captured log line contains either env key value or any generated random value. Expose
  the generated values to the test through a package-private hook (SC-008).

### Implementation for User Story 5

- [X] T040 [US5] Add the disabled-mode gate to `main/core/security/ApiKeyAuthHandler.java` (research R10):
  - the constructor takes `AclMode`;
  - add `volatile boolean gateOpen` and `openGate()`;
  - in disabled mode, every keyed path answers 503 `not-ready` with `Retry-After: 5` while the gate is closed;
  - enabled mode doesn't change;
  - wire `AclMode` in `main/ServiceRegistry.java` and expose `authHandler()`.

  Add the gate cases to `test/core/security/ApiKeyAuthHandlerTest.java` first: disabled and gate closed → 503 on
  admin, validate and package-read paths; open → passes through; enabled mode → unaffected.
- [X] T041 [US5] Create `main/core/security/AclModeStartup.java` with `Future<Void> run(ServiceRegistry services)`
  (research R11). In one `pool.withTransaction`:
  1. Find the previous mode: `settings.lockForUpdate(tx, ACL_SECURITY)`. If that is empty, run `INSERT … VALUES
     ('acl_security', <configured mode>) ON CONFLICT DO NOTHING` and lock again. The previous mode is the first read
     when present. Otherwise, if the insert affected 0 rows (a concurrent starter inserted first), it is the re-read
     value; otherwise there is none (no stored mode, no confirmation needed).
  2. Disabled: generate two values with a shared `SecureRandom` (created in `ServiceRegistry`): 32 bytes,
     `Base64.getUrlEncoder().withoutPadding()`, 43 characters. Call
     `apiKeys.upsertWellKnown(tx, SYS_ORG_ADMIN_API_KEY_UUID, SYS_ORG_ADMIN_API_KEY_NAME, value, ADMIN,
     SYSTEM_ORG_UUID, SYS_ORG_API_KEY_EXPIRES_AT)` and the same for `TRIAL_ORG_VALIDATOR_API_KEY_UUID` (VALIDATE,
     `TRIAL_ORG_UUID`). Log the "ignored" INFO for each key variable that is set.
  3. `upsert(tx, ACL_SECURITY, mode, hostname)`.

  After commit: `services.authHandler().openGate()` (T040), and the FR-023 WARN in disabled mode.
- [X] T042 [US5] In `main/Bootstrap.java`, replace `installBootstrapKey` and `installTrialValidatorKey` in the warm-up
  chain with `AclModeStartup.run(services)`. Move their enabled-mode logic (unset → WARN, revoked → ERROR, installed →
  INFO) into `AclModeStartup`'s enabled branch unchanged, so 4.1 behaviour holds (FR-027).

**Checkpoint**: disabled-mode starts install unknown keys and store the mode.

---

## Phase 7: User Story 6 - Switch access control back on without manual steps (Priority: P2)

**Goal**: a mode change needs `FV_ACL_MODE_CHANGE_CONFIRMED`. Switching back re-installs the env keys in one
transaction and logs the re-sync line.

**Independent Test**: disabled run → enabled restart without the confirmation exits 1 with nothing changed. With the
confirmation, the env keys work and a context created while disabled is reachable with the admin key.

### Tests for User Story 6

- [X] T043 [P] [US6] Extend `test/BootstrapAclModeIT.java`:
  - stored `disabled` + configured enabled, no confirmation → `StartupException` whose message names both modes and
    `FV_ACL_MODE_CHANGE_CONFIRMED`; key hashes and the stored mode are unchanged (US6-7, FR-029);
  - stored `enabled` + configured disabled, no confirmation → refused in the same way (US6-6);
  - while a refused instance starts in disabled mode, a keyed request to it gets 503 `not-ready` until the process
    exits (research R10);
  - with the confirmation: the stored hashes equal `sha256(env)`, INFO `API keys re-synchronised from environment`,
    and the mode becomes `enabled` (US6-1, FR-025);
  - a validator context included while disabled is reachable with the env admin key and owned by the system
    organisation (US6-2, FR-026);
  - enabled → enabled with the same keys and no confirmation → no re-sync line and no ACL WARN (US6-3, FR-027);
  - enabled → enabled with a new `FV_VALIDATOR_API_KEY` → rotated as in 4.1, with no re-sync line (US6-4);
  - confirmation set and modes equal → WARN asking to remove it, start succeeds (US6-8);
  - disabled → enabled with `FV_VALIDATOR_API_KEY` unset → WARN naming it; the trial key stays unusable (401) and
    the admin key works (edge case);
  - disabled → enabled with a revoked admin value → ERROR as in 4.1, and that key stays unusable (edge case);
  - no stored mode (fresh database) + disabled, no confirmation → starts (edge case);
  - two instances starting at once with the same mode → both start, one stored row.
- [X] T044 [P] [US6] Extend `test/web/ApiKeyAuthIT.java`: with the mode unset or `false`, keyed endpoints without a
  valid key → 401/403 as in 4.1 (US6-5).

### Implementation for User Story 6

- [X] T045 [US6] Add the FR-029 guard to `main/core/security/AclModeStartup.java`. After locking:
  - if a previous mode exists, differs from the configured one, and `config.aclModeChangeConfirmed()` is false →
    fail with `StartupException("access control mode change refused: stored mode is <a>, this instance is configured
    <b>. To switch, set FV_ACL_MODE_CHANGE_CONFIRMED=true on the instances being switched (see
    docs/upgrade-4.2.md)")`, which rolls back the transaction;
  - if it differs and is confirmed → WARN `Access control mode changes from <a> to <b> (FV_ACL_MODE_CHANGE_CONFIRMED)`;
  - if it is equal and confirmed → WARN to remove the setting.
- [X] T046 [US6] In the enabled branch of `AclModeStartup`, perform both env key upserts with the transaction-aware
  `upsertWellKnown(tx, …)`. When the previous mode was `disabled`, log INFO `API keys re-synchronised from environment`
  after commit, plus one WARN per unset variable: `<VAR> is not set: the '<name>' key keeps an unknown value and can't
  be used until it is set` (FR-025).

**Checkpoint**: switching modes is safe in both directions.

---

## Phase 8: User Story 4 - Run every endpoint without access control (Priority: P2)

**Goal**: in disabled mode no endpoint needs a key and the header is never read. Each request runs as a fixed caller.
The CIDR allowlist and ownership rules still apply.

**Independent Test**: disabled start. An ADMIN call without a header succeeds as `system administrator`.
`Bearer garbage` succeeds. Validation records usage for `…200000000002`. A context included is owned by the system
organisation. `/health` → `security.aclSecurity = disabled`.

### Tests for User Story 4

- [X] T047 [P] [US4] Extend `test/core/security/ApiKeyAuthHandlerTest.java` (pure unit test, mocked
  `RoutingContext`, stub `FixedCallers`). In disabled mode:
  - `getHeader("Authorization")` is never called (verify zero interactions) (FR-017);
  - admin path → `fixed.admin()`;
  - `/R4/validate` → `fixed.validate()`;
  - `/igs/x/1.0.0/dependencies` and `/conformance` → `fixed.admin()` (FR-018);
  - `CONTEXT_KEY` and `CALLER_KEY` are set;
  - `FV_AUTH_ADMIN_CIDRS` set and the client outside it → 403 before the caller is set (FR-030);
  - organisation inactive → 403;
  - fixed key revoked → 403.

  In enabled mode, behaviour is unchanged.
- [X] T048 [P] [US4] Write `test/web/AclDisabledIT.java` (AppHarness, disabled, with `usage.sink = "db"` so usage rows
  are written). Cases:
  - `POST /R4/validator` without a header → 200, owner = system organisation (US4-1, US4-4, FR-019);
  - `Authorization: Bearer garbage`, `Basic abc` and a valid env key → never 401 (US4-2);
  - `POST /R4/validate` → 200 and the `validation_usage` row has `api_key_id = 00000000-0000-0000-0000-200000000002`
    and `org_id` = the trial org (US4-3, FR-020);
  - `GET /igs/{n}/{v}/dependencies` → 200 (US4-5);
  - `POST /igs/upload` → the package is owned by the system organisation (FR-019);
  - `POST /igs/register` (through the stub registry, `StubRegistry`) → the registered package is owned by the system
    organisation (FR-019);
  - org A's private context (created through the CLI or a direct insert) named in validation → 404, and a shareable
    one works (US4-8, FR-031);
  - with `auth.adminCidrs = "10.0.0.0/8"`, an admin call from 127.0.0.1 → 403 and a validation call → 200 (US4-7);
  - `/health` → `security = {aclSecurity: disabled, adminNetworkRestricted: …}` (US4-6, FR-023);
  - `/metrics` has `fv_acl_security_disabled 1`.

### Implementation for User Story 4

- [X] T049 [P] [US4] Create `main/core/security/FixedCallers.java`:
  - holds `ApiKeyRepository` and the TTL;
  - `Future<KeyInfo> admin()` and `validate()` load by `SYS_ORG_ADMIN_API_KEY_UUID` /
    `TRIAL_ORG_VALIDATOR_API_KEY_UUID` through `findById`;
  - caches each for `FV_AUTH_CACHE_TTL_MS` (2 entries, research R9);
  - empty (revoked or expired) → fail with `ProblemException(403, "forbidden", "This operation's fixed caller key is
    revoked; restart to re-create it")`.
- [X] T050 [US4] Update `main/core/security/ApiKeyAuthHandler.java` (research R9); the gate already exists (T040):
  - the constructor also takes `FixedCallers`;
  - split `VALIDATE_PATHS` into `VALIDATE_ONLY_PATH` (`^/[^/]+/validate/?$`) and `EITHER_ROLE_PATHS`
    (`^/igs/[^/]+/[^/]+/(dependencies|conformance)/?$`);
  - in `rootHandler()`, keep the admin CIDR check first in both modes. In disabled mode, after the gate check, resolve
    the fixed caller (ADMIN for admin and
    either-role paths, VALIDATE for validate), check `ownerActive` (403), set `CONTEXT_KEY` / `CALLER_KEY`, call
    `repository.touch(id, ClientAddress.of(ctx))` and `ctx.next()`, never reading `Authorization`;
  - in enabled mode, everything stays as 4.1.
- [X] T051 [US4] Wire `FixedCallers` into `ApiKeyAuthHandler` in `main/ServiceRegistry.java`. Add `security = {aclSecurity, adminNetworkRestricted}` to
  `main/core/service/HealthService.java#checkHealth`. Register `fv_acl_security_disabled` in
  `main/core/metrics/ServiceMetrics.java`.

**Checkpoint**: part B is complete (US4–US6). All six stories pass on their own.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T052 [P] Register the gauge `fv_validator_contexts_inactive` in `main/core/metrics/ServiceMetrics.java`,
  refreshed after `ContextActivation.reconcile` and on each `ConfigSync` poll (count query from T037). Extend
  `ObservabilityIT` to assert both new gauges are exported (research R15).
- [X] T053 [P] Write `docs/upgrade-4.2.md` from contracts/operations.md §6: V14 and V15 run automatically and are
  additive (rolling upgrade OK); enable `FV_ENABLED_FHIR_VERSIONS` only after every instance runs 4.2; the
  restrict-versions procedure; and the switch-off / switch-on procedures with `FV_ACL_MODE_CHANGE_CONFIRMED`. Warn
  to turn on `FV_GLOBAL_DISABLED_ACL_SECURITY` only after every instance runs 4.2: a 4.1 instance has no mode guard
  and reinstalls the configured keys on every start, undoing the random keys.
- [X] T054 [P] Update `README.md`: add the three variables to the settings table (contracts/operations.md §1); add a
  "Public mode (access control disabled)" section with a security warning; note that `FV_AUTH_ADMIN_CIDRS` still
  applies, that usage is charged to the trial organisation, and that private content stays private; add a
  "Serving fewer FHIR versions" section.
- [X] T055 [P] Fill the `## [4.2.0]` entry in `CHANGELOG.md` with **Added** (both settings, the 422, `/health`
  fields, gauges, V14/V15) and **Security** (disabled mode is opt-in, the startup guard, random keys). No **Breaking**
  section. Link `docs/upgrade-4.2.md`.
- [X] T056 Measure SC-001 and SC-002 and record both in `perf/results-4.2.0.md`:
  - **SC-001**: 4.2.0 with the variables unset against 4.1.0. Run `perf/compare-run.sh` with `perf/validate.js` for
    p95 latency, plus heap after GC at readiness and time to readiness. Every figure must be within 5%.
  - **SC-002**: unset against `R4` (quickstart.md §F). Also record the combined size of the STU3, R4B and R5 core
    packages from the catalog, and confirm the heap saved is at least that.
- [X] T057 Run quickstart.md scenarios A–E manually against the built jar. Run the full `./mvnw verify` and confirm no
  new blocked-thread warnings (Principle I). Fix anything found.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none. T002–T004 are parallel; T005 needs T003 and T004; T006 needs T002.
- **Foundational (Phase 2)**: needs Phase 1. It blocks every story.
  - Part A needs T007, T008, T011–T013.
  - Part B needs T008, T009, T010, T012, T014, T015.
- **Part A**: US1 (Phase 3) → US2 (Phase 4) → US3 (Phase 5). US2 reuses `VersionParams` from US1. US3 reuses the
  422 from US2 in T030 and `VersionParams` from US1.
- **Part B**: T040 (gate) → US5 startup step (Phase 6) → US6 (Phase 7) → US4 (Phase 8). US6 extends
  `AclModeStartup` from US5, and its 503 test needs the gate. US4 needs the gate opened by US5.
- **Part A and part B are independent** after Phase 2. Both touch `Bootstrap.java` (T021/T035 vs T042),
  `HealthService.java` (T021/T037 vs T051) and `ServiceRegistry.java`, so merge those edits carefully if two people
  work on them.
- **Polish (Phase 9)**: after the stories it documents. T052 needs T037; T056–T057 need everything.

### Within each story

Tests first (they must fail), then models and repositories, then services and startup steps, then the HTTP layer,
then visibility.

---

## Parallel Example

```text
# Phase 1, after T001:
T002 openapi.yaml  |  T003 V14  |  T004 V15

# Phase 2 tests together:
T007 ServedVersionsTest  |  T008 ConfigLoaderTest  |  T009 ServiceSettingsRepositoryIT  |  T010 ApiKeyRepositoryIT

# After Phase 2, two developers:
Dev A: T017–T022 (US1) → T023–T027 (US2) → T028–T037 (US3)
Dev B: T038–T042 (US5) → T043–T046 (US6) → T047–T051 (US4)

# US3 tests together:
T028 ContextActivationIT core  |  T029 two-instance  |  T030 FR-032 cases  |  T031 AdminCliIT

# Polish docs together:
T053 upgrade-4.2.md  |  T054 README  |  T055 CHANGELOG
```

---

## Implementation Strategy

### MVP first (US1 + US2)

1. Phase 1 and Phase 2. With the settings unset the suite is green.
2. US1, then validate with `EnabledVersionsIT` and quickstart A.
3. US2, then validate with quickstart B. This is the P1 MVP: a deployment can serve only the versions it chooses and
   can't be extended at runtime.

### Incremental delivery

1. US3: stored contexts follow the setting (quickstart C). Part A is complete.
2. US5 → US6: the startup ACL step and the guarded switch (quickstart E, steps 1–4).
3. US4: public request handling (quickstart D). Part B is complete.
4. Phase 9: docs, metrics and evidence, then release 4.2.0.

Each checkpoint leaves `./mvnw verify` green and the service releasable with the new settings unset.

---

## Notes

- `[P]` means a different file and no dependency on an unfinished task.
- Every story's IT is written first and must fail before implementation.
- Never log a key value (env or generated). `NoSecretsInLogsTest` (T039) guards this.
- Do not edit applied migrations V1–V13.
- Commit after each task or logical group. Do not commit or push unless asked.
