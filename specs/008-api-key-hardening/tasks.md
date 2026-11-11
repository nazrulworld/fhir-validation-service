---

description: "Task list for 008-api-key-hardening"
---

# Tasks: API Key Hardening, Key-Only Validation, Usage Accounting and Well-Known Organisations

**Input**: Design documents from `specs/008-api-key-hardening/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R19), data-model.md, contracts/http-api.md,
contracts/operations.md, quickstart.md

**Tests**: Included. Constitution V requires:
- test-first development;
- real PostgreSQL (Testcontainers) for persistence and migrations;
- Mockito only for pure unit tests with no I/O;
- a contract or integration test for every endpoint whose behaviour changes.

Write each test task first and confirm it fails before the matching implementation task.

**Organization**: Tasks are grouped by the user stories in spec.md (US1–US8). Phases follow **dependency order**:
- US3 (re-key) runs before US2, because the seeding uses the new ids.
- US2 runs before US1, because the existing ITs need the trial validator key once validation becomes key-only.
- US7 is small and follows US1.
- The P2 and P3 stories follow.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: The user story the task belongs to (US1–US8)

## Path Conventions

- Production code: `src/main/java/nzi/fhir/validator/` (abbreviated `main/` below)
- Tests: `src/test/java/nzi/fhir/validator/` (abbreviated `test/` below)
- Contract: `src/main/resources/openapi.yaml`
- Migrations: `src/main/resources/db/migration/`
- Run one test with `./mvnw -q test -Dtest=<Class>`; run an IT with `./mvnw -q verify -Dit.test=<Class>`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Version, well-known constants and the fatal-startup path used by later phases.

- [X] T001 Bump the project version `4.0.0` → `4.1.0-SNAPSHOT` in `pom.xml` (research R1). Check whether anything else hard-codes the version: `grep -rn "4\.0\.0" README.md docker-compose.yml .env* 2>/dev/null`. Leave historical mentions alone.
- [X] T002 Rewrite `main/core/Constant.java` per data-model §4. This file already has the user's staged changes; edit on top of them, never revert them.
  - Keep `SYSTEM_ORG_*` and `TRIAL_ORG_*`.
  - Keep `SYS_ORG_ADMIN_API_KEY_ID/ROLE/NAME`.
  - Replace `SYS_ORG_VALIDATOR_API_KEY_*` with `TRIAL_ORG_VALIDATOR_API_KEY_ID = "00000000-0000-0000-0000-200000000002"`, `_NAME = "trial validator"`, `_ROLE = "VALIDATE"`.
  - Remove `SYS_ORG_API_KEY_OWNER`.
  - Set `SYS_ORG_API_KEY_EXPIRES = "2099-12-30T23:59:59Z"`.
  - Add parsed forms: `SYSTEM_ORG_UUID`, `TRIAL_ORG_UUID`, `SYS_ORG_ADMIN_API_KEY_UUID`, `TRIAL_ORG_VALIDATOR_API_KEY_UUID` (`UUID.fromString`) and `SYS_ORG_API_KEY_EXPIRES_AT` (`Instant.parse`).
  - Add a private constructor and short Javadoc on each group (research R16).
- [X] T003 Make `main/core/org/Organisations.java` delegate (research R16): `SYSTEM_ID = Constant.SYSTEM_ORG_UUID`, `DEFAULT_SYSTEM_SHORT_NAME = Constant.SYSTEM_ORG_SHORT_NAME`. Add `TRIAL_ID = Constant.TRIAL_ORG_UUID`. Run `./mvnw -q test`: unit tests that hard-code `…-000000000001` must now fail. List them; they are fixed in T011.
- [X] T004 [P] Create `main/core/StartupException.java` (`RuntimeException` with message and cause). In `main/Bootstrap.java` `main(...)`, after `start(...)` succeeds, attach `running.warmUp().onFailure(err -> { if (err instanceof StartupException) { logger().error("Startup failed: {}", err.getMessage(), err); vertx.close().onComplete(v -> System.exit(1)); } })`. Other warm-up failures keep today's behaviour of logging and staying unready (research R14).
- [X] T005 [P] In `main/core/config/ConfigLoader.java`, refactor `DEPRECATED` from `List<String>` into an ordered `Map<String, String>` (name → reason). This step doesn't change behaviour: the two existing entries keep their current WARN text exactly. `ConfigLoaderTest` must stay green.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The V10 schema (it must exist before V11 and V12) and test-harness plumbing.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T006 Create `src/main/resources/db/migration/V10__api_key_expiry_and_audit.sql` (data-model §1):
  - `ALTER TABLE fhir_validator_schema.api_keys ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL, ADD COLUMN IF NOT EXISTS last_used_ip INET NULL;`
  - a header comment: "4.1.0 (spec 008): optional key expiry (NULL = never) and the client address of the last use."
- [X] T007 Add `v10AddsExpiryAndLastUsedIp` to `test/core/service/MigrationUpgradeIT.java`. Migrate to target `9`, insert a key, migrate to `10`, then assert that both columns exist, are nullable and are NULL on the existing row. Also extend `assertApiKeysTable` (it checks the latest schema) to expect `expires_at` and `last_used_ip`.

**Checkpoint**: `./mvnw -q verify -Dit.test=MigrationUpgradeIT` is green.

---

## Phase 3: User Story 3 - Upgrading a 4.0 database re-keys the system organisation (Priority: P1)

**Goal**: V11 moves the system org to `00000000-0000-0000-0000-100000000001` with every owner reference, seeds the
trial org and renames the bootstrap key.

**Independent Test**: migrate a database holding V9 data → every owner and default uses the new id; the old id is
gone; there is still only one system org.

### Tests for User Story 3 ⚠️

- [X] T008 [US3] In `test/core/service/MigrationUpgradeIT.java`, add `v11ReKeysTheSystemOrganisation`:
  1. Migrate to target `10`.
  2. Insert one `api_keys` row named `bootstrap`, one VALIDATE key, one `fhir_implementation_guides` row, one `fhir_validator_snapshots` row, and one non-system org with its own key. Rows owned by the system use `owner` = `'00000000-0000-0000-0000-000000000001'` or the default.
  3. Migrate to the latest version.

  Then assert:
  - every row formerly owned by `…-000000000001` now has owner `…-100000000001`, and the non-system org's rows are untouched;
  - `SELECT count(*) FROM organisations WHERE id = '…-000000000001'` is 0;
  - `information_schema.columns.column_default` of the three `owner` columns contains `100000000001`;
  - the three FKs `api_keys_owner_fkey`, `fhir_implementation_guides_owner_fkey` and `fhir_validator_snapshots_owner_fkey` exist with `ON DELETE RESTRICT` (`pg_constraint.confdeltype = 'r'`);
  - inserting `type='SYSTEM'` with another id fails with a check violation;
  - the trial org exists: `(…-100000000002, 'trial', 'Free trial validation service', 'NON_COMMERCIAL', is_active = true)`;
  - the former `bootstrap` row now has `id = '00000000-0000-0000-0000-200000000001'` and `name = 'system administrator'`, with the same `key_hash`.
- [X] T009 [P] [US3] In `test/core/service/MigrationUpgradeIT.java`, add `v11LeavesAnExistingTrialNameAlone`: before V11, insert an org with `name_short = 'trial'` and a random id. After the migration, that org is unchanged, and no org has id `…-100000000002`. The startup check in US2 reports the clash. Add `v11SkipsTheBootstrapRenameWhenTheNameIsTaken`: a key named `system administrator` already exists, so the `bootstrap` row keeps its id and name.
- [X] T010 [P] [US3] Create `test/core/ConstantMigrationConsistencyTest.java` (pure unit test). Read `src/main/resources/db/migration/V11__well_known_organisations.sql` from the classpath and assert that it contains each of `Constant.SYSTEM_ORG_ID`, `TRIAL_ORG_ID`, `TRIAL_ORG_SHORT_NAME`, `TRIAL_ORG_LONG_NAME`, `TRIAL_ORG_TYPE`, `SYS_ORG_ADMIN_API_KEY_ID` and `SYS_ORG_ADMIN_API_KEY_NAME` as quoted SQL literals (research R16).
- [X] T011 [US3] Update the existing tests that assumed the old id:
  - In `MigrationUpgradeIT`, make `v8CreatesTheSystemOrganisation`, `v8MakesExistingDataSystemOwnedAndShareable` and `v8ProtectsTheSystemOrganisation` migrate to target `8` (not the latest) when they assert `…-000000000001`, or assert the new id when they check the latest schema.
  - Fix the failures listed in T003. Find them with `grep -rn "000000000001" src/test`.

### Implementation for User Story 3

- [X] T012 [US3] Create `src/main/resources/db/migration/V11__well_known_organisations.sql` (research R13, data-model §2). Start it with a header comment: "4.1.0 (spec 008): re-keys the system organisation to its well-known id. NOT rolling-upgrade safe: stop every 4.0 instance first (docs/upgrade-4.1.md)." Then, in order:
  1. `ALTER TABLE fhir_validator_schema.organisations DROP CONSTRAINT organisations_system_id_chk;`
  2. Drop the FKs `api_keys_owner_fkey`, `fhir_implementation_guides_owner_fkey` and `fhir_validator_snapshots_owner_fkey`. Use plain `DROP CONSTRAINT`, without `IF EXISTS`, so a differently named FK fails the migration loudly.
  3. `UPDATE organisations SET id = '00000000-0000-0000-0000-100000000001' WHERE id = '00000000-0000-0000-0000-000000000001';`
  4. `UPDATE` the `owner` column of the three tables from the old id to the new one.
  5. Re-add the three FKs exactly as in V8 (`FOREIGN KEY (owner) REFERENCES fhir_validator_schema.organisations (id) ON DELETE RESTRICT`), with the same names.
  6. `ALTER COLUMN owner SET DEFAULT '00000000-0000-0000-0000-100000000001'` on the three tables.
  7. Re-add `organisations_system_id_chk CHECK ((type = 'SYSTEM') = (id = '00000000-0000-0000-0000-100000000001'))`.
  8. `INSERT` the system org `('…-100000000001','system','FHIR Validation Service','SYSTEM',true)` and the trial org `('…-100000000002','trial','Free trial validation service','NON_COMMERCIAL',true)`, each `ON CONFLICT DO NOTHING`. This form, with no conflict target, also skips a clashing short name.
  9. `UPDATE api_keys SET id = '00000000-0000-0000-0000-200000000001', name = 'system administrator' WHERE name = 'bootstrap' AND NOT EXISTS (SELECT 1 FROM api_keys WHERE name = 'system administrator' OR id = '00000000-0000-0000-0000-200000000001');`

  Make T008–T010 pass.

**Checkpoint**: `MigrationUpgradeIT` and `ConstantMigrationConsistencyTest` are green.

---

## Phase 4: User Story 2 - Well-known organisations and the trial validator key (Priority: P1)

**Goal**: Startup ensures both orgs, installs the admin key and (when configured) the trial validator key by fixed
ids, and fails loudly on clashes.

**Independent Test**: start on an empty database with `FV_VALIDATOR_API_KEY` → both orgs and both keys exist; the
trial key validates and gets 403 on admin paths; a restart changes nothing.

### Tests for User Story 2 ⚠️

- [X] T013 [US2] Create `test/BootstrapOrganisationsIT.java` (Testcontainers, `AppHarness` with `auth.validatorKey`). Cases:
  - `wellKnownOrganisationsExistAfterStartup`: both orgs have the `Constant` ids, names and types.
  - `trialValidatorKeyIsInstalled`: the row has id `TRIAL_ORG_VALIDATOR_API_KEY_ID`, name `trial validator`, role `VALIDATE`, owner `TRIAL_ORG_ID` and `expires_at = 2099-12-30T23:59:59Z`.
  - `adminKeyHasTheWellKnownId`: id `…-200000000001`, name `system administrator`.
  - `trialKeyValidatesButIsForbiddenOnAdminPaths`: `POST /R4/validate` returns 200, and `POST /R4/validator` returns 403.
  - `restartIsIdempotent`: stop, start again on the same database, then compare full row dumps of `organisations` and `api_keys` (excluding `last_used_*`).
  - `rotatingTheTrialKeyReplacesItsHash`: restart with another key; the old key gets 401 and the new one 200.
  - `clashingTrialNameFailsStartup`: on a fresh container, run Flyway with `target("10")`, insert the org `('<random uuid>', 'trial', 'Someone else', 'COMMERCIAL')`, then start `AppHarness`. V11 skips the trial seed, and `warmUp()` fails with a `StartupException` whose message contains `trial`.
  - `malformedTrialKeyFailsStartup`: `auth.validatorKey = "short"` → `StartupException`.
  - `missingTrialKeyLogsAWarning`: `LogCapture` contains `FV_VALIDATOR_API_KEY is not set`.
  - `skippedBootstrapRenameFailsWithClearMessage`: migrate to `10`, insert keys `bootstrap` (hash of `ADMIN_KEY`) and `system administrator` (another hash), then start with `FV_ADMIN_API_KEY = ADMIN_KEY`. `warmUp()` fails with a `StartupException` whose message names `api_keys_key_hash_key` or `api_keys_name_key` and `system administrator`, and never contains the secret.
- [X] T014 [P] [US2] Extend `test/core/config/NoSecretsInLogsTest.java` (or the safe-dump test in `ConfigLoaderTest`) so the `auth.validatorKey` value never appears in `ApplicationConfig`'s safe dump or the startup log.
- [X] T015 [P] [US2] Extend `test/cli/AdminCliIT.java`: `keys create --name "system administrator"` and `--name "trial validator"` exit with the conflict code and a reserved-name message, and so do `keys revoke "system administrator"` and `keys revoke "trial validator"` (message names `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY`; the key stays active). `bootstrap` is no longer reserved.

### Implementation for User Story 2

- [X] T016 [US2] In `main/core/config/ConfigLoader.java`, add `new Entry("FV_VALIDATOR_API_KEY", "auth.validatorKey", Kind.STRING, NO_MIN, NO_MAX)`. In `main/core/config/ApplicationConfig.java`, add `validatorKey()` and remove `auth.validatorKey` in the safe dump next to `bootstrapAdminKey` (line ~152).
- [X] T017 [US2] In `main/core/security/ApiKeyRepository.java`, add `Future<Void> upsertWellKnown(UUID id, String name, String key, Role role, UUID owner, Instant expiresAt)` (research R15):
  - SQL: `INSERT INTO api_keys (id, name, key_hash, role, owner, expires_at) VALUES ($1..$6) ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, key_hash = EXCLUDED.key_hash, role = EXCLUDED.role, owner = EXCLUDED.owner, expires_at = EXCLUDED.expires_at, revoked_at = NULL`.
  - A malformed key → `failedFuture(new StartupException("<name>: API keys must be 32-128 characters of [A-Za-z0-9_-]"))`.
  - A PG unique violation (`23505`, constraint `api_keys_name_key` or `api_keys_key_hash_key`) → `StartupException` naming the conflicting constraint and the well-known key name (never the secret).
  - Re-implement `upsertBootstrap(key)` as `upsertWellKnown(Constant.SYS_ORG_ADMIN_API_KEY_UUID, Constant.SYS_ORG_ADMIN_API_KEY_NAME, key, Role.ADMIN, Constant.SYSTEM_ORG_UUID, Constant.SYS_ORG_API_KEY_EXPIRES_AT)`.
  - Remove `BOOTSTRAP_NAME`, and add `RESERVED_NAMES = Set.of(SYS_ORG_ADMIN_API_KEY_NAME, TRIAL_ORG_VALIDATOR_API_KEY_NAME)`.
- [X] T018 [US2] In `main/core/org/OrganisationRepository.java`, add `Future<Void> ensureWellKnown()`:
  - Two `INSERT … (id, name_short, name_long, type, is_active) VALUES (…, true) ON CONFLICT (id) DO NOTHING`, one for the system org and one for the trial org, using the `Constant` values.
  - A `23505` on `organisations_name_short_uq` → `StartupException("an organisation named '<name>' already exists with another id; rename it before starting 4.1.0")`.
  - Existing rows are never updated.
- [X] T019 [US2] In `main/Bootstrap.java`, change the warm-up chain to: `migrate → services.organisations().ensureWellKnown() → installSystemOrganisation → installBootstrapKey → installTrialValidatorKey → startTerminology → configSync.start`.
  - `installTrialValidatorKey`:
    - if `config.validatorKey()` is null or blank → WARN `FV_VALIDATOR_API_KEY is not set: validation requires keys created with 'keys create'`;
    - otherwise `apiKeys().upsertWellKnown(TRIAL_ORG_VALIDATOR_API_KEY_UUID, TRIAL_ORG_VALIDATOR_API_KEY_NAME, key, Role.VALIDATE, TRIAL_ORG_UUID, SYS_ORG_API_KEY_EXPIRES_AT)`, then INFO `Trial validator API key installed`.
  - Update `installBootstrapKey`'s log text.
- [X] T020 [US2] In `main/cli/KeyCommands.java`, replace the `BOOTSTRAP_NAME` guard with `ApiKeyRepository.RESERVED_NAMES` (case-insensitive). Message: `The key name '<n>' is reserved for a well-known key`. `keys revoke` on a reserved name also exits with the conflict code and the message `'<n>' is installed from configuration; rotate or unset FV_ADMIN_API_KEY / FV_VALIDATOR_API_KEY and restart` (spec Edge Cases; data-model §1).
- [X] T021 [US2] In `test/testsupport/AppHarness.java`, add `public static final String VALIDATOR_KEY = "test-validator-key-0123456789abcdefghijklmn"` and `VALIDATOR_AUTH`, put `validatorKey` in the default `auth` config, and add a helper `validatorAuth(HttpRequest<?>)` like the existing admin one.

**Checkpoint**: `BootstrapOrganisationsIT`, `AdminCliIT` and `NoSecretsInLogsTest` are green; `mvn verify` is green
(validation is still anonymous at this point).

---

## Phase 5: User Story 1 - Validation always requires a key (Priority: P1) 🎯 MVP

**Goal**: Validate and the package reads need VALIDATE or ADMIN; `FV_AUTH_ANONYMOUS_VALIDATE` is removed and
warned about.

**Independent Test**: no key → 401; VALIDATE key → 200; ADMIN key → 200; the old setting → WARN and still 401.

### Tests for User Story 1 ⚠️

- [X] T022 [US1] Update `test/web/ApiKeyAuthIT.java`:
  - Rename `adminOperationsNeedAnAdminKeyAndValidationIsAnonymousByDefault` to `adminOperationsNeedAnAdminKeyAndValidationNeedsAKey`. It now expects 401 (with `WWW-Authenticate: Bearer`) for no-key validate and for `GET /igs/{n}/{v}/dependencies` and `/conformance`, and 200 with the validator key and with the admin key.
  - In `theValidatorPathIsAdministrativeAndTheOldPathIsGone`, change every anonymous-validate expectation of 200 to sending `VALIDATOR_AUTH`.
  - Fold `authenticatedValidationModeRequiresAKeyAndHonoursRevocation` into the default mode, keeping the revocation part.
  - Drop every `anonymousValidate` override.
- [X] T023 [P] [US1] In `test/core/config/ConfigLoaderTest.java`, add `anonymousValidateIsIgnoredWithAWarning`: with env `FV_AUTH_ANONYMOUS_VALIDATE=true`, `LogCapture` contains `FV_AUTH_ANONYMOUS_VALIDATE is deprecated and ignored: removed in 4.1.0, validation always requires a VALIDATE or ADMIN key`, and the loaded config has no `auth.anonymousValidate`. Remove any existing assertion on that key.
- [X] T024 [P] [US1] Contract check in `test/web/ApiKeyAuthIT.java` (`openApiDeclaresKeyedOperations`): parse `openapi.yaml` from the classpath and assert:
  - `security` contains `apiKey` on the operationIds `validationApiValidate`, `igPackageApiGetDependenciesGraph`, `igPackageApiGenerateConformanceReport`, `validatorApiIncludeIg`, `validatorApiExcludeIg`, `validatorApiChangeIncludedIg`, `igPackageApiUploadIg`, `igPackageApiRegisterIg` and `igPackageApiChangeAccess`;
  - it is absent on the health and metrics operations;
  - each keyed operation lists `401`.

### Implementation for User Story 1

- [X] T025 [US1] In `main/core/security/ApiKeyAuthHandler.java`:
  - remove the `anonymousValidate` field and the constructor parameters (both constructors);
  - change `authorise(ctx, Role... allowed)` (no anonymous flag), so a missing bearer → `unauthorized(ctx)`;
  - `VALIDATE_PATHS` → `authorise(ctx, Role.VALIDATE, Role.ADMIN)`;
  - update the class and `rootHandler` Javadoc.

  `caller(ctx)` keeps returning `Caller.ANONYMOUS` for open paths.
- [X] T026 [US1] Remove `FV_AUTH_ANONYMOUS_VALIDATE` from the entries in `main/core/config/ConfigLoader.java` and add it to the `DEPRECATED` map (T005) with the reason `removed in 4.1.0, validation always requires a VALIDATE or ADMIN key`. Make sure the WARN format renders `<NAME> is deprecated and ignored: <reason>`; for the two existing entries, keep their text exactly as it is today. Remove `anonymousValidate()` from `main/core/config/ApplicationConfig.java`, and update `main/ServiceRegistry.java:85` to `new ApiKeyAuthHandler(apiKeys, config.authCacheTtlMs())`.
- [X] T027 [US1] Update `src/main/resources/openapi.yaml` per contracts/http-api.md §1–§3:
  - `security: [{apiKey: []}]` on the 9 keyed operations;
  - the new `apiKey` description text;
  - the `401` description on validate and the reads: "no valid API key was sent (missing, malformed, unknown, revoked or expired)";
  - add `401` and `403` `$ref Forbidden` wherever an ADMIN operation lacks them;
  - extend the `Forbidden` description with the admin-network case.

  Don't register a security handler: `HttpVerticle` keeps `setRequireSecurityHandlers(false)` (research R9).
- [X] T028 [US1] Make every other IT that validates or reads packages without a key send `AppHarness.VALIDATOR_AUTH` (or the admin key where the test is about admin-owned content). Find them with `grep -rln "/validate\|/dependencies\|/conformance" src/test/java`. Likely files: `IgScopedValidationIT`, `OwnershipValidationIT`, `ObservabilityIT`, `RequestTimeoutIT`, `EventLoopResponsivenessIT`, `GracefulShutdownIT`, `TerminologySettingIT`, `IncludedOnlyValidationIT`, `BootstrapIT`, the `web/endpoint` tests, and `AdminCliIT`, which mentions `anonymousValidate`. Tests about access by organisation must keep using the organisation's own key, not the trial key.
- [X] T029 [P] [US1] Update `perf/validate.js` and `perf/validate-during-ig-load.js` (and `perf/validate-terminology.js`) to send `Authorization: Bearer ${__ENV.FV_KEY}`, and document `FV_KEY` in `perf/README.md`.

**Checkpoint**: `mvn verify` is green with key-only validation. This is the MVP: US3 + US2 + US1.

---

## Phase 6: User Story 7 - Cheap rejection of malformed tokens (Priority: P3)

**Goal**: A malformed bearer gets 401 with no hashing, cache access or lookup.

**Independent Test**: a malformed token → 401 and zero repository calls.

- [X] T030 [US7] In `test/core/security/ApiKeyAuthHandlerTest.java`, add `malformedTokenIsRefusedWithoutLookup`. Use a Mockito `ApiKeyRepository` (pure unit test) and a routing context for `POST /R4/validate`. Try the bearer values `x`, `a b`, 31 chars, 129 chars and `ü`×40. Each gives 401, `verifyNoInteractions(repository)`, and `cachedKeys() == 0`. Also add `wellFormedUnknownKeyIsLookedUp` (calls `findActive` once).
- [X] T031 [US7] In `main/core/security/ApiKeyAuthHandler.java` `authorise`, after `bearer(...)`, return `unauthorized(ctx)` when `!ApiKeyRepository.isWellFormed(key)`, before `lookup(key)` (research R2).

---

## Phase 7: User Story 4 - Admin network allowlist and trusted proxies (Priority: P2)

**Goal**: One resolved client address per request. Administrative paths are refused with 403 outside
`FV_AUTH_ADMIN_CIDRS`, before the key lookup.

**Independent Test**: contracts/http-api.md §4 and quickstart §4.

### Tests for User Story 4 ⚠️

- [X] T032 [P] [US4] Create `test/core/security/CidrTest.java`. Cover:
  - v4 `10.0.0.0/8` contains `10.255.1.2` but not `11.0.0.1`;
  - `/32` matches only the exact address;
  - `0.0.0.0/0` matches every v4 address;
  - v6 `2001:db8::/32`, `::/0` and `/128`;
  - IPv4-mapped `::ffff:10.1.2.3` matches `10.0.0.0/8`;
  - a bare address is a host network;
  - invalid input throws `IllegalArgumentException` with the entry in the message: `10.0.0.0/33`, `2001:db8::/129`, `example.com`, `10.0.0`, the empty string and `1.2.3.4/x`;
  - parsing never performs DNS (a name input is rejected before `InetAddress.getByName`);
  - `CidrList.parse("10.0.0.0/8, 192.168.0.0/16")` handles whitespace and commas, and `CidrList.EMPTY.isEmpty()`.
- [X] T033 [P] [US4] Create `test/core/security/ClientAddressTest.java` (pure, calling `ClientAddress.resolve(String peer, List<String> forwarded, List<String> xff, CidrList trusted)`). Cover:
  - no trusted proxies → the peer, headers ignored;
  - an untrusted peer with a spoofed XFF → the peer;
  - trusted peer `127.0.0.1` with `XFF: 203.0.113.7` → `203.0.113.7`;
  - `XFF: 10.1.1.1, 203.0.113.7` with trusted `127.0.0.1/32` → `203.0.113.7` (right-most untrusted);
  - `XFF: 203.0.113.7, 10.0.0.5` with trusted `127.0.0.1/32,10.0.0.0/8` → `203.0.113.7`;
  - all hops trusted → the left-most;
  - `Forwarded: for=198.51.100.1;proto=https, for="[2001:db8::1]:4711"` takes precedence over XFF → `2001:db8::1`;
  - `for=unknown` stops the walk → the last trusted hop;
  - ports are stripped from `203.0.113.7:5555`;
  - several XFF header lines are joined in order.
- [X] T034 [US4] Add to `test/web/ApiKeyAuthIT.java` (`start(vertx, authOverrides)`). The test client connects from `127.0.0.1`. Cases:
  - `adminFromOutsideTheAllowlistIsForbiddenBeforeLookup` (`adminCidrs=10.0.0.0/8`, admin key → 403 `forbidden` with the network message; an invalid key gets the same 403);
  - `adminFromInsideTheAllowlistIsServed` (`127.0.0.0/8` → 200);
  - `noAllowlistMeansNoRestriction`;
  - `trustedProxyForwardedAddressIsUsed` (`trustedProxies=127.0.0.1/32`, `adminCidrs=10.0.0.0/8`, `XFF: 10.1.2.3` → 200; `XFF: 10.1.2.3, 198.51.100.9` → 403);
  - `spoofedForwardedHeaderIsIgnoredWithoutTrustedProxies` (`XFF: 10.1.2.3` → 403);
  - `validationIsNotAffectedByTheAdminAllowlist` (the validator key still gets 200).
- [X] T035 [P] [US4] In `test/core/config/ConfigLoaderTest.java`, add `invalidAdminCidrFailsStartup` and `invalidTrustedProxyFailsStartup`. Each message contains `invalid FV_AUTH_ADMIN_CIDRS entry '10.0.0.0/33'` (or the trusted-proxies variant).

### Implementation for User Story 4

- [X] T036 [P] [US4] Create `main/core/security/Cidr.java`, a record `(byte[] network, int prefix)` with `static Cidr parse(String)` and `boolean contains(InetAddress)` (research R4):
  - accept only `[0-9A-Fa-f:.]` plus an optional `/n` before calling `InetAddress.getByName`;
  - normalise IPv4-mapped v6 to v4;
  - mask the network bits on parse;
  - compare whole bytes plus a final bit mask.

  Then create `main/core/security/CidrList.java`: an immutable `List<Cidr>`, `static CidrList parse(String commaSeparated)` (null or blank → `EMPTY`), `contains(InetAddress)` and `isEmpty()`.
- [X] T037 [US4] Create `main/core/security/ClientAddress.java` (research R3):
  - `public static final String KEY = "clientAddress"`;
  - a pure `static InetAddress resolve(...)` as tested in T033;
  - `static InetAddress of(RoutingContext ctx)` (falls back to `remoteAddress()` when unset);
  - `static Handler<RoutingContext> handler(CidrList trusted)`, which stores the result under `KEY` and calls `ctx.next()`;
  - when `remoteAddress()` is null (unit contexts), use the loopback address.
- [X] T038 [US4] In `main/core/config/ConfigLoader.java`, add `FV_AUTH_ADMIN_CIDRS` → `auth.adminCidrs` and `FV_AUTH_TRUSTED_PROXIES` → `auth.trustedProxies` (`Kind.STRING`). Validate both at load time with `CidrList.parse`, rethrowing as the loader's startup error `invalid <VAR> entry '<entry>'`. In `ApplicationConfig`, add `CidrList adminCidrs()` and `CidrList trustedProxies()`.
- [X] T039 [US4] In `main/web/HttpVerticle.java`, mount `routerBuilder.rootHandler(ClientAddress.handler(services.config().trustedProxies()))` right after `RequestIdHandler` and before the auth handler.
- [X] T040 [US4] In `main/core/security/ApiKeyAuthHandler.java`, add a `CidrList adminCidrs` constructor argument (wired from `ServiceRegistry`). In `rootHandler`, for `isAdministrative(ctx)`: if `!adminCidrs.isEmpty() && !adminCidrs.contains(ClientAddress.of(ctx))`, answer `ErrorResponder.fail(ctx, new ProblemException(403, "forbidden", "Administrative operations are not allowed from this network"))` before `authorise`.

**Checkpoint**: `CidrTest`, `ClientAddressTest`, `ConfigLoaderTest` and `ApiKeyAuthIT` are green.

---

## Phase 8: User Story 5 - Key expiry and last-used address (Priority: P2)

**Goal**: Keys can expire (also from cache), and the key list shows expiry and the last-used client address.

**Independent Test**: quickstart §5.

### Tests for User Story 5 ⚠️

- [X] T041 [US5] Add to `test/web/ApiKeyAuthIT.java`:
  - `expiredKeyIsRefusedEvenWhenCached`: `cacheTtlMs=60000`. Create a VALIDATE key through the repository with `expiresAt = now + 2 s`; validate → 200; wait with `Await` until expiry + 100 ms; validate → 401.
  - `keyWithoutExpiryNeverExpires`.
  - `lastUsedIpIsRecorded`: after a validate, `SELECT host(last_used_ip)` is `127.0.0.1`. With `trustedProxies=127.0.0.1/32` and `XFF: 203.0.113.9`, a second request with the same key updates the column to `203.0.113.9` immediately, without waiting for the 60 s throttle.
- [X] T042 [P] [US5] In `test/core/security/ApiKeyAuthHandlerTest.java`, add `cacheEntryNeverOutlivesKeyExpiry`: the mock `findActive` returns a `KeyInfo` with `expiresAt = now + 50 ms`, and `cacheTtlMs` is 60 000. After 60 ms the next request calls `findActive` again.
- [X] T043 [P] [US5] Extend `test/cli/AdminCliIT.java`:
  - `keys create … --expires 2099-01-01T00:00:00Z`, then `keys list` shows `"expiresAt":"2099-01-01T00:00:00Z"` and `"lastUsedIp":null`;
  - `--expires 2000-01-01T00:00:00Z` gives a usage error (exit 2);
  - `--expires tomorrow` gives a usage error;
  - the seeded keys list `"expiresAt":"2099-12-30T23:59:59Z"`.

### Implementation for User Story 5

- [X] T044 [US5] In `main/core/security/ApiKeyRepository.java` (data-model §1):
  - `KeyInfo` becomes `(UUID id, String name, Role role, UUID ownerId, String ownerShortName, boolean ownerActive, Instant expiresAt)`.
  - `findActive` selects `k.name, k.expires_at` and adds `AND (k.expires_at IS NULL OR k.expires_at > now())`.
  - `create(name, key, role, owner)` gains an overload with `Instant expiresAt`; the CLI passes `null` for none.
  - `KeySummary` gains `Instant expiresAt` and `String lastUsedIp` (`host(k.last_used_ip)`), with JSON keys `expiresAt` and `lastUsedIp`.
- [X] T045 [US5] In `ApiKeyRepository.java`, replace `touch(UUID)` with `touch(UUID id, InetAddress ip)` (research R7):
  - Write when 60 s have passed since the last write **or** the address differs from the last one written for that key.
  - SQL: `UPDATE … SET last_used_at = now(), last_used_ip = $2::inet WHERE id = $1`.
  - Replace the `ConcurrentHashMap` with a synchronized access-order `LinkedHashMap<UUID, Touched(long at, String ip)>` capped at 10 000 entries (`removeEldestEntry`).
- [X] T046 [US5] In `main/core/security/ApiKeyAuthHandler.java`:
  - cache positive results until `min(now + cacheTtlMs, expiresAt.toEpochMilli())` when `expiresAt` is non-null;
  - call `repository.touch(info.id(), ClientAddress.of(ctx))`.
- [X] T047 [US5] In `main/cli/KeyCommands.java`, add `--expires <ISO-8601 instant>` to `create`. Parse it with `Instant.parse`; a parse failure or a value `<= now` → `CliException.usage("--expires must be a future ISO-8601 instant, e.g. 2027-01-01T00:00:00Z")`. Update the help text and the `list` output (fields come from `KeySummary.toJson`).

**Checkpoint**: `ApiKeyAuthIT`, `ApiKeyAuthHandlerTest` and `AdminCliIT` are green.

---

## Phase 9: User Story 6 - Usage accounting per key and organisation (Priority: P2)

**Goal**: Exactly one usage record per validation request that passed authorisation, sent to the configured sink
without blocking. Plus aggregate metrics.

**Independent Test**: quickstart §6.

### Tests for User Story 6 ⚠️

- [X] T048 [P] [US6] Create `test/core/usage/UsageMeterTest.java` (pure; inject a fake `LongSupplier` nano clock). Cover:
  - phase arithmetic per research R10: `queueMs` = admission wait + executor wait, `contextMs`, `validateMs` and `totalMs`;
  - unreached phases are -1;
  - emits exactly once when the response ends before the work and when it ends after;
  - emits on response end when the work never started (outcome `rejected`, or `aborted` when the end was a reset);
  - outcome `timeout` when the response status is 503 from the request timeout and the work is still pending;
  - the timeout fires, then the worker finishes with `valid` → outcome `timeout`, CPU > 0;
  - a reset, then the worker finishes with `invalid` → outcome `aborted`;
  - metrics-only mode (sink `none`) captures CPU and allocation but never calls the sink;
  - CPU/alloc -1 when the `ThreadMetrics` probe reports unsupported;
  - `UsageMeter.NONE` does nothing.
- [X] T049 [P] [US6] Create `test/core/usage/LogUsageWriterTest.java`. Use `LogCapture` on logger `nzi.fhir.validator.usage` and write one record. Assert:
  - exactly one INFO line, parseable as JSON;
  - its keys equal contracts/operations.md §4: `requestId, startedAt, apiKeyId, apiKeyName, orgId, fhirVersion, ig, profiles, contentType, requestBytes, outcome, httpStatus, issues{fatal,error,warning,information}, clientIp, totalMs, queueMs, contextMs, validateMs, cpuTimeNs, allocatedBytes`;
  - no other logger receives it;
  - rendering the event with the `UsageConsole` layout from `log4j2.xml` gives a line that, minus the newline, parses as JSON.
- [X] T050 [P] [US6] Create `test/core/usage/DbUsageWriterTest.java` (Testcontainers, migrated schema, `vertx-junit5`). Cover:
  - `flushesWhenBatchSizeIsReached` (batch 10, flush 60 000 ms; 10 writes → 10 rows within 1 s);
  - `flushesOnTimer` (batch 500, flush 200 ms; 3 writes → 3 rows);
  - `dropsAndCountsWhenQueueIsFull` (queue 100, pool paused or flush 60 000 ms; 150 writes → `fv.usage.dropped{reason=queue_full}` = 50, and `write` never blocks: 150 writes take < 50 ms);
  - `failedWriteIsCountedAndNotRetried` (drop the table, write 5, flush → `fv.usage.dropped{reason=write_failed}` = 5 and one WARN; recreate the table, write 1 → exactly 1 row);
  - `closeFlushesRemainingRecords`;
  - `deletingTheKeyKeepsRowsWithNullKey` (`ON DELETE SET NULL`).
- [X] T051 [US6] Create `test/web/UsageIT.java` (`AppHarness` with overrides on `usage.sink`). Cover:
  - `dbSinkRecordsOneRowPerValidation`: validate with `VALIDATOR_AUTH`; wait for the row; `api_key_id = TRIAL_ORG_VALIDATOR_API_KEY_ID`, `org_id = TRIAL_ORG_ID`, `outcome` in (`valid`, `invalid`), `total_ms >= validate_ms >= 0`, `cpu_time_ns > 0`, `allocated_bytes > 0`, `client_ip = 127.0.0.1`, `request_id` equals the response's request-id header, `http_status = 200`;
  - `unparseableBodyIsRecorded` (outcome `unparseable`, status 400);
  - `rejectedRequestIsRecorded` (415 → outcome `rejected`, `validate_ms = -1`);
  - `unauthenticatedRequestIsNotRecorded` (401 → no row);
  - `timeoutIsRecordedWithCpu` (use the request-timeout setup from `RequestTimeoutIT`: outcome `timeout`, status 503, exactly one row after the worker finishes);
  - `logSinkWritesOneLine` (`LogCapture`);
  - `noneSinkWritesNothing`;
  - `bothSinks` (`log,db`): one row and one line.
- [X] T052 [P] [US6] Extend `test/web/ObservabilityIT.java`:
  - after one validation, the scrape contains `fv_validation_cpu_seconds_count{…outcome=…,version="R4"}` and `fv_validation_allocated_bytes_count`;
  - with the `db` sink, `fv_usage_written_total` and `fv_usage_queue_size`;
  - no series carries a `key`, `org` or `client_ip` label.
- [X] T053 [P] [US6] In `test/core/config/ConfigLoaderTest.java`:
  - `FV_USAGE_SINK` accepts `none`, `log`, `db`, `log,db` and `db,log`, and rejects `kafka` and `none,db`;
  - the bounds `FV_USAGE_QUEUE_SIZE` 100–1 000 000, `FV_USAGE_BATCH_SIZE` 10–5 000 and `FV_USAGE_FLUSH_MS` 100–60 000 are enforced;
  - the defaults are 10 000, 500 and 1 000;
  - `FV_USAGE_BATCH_SIZE` greater than `FV_USAGE_QUEUE_SIZE` (e.g. 500 and 100) fails startup with `FV_USAGE_BATCH_SIZE (500) must not exceed FV_USAGE_QUEUE_SIZE (100)`.

### Implementation for User Story 6

- [X] T054 [US6] Create `src/main/resources/db/migration/V12__validation_usage.sql`, with the table `fhir_validator_schema.validation_usage` exactly as data-model §3:
  - `id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`
  - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
  - `started_at TIMESTAMPTZ NOT NULL`
  - `request_id VARCHAR(64) NOT NULL`
  - `api_key_id UUID NULL REFERENCES fhir_validator_schema.api_keys (id) ON DELETE SET NULL`
  - `api_key_name VARCHAR(64) NULL`
  - `org_id UUID NOT NULL` (no FK)
  - `fhir_version VARCHAR(16) NULL`
  - `ig VARCHAR(192) NULL`
  - `profile_count SMALLINT NOT NULL`
  - `content_type VARCHAR(16) NULL`
  - `request_bytes INTEGER NOT NULL`
  - `outcome VARCHAR(32) NOT NULL`
  - `http_status SMALLINT NOT NULL`
  - `issues_fatal`, `issues_error`, `issues_warning` and `issues_information`, each `INTEGER NOT NULL DEFAULT 0`
  - `client_ip INET NULL`
  - `total_ms`, `queue_ms`, `context_ms` and `validate_ms`, each `INTEGER NOT NULL`
  - `cpu_time_ns BIGINT NOT NULL`
  - `allocated_bytes BIGINT NOT NULL`

  Also create the indexes `validation_usage_key_created_idx (api_key_id, created_at)` and `validation_usage_org_created_idx (org_id, created_at)`, and add a header comment that says retention is the operator's job. Add `v12CreatesValidationUsage` to `test/core/service/MigrationUpgradeIT.java`.
- [X] T055 [US6] Extend `main/core/security/Caller.java` with the components `UUID apiKeyId` and `String apiKeyName` (data-model §5):
  - `Caller.ANONYMOUS` and `Caller.system(...)` set them to null;
  - add `Caller.of(UUID orgId, String orgShortName, Role role, UUID apiKeyId, String apiKeyName)`, and keep the old `of` delegating with nulls;
  - `KeyInfo.caller()` passes `id` and `name` (T044 added `name`; if US5 isn't done yet, add `name` to `KeyInfo` here);
  - fix the compile errors in tests that construct `Caller` directly.
- [X] T056 [P] [US6] Create `main/core/usage/UsageRecord.java`, a Java record with every data-model §3 column except `id`/`created_at` (camelCase, `issues` as four ints). Add `JsonObject toJson()` with the exact key order of contracts/operations.md §4, and `Tuple toTuple()` in V12 column order.
- [X] T057 [P] [US6] Create `main/core/usage/ThreadMetrics.java`:
  - check once whether `ManagementFactory.getThreadMXBean()` supports and has enabled thread CPU time, and whether it is `com.sun.management.ThreadMXBean` with `isThreadAllocatedMemorySupported() && isThreadAllocatedMemoryEnabled()`;
  - `long cpuNanos()` and `long allocatedBytes()` for the current thread return -1 when unsupported;
  - log INFO once at class init when either counter is unavailable.
- [X] T058 [US6] Create `main/core/usage/UsageMeter.java` (research R10):
  - Constructed with `(LongSupplier nanoClock, Instant startedAt, String requestId, Caller caller, InetAddress clientIp, String fhirVersionParam, String contentType, int requestBytes, int profileCount, Consumer<UsageRecord> sink)`.
  - Methods:
    - `markAdmitted()`, `markContextReady()`;
    - `workerStart()` and `workerEnd(Outcome-derived issue counts)`, which capture `ThreadMetrics` deltas;
    - `ig(String label)`, `outcome(String)`, `workDone()`, `workNeverStarted()`;
    - `responseEnded(int status, boolean reset, boolean timedOut)`.
  - An `AtomicInteger` latch of 2 emits exactly once; `workNeverStarted()` counts down the work latch.
  - Outcome precedence (research R10): `timeout` when the request timeout fired, else `aborted` when the connection reset before the response ended, else the service's outcome, else `rejected` (a 4xx before the work started). A late service outcome never overwrites `timeout` or `aborted`. CPU and allocation still come from the worker.
  - Two modes. **Metrics-only** (sink `none`): marks, `ThreadMetrics` deltas and the outcome are captured, but no `UsageRecord` is built or emitted. **Full**: the same, plus the record.
  - `static final UsageMeter NONE` is a no-op, used only by callers outside HTTP (CLI, tests).
- [X] T059 [P] [US6] Create `main/core/usage/UsageWriter.java`: the interface `void write(UsageRecord r)`, `Future<Void> close()`, `default boolean enabled() { return true; }`, and `static UsageWriter create(Vertx, Pool, ApplicationConfig, ServiceMetrics)`, which builds from `usage.sink`. Also create `NoopUsageWriter.java` (`enabled() == false`) and `CompositeUsageWriter.java` (fans out; `close` = `Future.all` of the parts).
- [X] T060 [P] [US6] Create `main/core/usage/LogUsageWriter.java`: `LogManager.getLogger("nzi.fhir.validator.usage")`, with `write` = `logger.info(record.toJson().encode())`. In `src/main/resources/log4j2.xml`, add the appender `<Console name="UsageConsole" target="SYSTEM_OUT"><PatternLayout pattern="%msg%n"/></Console>` under `<Appenders>`, and the logger `<Logger name="nzi.fhir.validator.usage" level="info" additivity="false"><AppenderRef ref="UsageConsole"/></Logger>` under `<Loggers>`. The usage logger must not depend on `FV_LOG_FORMAT`. Add an XML comment showing a `RollingFile` alternative.
- [X] T061 [US6] Create `main/core/usage/DbUsageWriter.java` (research R12):
  - an `ArrayBlockingQueue<UsageRecord>(queueSize)`;
  - `write` calls `offer`; on false → `metrics.usageDropped("queue_full")`; when the size is `>= batchSize`, `vertx.runOnContext(v -> flush())`;
  - `vertx.setPeriodic(flushMs, …)` calls `flush()`;
  - `flush()` is guarded by an `AtomicBoolean inFlight`. It drains up to `batchSize`, then calls `pool.preparedQuery("INSERT INTO fhir_validator_schema.validation_usage (<V12 columns>) VALUES ($1…$25)").executeBatch(tuples)`:
    - on success: `usageWritten(n)`, and if the queue still has `>= batchSize`, flush again;
    - on failure: `usageDropped("write_failed", n)` and a WARN at most once per 60 s, with no retry;
  - `close()`: cancel the timer, then flush repeatedly until the queue is empty or 5 s pass;
  - register the gauge `fv.usage.queue.size`.
- [X] T062 [US6] In `main/core/metrics/ServiceMetrics.java`, add (research R11, contracts/operations.md §3):
  - `recordValidationResources(SupportedFhirVersion v, String outcome, long cpuNanos, long allocatedBytes)`, which feeds the cached `DistributionSummary`s `fv.validation.cpu` (base unit `seconds`, value `cpuNanos / 1e9`, skipped when < 0) and `fv.validation.allocated` (base unit `bytes`, skipped when < 0), tagged `version` and `outcome`;
  - `usageDropped(String reason, long n)` and `usageWritten(long n)`, which feed counters `fv.usage.dropped` (tag `reason`) and `fv.usage.written`;
  - `bindUsageQueue(Queue<?>)`.

  Keep the existing `fv.validation` timer as it is.
- [X] T063 [US6] In `main/core/service/FhirValidationService.java`, add the overload `validate(Caller, SupportedFhirVersion, IgReference, String, SupportedContentType, List<String>, UsageMeter meter, Function<Outcome,T> finisher)`; the current 7-argument overload delegates with `UsageMeter.NONE`. Inside it:
  - call `meter.markAdmitted()` at the top of the admission supplier;
  - call `meter.markContextReady()` and `meter.ig(key label)` after `registry.snapshot(key)` completes;
  - in the blocking task, call `meter.workerStart()` first and `meter.workerEnd(issueCounts(outcome))` last (count by severity from `outcome.result()`, on the worker);
  - in `onComplete`, call `meter.outcome(<same outcome string as recordValidation>)`, `metrics.recordValidationResources(...)` and `meter.workDone()`. `recordValidationResources` runs for every request regardless of sink, using the meter's CPU and allocation deltas (FR-020);
  - when admission refuses (503 overloaded), the outcome is whatever `failureOutcome` returns today (`error`). Don't add a new outcome constant: that would change the existing `fv.validations` labels.
- [X] T064 [US6] In `main/web/endpoint/ValidationApi.java` `handleDoValidation`:
  - at entry, always create the meter: full mode when `usageWriter.enabled()`, metrics-only otherwise (caller from `ApiKeyAuthHandler.caller(ctx)`, `RequestIdHandler.requestId(ctx)`, `ClientAddress.of(ctx)`, `ctx.pathParam("version")`, the `Content-Type` value, the body length or 0, and the profile count once parsed);
  - register `ctx.addEndHandler(ar -> meter.responseEnded(ctx.response().getStatusCode(), ar.failed(), RequestTimeoutHandler.timedOut(ctx)))`;
  - on every early return (415, 406, bad profiles, bad `ig`, empty body, unsupported version), call `meter.workNeverStarted()`;
  - pass the meter to the new `validate` overload.

  `RequestTimeoutHandler` has no such flag today: in `main/web/RequestTimeoutHandler.java`, add `public static final String TIMED_OUT = "requestTimedOut"`, set `ctx.put(TIMED_OUT, true)` in the timer callback before `ErrorResponder.fail`, and add `static boolean timedOut(RoutingContext ctx)`.
- [X] T065 [US6] Wiring:
  - In `main/core/config/ConfigLoader.java`, add `FV_USAGE_SINK` (`usage.sink`, STRING, default `none`, validated as in T053), `FV_USAGE_QUEUE_SIZE` (`usage.queueSize`, INT 100–1 000 000, default 10 000), `FV_USAGE_BATCH_SIZE` (`usage.batchSize`, INT 10–5 000, default 500) and `FV_USAGE_FLUSH_MS` (`usage.flushMs`, LONG 100–60 000, default 1 000). After the bounds checks, reject `batchSize > queueSize` with the startup error `FV_USAGE_BATCH_SIZE (<b>) must not exceed FV_USAGE_QUEUE_SIZE (<q>)`, because a batch larger than the queue could never trigger a size flush. Add the getters to `ApplicationConfig`.
  - In `main/ServiceRegistry.java`, build `UsageWriter.create(...)`, expose `usage()`, and pass it to `ValidationApi`.
  - In `main/Bootstrap.java` `stop()`, insert `.eventually(() -> services.usage().close())` after the drain log and before `services.stop()` / the pool close.

**Checkpoint**: `UsageMeterTest`, `LogUsageWriterTest`, `DbUsageWriterTest`, `UsageIT`, `ObservabilityIT`,
`ConfigLoaderTest`, `MigrationUpgradeIT` and `GracefulShutdownIT` are green.

---

## Phase 10: User Story 8 - Usage summary command (Priority: P3, optional)

**Goal**: `usage summary (--org | --key) [--since]` prints aggregate usage as JSON.

**Independent Test**: seeded usage rows → the totals match.

- [X] T066 [US8] In `test/cli/AdminCliIT.java`, add `usageSummaryByOrgAndKey`:
  - insert 3 `validation_usage` rows for the trial key (outcomes `valid`, `valid`, `invalid`) and 1 for another org;
  - `usage summary --org trial --since 2000-01-01T00:00:00Z` gives `validations = 3`, `byOutcome = {valid:2, invalid:1}` and the summed ms, CPU and bytes;
  - `--key "trial validator"` gives the same;
  - passing both `--org` and `--key`, or neither, is a usage error.
- [X] T067 [US8] Create `main/cli/UsageCommands.java` (pattern: `KeyCommands`):
  - one aggregate `SELECT count(*), sum(total_ms), avg(total_ms), sum(validate_ms), sum(greatest(cpu_time_ns,0)), sum(greatest(allocated_bytes,0))` plus a `GROUP BY outcome` query, filtered by `org_id` (resolved from the short name) or `api_key_id` (resolved from the name) and `created_at >= since`;
  - `--since` defaults to 24 h ago;
  - the JSON shape follows contracts/operations.md §2.

  Register the `usage` group in `main/cli/AdminCli.java` and its help text.

---

## Phase 11: Polish & Cross-Cutting Concerns

- [X] T068 [P] Create `docs/upgrade-4.1.md` from contracts/operations.md §5. Cover:
  - stop all instances, and why;
  - create VALIDATE keys or set `FV_VALIDATOR_API_KEY`;
  - remove `FV_AUTH_ANONYMOUS_VALIDATE`;
  - the new optional settings;
  - usage retention, with a sample `DELETE`;
  - rollback by restoring the backup;
  - the bootstrap key is renamed to `system administrator`, and the names `system administrator` and `trial validator` are now reserved; seeded keys are revoked by rotating or unsetting `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY`, not with `keys revoke`;
  - "If startup fails with a conflict on the system administrator key": rename or revoke the conflicting key with `keys revoke <name>`, then restart.

  Add `docs/upgrade-4.1.md` to the release assets list in `.github/workflows/maven-publish.yml` (next to `docs/upgrade-4.0.md`).
- [X] T069 [P] Add a 4.1.0 entry to `CHANGELOG.md`, with a **Breaking** section (key-only validation, removed setting, stop-the-world upgrade, renamed bootstrap key) that links to `docs/upgrade-4.1.md`, and an **Added** section (admin CIDRs, trusted proxies, key expiry, last-used IP, usage accounting, trial org and key, metrics).
- [X] T070 [P] Update `README.md`:
  - the auth section (around line 448): key-only validation, the trial key, the admin allowlist and trusted proxies, expiry, and `keys list` fields;
  - the settings table (around line 513): remove `FV_AUTH_ANONYMOUS_VALIDATE` and add the 7 new variables with their defaults and bounds from data-model §6;
  - a new "Usage accounting" section: sinks, the log line, the table, retention, metrics, and that "memory" means allocated bytes;
  - every `curl` example that validates without a key now sends one.
- [X] T071 Performance (Principle VI, SC-007): run `perf/validate.js` three times against a warm R4 validator: (a) 4.0.0 without a key, (b) 4.1.0 with `FV_USAGE_SINK=none`, (c) 4.1.0 with `FV_USAGE_SINK=db`. Record p50/p95/p99 and throughput in `perf/results-4.1.0.md`. **SC-007 passes when** p95(c) − p95(b) < 1 ms and p95(c) < 200 ms. Report (b) − (a), the cost of auth and the client-address handler, separately for information. If it fails, profile `UsageMeter`/`DbUsageWriter` before merging.
- [X] T072 Run the quickstart.md scenarios §1–§6 manually against a local build, then run `./mvnw verify`, which must be green with no new `BlockedThreadChecker` warnings. Check that `git diff --stat src/main/resources/db/migration` shows only new files (V10–V12), with V1–V9 untouched.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none. T002 → T003. T004 and T005 are independent.
- **Foundational (Phase 2)**: after Setup. V10 must exist before V11 (T012) and V12 (T054).
- **US3 (Phase 3)**: after Phase 2.
- **US2 (Phase 4)**: after US3, because the seeding relies on the V11 ids and constraints.
- **US1 (Phase 5)**: after US2, because the ITs need `AppHarness.VALIDATOR_KEY` (T021).
- **US7 (Phase 6)**: after US1 (both edit `authorise`).
- **US4 (Phase 7)**: after US1. It is independent of US5 and US6, apart from `ClientAddress` being used by them.
- **US5 (Phase 8)**: after US4 (`touch(id, ip)` uses `ClientAddress.of`).
- **US6 (Phase 9)**: after US4 (client address). T055 needs `KeyInfo.name` (T044); if US5 isn't done, add `name` in T055.
- **US8 (Phase 10)**: after US6 (the table and the rows).
- **Polish (Phase 11)**: after the stories you intend to ship.

### Within Each User Story

- Tests are written first and must fail.
- Within a phase: schema → repository → handler/service → wiring → docs.
- Edits to the same file are sequential: `ApiKeyAuthHandler` (T025 → T031 → T040 → T046), `ConfigLoader` (T005 → T016 → T026 → T038 → T065), `ApiKeyRepository` (T017 → T044 → T045) and `ApiKeyAuthIT` (T022 → T024 → T034 → T041).

### Parallel Opportunities

- Phase 1: T004 ∥ T005, after T002/T003.
- US3: T009 ∥ T010 (different files); T008 and T011 share `MigrationUpgradeIT`.
- US2: T014 ∥ T015 ∥ T013.
- US1: T023 ∥ T024 ∥ T029.
- US4: T032 ∥ T033 ∥ T035 ∥ T036.
- US6: T048 ∥ T049 ∥ T050 ∥ T052 ∥ T053, then T056 ∥ T057 ∥ T059 ∥ T060.
- Polish: T068 ∥ T069 ∥ T070.

## Parallel Example: User Story 6

```text
# Tests first (different files):
Task: "T048 UsageMeterTest in test/core/usage/UsageMeterTest.java"
Task: "T049 LogUsageWriterTest in test/core/usage/LogUsageWriterTest.java"
Task: "T050 DbUsageWriterTest in test/core/usage/DbUsageWriterTest.java"
Task: "T053 usage config bounds in test/core/config/ConfigLoaderTest.java"

# Then independent building blocks:
Task: "T056 UsageRecord in main/core/usage/UsageRecord.java"
Task: "T057 ThreadMetrics in main/core/usage/ThreadMetrics.java"
Task: "T059 UsageWriter + Noop + Composite in main/core/usage/"
Task: "T060 LogUsageWriter + log4j2.xml"
```

## Parallel Example: User Story 4

```text
Task: "T032 CidrTest in test/core/security/CidrTest.java"
Task: "T033 ClientAddressTest in test/core/security/ClientAddressTest.java"
Task: "T035 CIDR config validation in test/core/config/ConfigLoaderTest.java"
Task: "T036 Cidr + CidrList in main/core/security/"
```

## Implementation Strategy

### MVP First (US3 + US2 + US1)

1. Phases 1 and 2 (setup, V10).
2. Phase 3, US3: the re-key migration. Verify it with `MigrationUpgradeIT`.
3. Phase 4, US2: the well-known orgs and keys. Verify them with `BootstrapOrganisationsIT`.
4. Phase 5, US1: key-only validation. `mvn verify` must be green.
5. **Stop and validate**: this is a shippable 4.1.0 core (validation is attributed to keys, and the trial offering
   works).

### Incremental Delivery

1. MVP → US7 (one small change).
2. US4 (network controls) → US5 (expiry and audit). Both harden keys further.
3. US6 (usage accounting) with its performance run (T071).
4. US8 is optional and can slip to a later minor release.
5. Polish (docs, changelog, upgrade notes) before tagging 4.1.0.

## Notes

- [P] tasks touch different files and have no dependency on unfinished tasks.
- Never edit applied migrations V1–V9. V11 replaces constraints that V8 created.
- `Constant.java` already has the user's staged changes; T002 builds on them.
- Never log a key, its hash or `FV_VALIDATOR_API_KEY`. Usage records never contain resource content.
- Releasing breaking changes as 4.1.0 uses the Principle IV exception in constitution 1.3.0. It is recorded in
  plan.md's Complexity Tracking; T069 (Breaking section) and T068 (`docs/upgrade-4.1.md`) are required by that
  exception.

## Phase 12: Convergence

- [X] T073 CRITICAL: Declare `'503': $ref: '#/components/responses/Problem'` on `igPackageApiGetDependenciesGraph` and `igPackageApiGenerateConformanceReport` in `src/main/resources/openapi.yaml`. Both can answer 503 (the key lookup answers `not-ready` while migrations run, and the request timeout fires), and the other 7 keyed operations already list it. Extend `ApiKeyAuthIT.openApiDeclaresKeyedOperations` to assert that every keyed operation lists `503`. Per Constitution IV (contradicts).
- [X] T074 Amend `specs/008-api-key-hardening/spec.md` to match the code. FR-003: drop "or the left-most when every hop is trusted"; the client is unknown when no untrusted hop is found (no header, every hop trusted, an unparseable entry), and administrative calls from an unknown client are refused. Only the header named by `FV_AUTH_FORWARDED_HEADER` (`x-forwarded-for` by default, or `forwarded`) is read. Edge Cases: rewrite "Both standard forwarded headers are present…" and "Every hop in the forwarded chain is a trusted proxy: the left-most address is used" the same way. Per FR-003 (contradicts).
- [X] T075 Count usage log lines dropped by the non-blocking `UsageAsync` appender in `fv.usage.dropped{reason="log_full"}`. For example, give the appender an `errorRef` to a small custom appender, or have `LogUsageWriter` own a bounded queue drained by a single logging thread, and count when it rejects. Add a `LogUsageWriterTest` case that fills the buffer and checks the counter. Update `contracts/operations.md` §3. Per FR-017 (partial).
- [X] T076 Record a usage row for an authorised `POST /{version}/validate` that the router refuses before any operation handler runs (406 on `Accept`, 415 on `Content-Type`). Either add a router-level failure or end handler for the validate path that starts the meter when no handler did (outcome `rejected`), or narrow FR-011 in `spec.md` to requests that reach the endpoint and say so in `contracts/operations.md`. Add a `UsageIT` case with `Accept: text/plain`. Per FR-011 (partial).
- [X] T077 Align `specs/008-api-key-hardening/plan.md` (Constraints: "touch map ≤ 10 000 (LRU)"; source tree: "bounded LRU") and `research.md` R7 ("shows up at once", "LRU, 10 000 entries") with the code. The throttle is a bounded `ConcurrentHashMap` with arbitrary eviction past 10 000 keys, and an address change is written at most once per second per key (`ADDRESS_CHANGE_INTERVAL_MS`). An unknown address never replaces the stored one. Per plan: constraints / research R7 (partial).
- [X] T078 Update `specs/008-api-key-hardening/contracts/http-api.md`. §3 Responses: add **503 `not-ready`** (`Retry-After`) for key lookups before the migrations have run. §4 Order of checks: the client address may be unknown (fail-closed), and an unknown client is refused on administrative paths. Per contracts/http-api.md (partial).

## Phase 13: Convergence

- [X] T079 CRITICAL: In `main/core/usage/LogUsageWriter.java`, replace the raw `new Thread(this::drain, "fv-usage-log")` writer and the raw `fv-usage-log-close` waiter. Run the drain loop on a named `WorkerExecutor` (`vertx.createSharedWorkerExecutor("fv-usage-log", 1, …)`, size 1, with a `maxExecuteTime` long enough for a loop that runs as long as the service), started by `executeBlocking(…, false)`. `close()` sets the flag and waits on the drain's `Future` with a 5 s timer instead of joining a thread. Pass `Vertx` in from `UsageWriter.create`. Keep the bounded queue, the `log_full` count and the non-blocking `write`. `LogUsageWriterTest` keeps passing: no blocking, drops counted, and `close()` writes what remains. Per Constitution I (contradicts).
- [X] T080 Update `specs/008-api-key-hardening/research.md`. R11: the `fv.usage.dropped` reasons are `queue_full`, `write_failed`, `closed`, `unattributed` and `log_full`. R12: `LogUsageWriter` offers each line to a bounded queue (8 192), and a single worker writes the queue to the `nzi.fhir.validator.usage` logger (`UsageConsole`, `%msg%n`); full → dropped and counted as `log_full`; `close()` writes what remains (up to 5 s). Per research R11/R12 (partial).
- [X] T081 Update `specs/008-api-key-hardening/data-model.md` §5: `ClientAddress.resolve(peer, values, header, trusted)` returns the client `InetAddress`, or `null` when it is unknown (no untrusted hop, an unparseable entry or peer). Only the header named by `FV_AUTH_FORWARDED_HEADER` is read. Per data-model §5 (partial).

## Phase 14: Convergence

- [X] T082 CRITICAL: Bring the two fixed sizes in line with Constitution I ("a named `WorkerExecutor` with an explicit, configurable pool size") and VI ("cache sizes and worker-pool sizes MUST be configurable"): the `fv-usage-log` pool in `main/core/usage/LogUsageWriter.java` (size 1) and the `touch()` throttle bound in `main/core/security/ApiKeyRepository.java` (`MAX_TOUCHED_KEYS` = 10 000). Preferred: record both as justified exceptions in `specs/008-api-key-hardening/plan.md`. In the Constitution Check, change rows I and VI to "PASS with justified exceptions". In Complexity Tracking, add one row each: the log pool is a single writer by design (a larger pool would interleave and reorder usage lines, and its throughput is bounded by stdout, not threads), and the touch map is a write throttle, not a data cache (eviction only costs one extra `UPDATE`, so a setting adds operator surface for no capacity gain). Alternative: add bounded `ConfigLoader` entries (for example `FV_AUTH_TOUCH_MAX_KEYS`) with `ConfigLoaderTest` cases, and keep the pool at 1 with the same justification. Per Constitution I, VI (contradicts).

## Phase 15: Convergence

- [X] T083 Amend FR-026 in `specs/008-api-key-hardening/spec.md` to match V13. A configured value whose hash was ever revoked (recorded in `revoked_key_hashes` by the trigger on any revocation of a seeded key) is never installed again: startup logs an ERROR naming the variable (never the value) and leaves the stored key unchanged. Every other well-formed value is installed or updated as FR-026 already says. Mention that FR-027 (the system administrator key) follows the same rule. Per FR-026 (contradicts).
- [X] T084 Update research R10 in `specs/008-api-key-hardening/research.md`. The usage meter is created once per request, at entry, by the root handler `ValidationApi.usageFallbackHandler()` → `meterFor(ctx)` (mounted right after authorisation), and stored in the routing context. When the response ends, the meter records it and also closes the work side unless the validation service was started (`WORK_STARTED_KEY`). `handleDoValidation` and the failure handler reuse that meter. Per research R10 (partial).
- [X] T085 Update research R12 in `specs/008-api-key-hardening/research.md`. Each `LogUsageWriter` has its own worker pool `fv-usage-log-N` (size 1) so two writers in one Vert.x both run. The queue holds `UsageRecord`s and the JSON is encoded on that worker, not the event loop. A record offered while `close()` finishes is taken back and counted as `closed`. Per research R12 (partial).
- [X] T086 In the plan's source tree (`specs/008-api-key-hardening/plan.md`), add `V13__revoked_well_known_key_hashes.sql` to the migrations line and `core/PgErrors.java` (shared PostgreSQL error checks) to the `core/` entries. Per plan: source tree (partial).

## Phase 16: Convergence

- [X] T087 Align `specs/008-api-key-hardening/plan.md` and `contracts/operations.md` with the forwarded-header setting. Plan Summary §1: the resolver reads only the header named by `FV_AUTH_FORWARDED_HEADER` (`x-forwarded-for` by default, or `forwarded`), never both, and the client is unknown when no untrusted hop is found. Add `FV_AUTH_FORWARDED_HEADER` to the plan source tree's `ConfigLoader` line. In `contracts/operations.md` §1, add the variable with its default, allowed values and the startup error `Invalid FV_AUTH_FORWARDED_HEADER: …`, and include it in the optional settings of the upgrade steps (§5). Per plan: Summary §1, contracts/operations.md §1 (partial).
- [X] T088 In `main/Bootstrap.java` `installWellKnownKey`, when `FV_VALIDATOR_API_KEY` is unset, make the WARN state that validation is key-only and that no trial validator key is installed. Keep the prefix `FV_VALIDATOR_API_KEY is not set` (asserted by `BootstrapOrganisationsIT.missingTrialKeyLogsAWarning`), and keep the note that a key stored earlier stays valid. The `FV_ADMIN_API_KEY` warning keeps its current text. Update the WARN text in `contracts/operations.md` §1 to match, and extend the test to assert the key-only wording. Per US2/AC4 (partial).
- [X] T089 Amend FR-003 and the Edge Case "Every hop in the forwarded chain is a trusted proxy…" in `specs/008-api-key-hardening/spec.md`: an unknown client is refused on administrative calls when an admin allowlist (`FV_AUTH_ADMIN_CIDRS`) is set (fail-closed). Without an allowlist the network is not restricted (FR-002), and an unknown address still never replaces a recorded one. This matches contracts/http-api.md §4 and `ApiKeyAuthHandler.rootHandler`. Per FR-003 (partial).
