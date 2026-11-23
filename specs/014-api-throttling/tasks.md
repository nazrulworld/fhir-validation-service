---

description: "Task list for feature 014-api-throttling"
---

# Tasks: Validation Rate Limits per API Key and per Organisation

**Input**: Design documents from `specs/014-api-throttling/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/http-api.md](./contracts/http-api.md),
[contracts/operations.md](./contracts/operations.md), [quickstart.md](./quickstart.md)

**Tests**: REQUIRED. Constitution Principle V says new behaviour starts with a failing test, persistence is tested on
Testcontainers PostgreSQL, and every endpoint has a contract test. In each phase, write the test tasks first and see
them fail before implementing.

**Organization**: tasks are grouped by user story. Fleet-wide sharing (FR-005, FR-019, SC-001) and observability
(FR-017, FR-018) cut across stories and get their own phases after the stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: US1–US5 from spec.md

## Path Conventions

- Main: `src/main/java/nzi/fhir/validator/…`
- Resources: `src/main/resources/…`
- Tests: `src/test/java/nzi/fhir/validator/…`
- Clock: every time-dependent class takes a `java.util.function.LongSupplier` of epoch milliseconds (default
  `System::currentTimeMillis`), so unit tests are deterministic.

---

## Phase 1: Setup (contract, constants, configuration)

**Purpose**: the contract first (Principle IV), plus the constants and settings every later phase reads.

- [X] T001 [P] Update `src/main/resources/openapi.yaml` as in contracts/http-api.md §3–§4:
  - **components/headers**: add `RetryAfter` (integer, minimum 1), `RateLimitLimit` (integer, minimum 1),
    `RateLimitRemaining` (integer, minimum 0) and `RateLimitReset` (integer, minimum 0), with the descriptions given
    there.
  - **components/responses**: add `TooManyRequests` (429 `application/problem+json`, headers `Retry-After` and
    `RateLimit-*`, with the example).
  - **components/schemas/Problem**: add the optional properties `scope` (string, enum `key`, `organisation`),
    `limit` (integer) and `retryAfterSeconds` (integer), each described as "Present only for type rate-limited".
  - **`/{version}/validate` post**: add `'429': $ref TooManyRequests`; add the three `RateLimit-*` headers to
    `'200'`; append the description paragraph from §4 verbatim.
- [X] T002 [P] Add the constants to `src/main/java/nzi/fhir/validator/core/Constant.java`:
  - `THROTTLE_DEFAULT_KEY_PER_MINUTE = 600`;
  - `THROTTLE_DEFAULT_ORG_PER_MINUTE = 1200`;
  - `TRIAL_ORG_RATE_LIMIT_PER_MINUTE = 60`;
  - `TRIAL_ORG_VALIDATOR_API_KEY_RATE_LIMIT_PER_MINUTE = 30`;
  - `RATE_LIMIT_MAX_PER_MINUTE = 1_000_000`.

  Add the settings-name constants `THROTTLE_DEFAULT_KEY = "throttle_default_key_per_minute"` and
  `THROTTLE_DEFAULT_ORG = "throttle_default_org_per_minute"` to
  `src/main/java/nzi/fhir/validator/core/settings/ServiceSettingsRepository.java`.
- [X] T003 [P] Add the three configuration entries (data-model.md §4):
  - in `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java`:
    - `FV_THROTTLE_SYNC_INTERVAL_MS` → `throttle.syncIntervalMs`, LONG, 200–1000;
    - `FV_THROTTLE_CAPS_REFRESH_MS` → `throttle.capsRefreshMs`, LONG, 1000–30000;
    - `FV_THROTTLE_MAX_TRACKED` → `throttle.maxTracked`, INT, 1000–10000000;
  - defaults 1000, 10000 and 100000 in a `"throttle"` section of `src/main/resources/config.json`;
  - getters `throttleSyncIntervalMs()`, `throttleCapsRefreshMs()` and `throttleMaxTracked()` in
    `src/main/java/nzi/fhir/validator/core/config/ApplicationConfig.java`;
  - bound and default cases in `src/test/java/nzi/fhir/validator/core/config/ConfigLoaderTest.java`.

---

## Phase 2: Foundational (schema, caps snapshot, window arithmetic)

**Purpose**: the stored caps, the in-memory cap snapshot and the rolling window that every story uses.

**⚠️ CRITICAL**: no user story can start until this phase is complete.

- [X] T004 Write the failing `src/test/java/nzi/fhir/validator/core/throttle/ThrottleMigrationIT.java` (Testcontainers,
  `BaseTestContainer`). It covers:
  - (a) on an empty database after all migrations:
    - `api_keys.rate_limit_per_minute` and `organisations.rate_limit_per_minute` exist and are nullable INTEGER;
    - `service_settings` has `throttle_default_key_per_minute = '600'` and `throttle_default_org_per_minute = '1200'`;
    - the trial organisation `00000000-0000-0000-0000-100000000002` has 60;
    - `throttle_counts` and `throttle_instances` are UNLOGGED (`pg_class.relpersistence = 'u'`);
  - (b) migrating a V17 schema with data (orgs, keys including the trial validator key
    `00000000-0000-0000-0000-200000000002`): the trial key gets 30, and other keys stay NULL;
  - (c) the constraints reject:
    - 0 and 1000001 on both columns;
    - any non-NULL cap on the system organisation `00000000-0000-0000-0000-100000000001` and on a key it owns;
    - the setting value `'0'`, `'abc'` or `'1000001'`;
  - (d) the existing settings still work after V18 (the D1 regression guard): `ServiceSettingsRepository.upsert` of
    `acl_security = 'enabled'`, then `'disabled'`, and of `fhir_versions = 'R4,R5'` succeeds.
- [X] T005 Create `src/main/resources/db/migration/V18__validation_rate_limits.sql` (data-model.md §1–§2). Do not edit
  V15–V17.
  - `ALTER TABLE fhir_validator_schema.api_keys ADD COLUMN rate_limit_per_minute INTEGER NULL`, with:
    - `api_keys_rate_limit_chk`: `rate_limit_per_minute IS NULL OR rate_limit_per_minute BETWEEN 1 AND 1000000`;
    - `api_keys_rate_limit_system_chk`: `owner <> '00000000-0000-0000-0000-100000000001' OR rate_limit_per_minute
      IS NULL`.
  - The same column on `organisations`, with:
    - `organisations_rate_limit_chk`: `IS NULL OR BETWEEN 1 AND 1000000`;
    - `organisations_rate_limit_system_chk`: `id <> '00000000-0000-0000-0000-100000000001' OR rate_limit_per_minute
      IS NULL`.
  - `service_settings_throttle_chk`, written exactly as in data-model.md §1.3 with nested `CASE` (PostgreSQL does not
    guarantee `AND`/`OR` evaluation order, so a plain form could cast `acl_security`'s value to int):
    `CHECK (CASE WHEN name IN ('throttle_default_key_per_minute', 'throttle_default_org_per_minute') THEN CASE WHEN
    value ~ '^[1-9][0-9]{0,6}$' THEN value::int <= 1000000 ELSE false END ELSE true END)`.
  - `CREATE UNLOGGED TABLE fhir_validator_schema.throttle_counts`:
    - columns `scope CHAR(1) NOT NULL CHECK (scope IN ('K','O'))`, `subject_id UUID NOT NULL`, `epoch_second BIGINT
      NOT NULL`, `instance_id UUID NOT NULL`, `count INTEGER NOT NULL CHECK (count >= 1)`;
    - PK `(scope, subject_id, epoch_second, instance_id)`;
    - index `throttle_counts_second_idx (epoch_second)`;
    - no foreign keys.
  - `CREATE UNLOGGED TABLE fhir_validator_schema.throttle_instances`: `instance_id UUID PRIMARY KEY`,
    `instance_name VARCHAR(128) NULL`, `seen_at TIMESTAMPTZ NOT NULL`.
  - Seeds:
    - `INSERT INTO service_settings (name, value, updated_by) VALUES ('throttle_default_key_per_minute','600','V18'),
      ('throttle_default_org_per_minute','1200','V18') ON CONFLICT (name) DO NOTHING`;
    - `UPDATE organisations SET rate_limit_per_minute = 60 WHERE id = '00000000-0000-0000-0000-100000000002' AND
      rate_limit_per_minute IS NULL`;
    - `UPDATE api_keys SET rate_limit_per_minute = 30 WHERE id = '00000000-0000-0000-0000-200000000002' AND
      rate_limit_per_minute IS NULL`.
  - Comment each block with the spec FR it serves. T004 must pass.
- [X] T006 [P] Extend `src/test/java/nzi/fhir/validator/core/ConstantMigrationConsistencyTest.java`: the ids, setting
  names and values in V18 equal the `Constant` and `ServiceSettingsRepository` constants from T002.
- [X] T007 [P] Write the failing `src/test/java/nzi/fhir/validator/core/throttle/WindowTest.java` (pure unit test,
  injected clock). It covers:
  - the count over the 60 buckets before the current second plus the current bucket, including a bucket exactly 60 s
    old (counted) and one 61 s old (not counted);
  - `local` and `remote` summed separately, and `setRemote(second, n)` replacing rather than adding;
  - `secondsUntilBelow(cap)`, the exact wait until the windowed count drops to `cap − 1`;
  - `resetSeconds()`, the seconds until the oldest counted request leaves (0 when empty);
  - a 5-request burst at second 59 and again at second 61 of a minute with cap 5, where the second burst is refused
    (spec Edge Cases: no doubled bursts).
- [X] T008 Implement `src/main/java/nzi/fhir/validator/core/throttle/Window.java`:
  - a ring of 61 slots, each with `long second`, `int local` and `int remote`;
  - `synchronized` methods `count(nowSec)`, `addLocal(nowSec)`, `setRemote(sec, n)`, `localCountsSince(sec)` (for the
    flush), `secondsUntilBelow(cap, nowSec)` and `resetSeconds(nowSec)`;
  - fields reserved for the later phases: `unconfirmed` admissions with their seconds, and refusal-log state
    (`refusedSinceLog`, `lastLogAtMs`, `firstRefusedRequestId`);
  - no I/O, and no allocation per call beyond primitives.

  T007 must pass.
- [X] T009 [P] Write the failing `src/test/java/nzi/fhir/validator/core/throttle/ThrottleCapsTest.java`. It covers:
  - effective key cap = own cap, else `defaultKey`, else none;
  - effective organisation cap = own cap, else `defaultOrg`, else none;
  - an own cap that is lower or higher than the default always wins (FR-006);
  - an empty snapshot gives none at both levels.
- [X] T010 Implement `src/main/java/nzi/fhir/validator/core/throttle/ThrottleCaps.java`, an immutable record:
  `Map<UUID,Integer> keyCaps`, `Map<UUID,Integer> orgCaps`, `OptionalInt defaultKey`, `OptionalInt defaultOrg`,
  `Instant loadedAt`, `keyCap(UUID)`, `orgCap(UUID)` and `EMPTY`. T009 must pass.
- [X] T011 Implement `src/main/java/nzi/fhir/validator/core/throttle/ThrottleCapsRepository.java`. `load(SqlClient)`
  returns `Future<ThrottleCaps>` from one query: `SELECT 'K', id, rate_limit_per_minute FROM api_keys WHERE
  rate_limit_per_minute IS NOT NULL UNION ALL SELECT 'O', id, … FROM organisations WHERE … IS NOT NULL UNION ALL
  SELECT name, NULL, value::int FROM service_settings WHERE name IN (<the two names>)`. Add a case to
  `ThrottleMigrationIT` that the fresh-database snapshot has 60 for the trial organisation and defaults 600 and 1200.
- [X] T012 [P] Create `src/main/java/nzi/fhir/validator/core/throttle/ThrottleDecision.java`, a record:
  - `boolean admitted`;
  - `Scope scope` (enum `KEY`, `ORGANISATION`; for a refusal, the level reported);
  - `Reason reason` (enum `CAP`, `UNCONFIRMED`);
  - `int limit`, `int remaining`, `int resetSeconds`, `int retryAfterSeconds`;
  - `boolean limited` (false means no level has a cap, so no headers);
  - static `unlimited()`.

**Checkpoint**: schema, caps snapshot and window arithmetic are done and tested.

---

## Phase 3: User Story 1 - A key cannot exceed its per-minute validation cap (Priority: P1) 🎯 MVP

**Goal**: one instance enforces a key's cap on `POST /{version}/validate`: 429 `rate-limited` with `Retry-After`,
`RateLimit-*` headers on every response, and refused requests not counted.

**Independent Test**: quickstart.md §2. Cap 5 on a key; 6 requests give 5 × 200 and 1 × 429. Wait `Retry-After`,
and the next request gets 200.

### Tests for User Story 1 ⚠️ (write first, see them fail)

- [X] T013 [P] [US1] Write the key-level cases of `src/test/java/nzi/fhir/validator/core/throttle/ThrottleDecisionTest.java`
  (unit, injected clock, peers = 0). They cover:
  - cap 5: 5 admits with remaining 4, 3, 2, 1, 0, then a refusal with `scope KEY`, `reason CAP`, `limit 5` and
    `retryAfterSeconds` equal to `Window.secondsUntilBelow`;
  - a refusal leaves the counts unchanged (AC5);
  - two keys of different organisations are independent (AC4);
  - no key cap and no default gives `limited = false`.
- [X] T014 [P] [US1] Write the key-level cases of the new `src/test/java/nzi/fhir/validator/web/ValidationThrottleIT.java`
  (`AppHarness`, one instance, `FV_THROTTLE_CAPS_REFRESH_MS=1000`; caps set by SQL in the test because the CLI comes in
  US3). It covers:
  - key cap 5: requests 1–5 → 200 with `RateLimit-Limit: 5` and `RateLimit-Remaining` 4..0; request 6 → 429 with
    `application/problem+json`, `type` `rate-limited`, `scope` `key`, `limit` 5, `retryAfterSeconds` equal to the
    `Retry-After` header, the `detail` text from contracts/http-api.md §3, and a `correlationId`;
  - after advancing past `Retry-After`, the next request → 200 (AC3). Use a controllable clock, not a real wait:
    `ThrottleService` takes a `LongSupplier` clock (epoch ms) through `ServiceRegistry`. Add
    `AppHarness.withThrottleClock(java.util.concurrent.atomic.AtomicLong)` so the test can set it. The test advances
    the clock by the `Retry-After` seconds, and the test needs no sleep;
  - the 429 is answered without contract validation: an invalid body still gets 429, not 400;
  - the 429 writes no `validation_usage` row (FR-018a, research R9), while the admitted requests still do;
  - the `RateLimit-*` headers are present on a 400 response (malformed resource) for a limited key;
  - every response passes `ContractAssertions` against `openapi.yaml`.

### Implementation for User Story 1

- [X] T015 [US1] Implement `src/main/java/nzi/fhir/validator/core/throttle/Throttle.java`:
  - windows held in a Caffeine `Cache<SubjectKey, Window>` (`SubjectKey` = record `(char scope, UUID id)`), with
    `maximumSize(maxTracked)` and `expireAfterAccess(2 min)`;
  - `ThrottleDecision check(UUID keyId, UUID orgId, ThrottleCaps caps, long nowMs)`. For now it handles the key level
    only, with the single-instance rule: admit iff `count + 1 <= cap`. Admitting calls `addLocal`;
  - `remaining = max(0, cap − count after admit)`; `reset` from `Window.resetSeconds`.

  T013 must pass.
- [X] T016 [P] [US1] Add `ProblemException.rateLimited(ThrottleDecision d)` in
  `src/main/java/nzi/fhir/validator/web/ProblemException.java`:
  - 429, type `rate-limited`;
  - the `detail` texts from contracts/http-api.md §3 for key and organisation;
  - extensions `scope` (`key` | `organisation`), `limit` and `retryAfterSeconds`;
  - `.withHeader("Retry-After", …)`.
- [X] T017 [US1] Implement `src/main/java/nzi/fhir/validator/core/throttle/ThrottleService.java`:
  - holds a `volatile ThrottleCaps`, the `Throttle` and a `LongSupplier` clock (default `System::currentTimeMillis`;
    tests pass an `AtomicLong`, see T014). `check` reads the time only from this clock;
  - `start()` loads caps through `ThrottleCapsRepository`, then sets `ready`, and schedules a Vert.x periodic reload
    every `throttleCapsRefreshMs()`. A failed reload keeps the old snapshot (WARN at most once a minute);
  - `stop()` cancels the timers;
  - `check(Caller, nowMs)` delegates to `Throttle`.
- [X] T018 [US1] Implement `src/main/java/nzi/fhir/validator/web/ThrottleHandler.java`, a root handler:
  - it acts only on `POST` paths matching `ValidationApi`'s validate pattern;
  - when `!service.ready()`, it fails with `ProblemException.notReady(...)`;
  - otherwise it calls `check`. When `limited`, it puts `RateLimit-Limit`, `RateLimit-Remaining` and
    `RateLimit-Reset` on the response;
  - when refused, it calls `ErrorResponder.fail(ctx, ProblemException.rateLimited(d))`; otherwise `ctx.next()`.
- [X] T019 [US1] Wire it in, **only when access control is enabled** (`config.aclMode() == ENABLED`). In disabled mode
  nothing is built, mounted or started, so the fixed `trial validator` caller is never throttled even before US4's
  tests exist:
  - `src/main/java/nzi/fhir/validator/ServiceRegistry.java`: build `ThrottleService` (with its clock, see T014) and
    `ThrottleHandler`. `throttle()` returns `Optional<ThrottleService>`;
  - `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`: mount `ThrottleHandler` as a root handler right after
    `services.auth().rootHandler()` and before `services.validationApi().usageFallbackHandler()`;
  - `src/main/java/nzi/fhir/validator/Bootstrap.java`: warm-up calls `start()` after `AclModeStartup.run(...)`, and
    `stop()` stops it before the pool closes;
  - readiness (constitution VII): add the reason `rate limits not loaded` to the not-ready reasons passed to
    `HealthService.setValidatorReadiness` while `!throttle.ready()`, so readiness reports not-ready until the first
    cap snapshot is loaded. Add a `ValidationThrottleIT` case that readiness is not-ready (with that reason) before
    `start()` completes and ready afterwards.

  T014 must pass.
- [X] T020 [P] [US1] Add `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset` to `exposedHeaders` in
  `src/main/java/nzi/fhir/validator/web/CorsConfig.java`. Add a CORS assertion to `ValidationThrottleIT`.

**Checkpoint**: US1 works on one instance. This is the MVP.

---

## Phase 4: User Story 2 - An organisation's keys together cannot exceed the organisation's cap (Priority: P1)

**Goal**: the organisation cap counts every key of the organisation, is checked together with the key cap, and a key
refusal consumes no organisation allowance.

**Independent Test**: quickstart.md §3. Organisation cap 6 and two keys with cap 5 each; 4 + 4 requests give exactly 6
× 200 and 2 × 429 `scope: organisation`.

### Tests for User Story 2 ⚠️

- [X] T021 [P] [US2] Add organisation cases to `ThrottleDecisionTest.java`:
  - organisation cap 6 with two keys of cap 5 → exactly 6 admitted;
  - a key-cap refusal leaves the organisation count unchanged (AC3);
  - both levels over the cap → `scope KEY` with `retryAfterSeconds = max(keyWait, orgWait)` (Edge Cases);
  - headers report the level with the smaller remaining, and the key on a tie;
  - no organisation cap but the default organisation cap set → the default applies (AC4).
- [X] T022 [P] [US2] Write the new `src/test/java/nzi/fhir/validator/core/throttle/ThrottleConcurrencyTest.java`: 16
  threads × 1 000 checks on one key (cap 100) and one organisation (cap 150) in the same second → exactly
  `min(100, 150)` admitted, with no deadlock (timeout 10 s).
- [X] T023 [P] [US2] Add organisation cases to `ValidationThrottleIT.java`:
  - AC1: the 6/2 split, with `scope` `organisation` and the organisation `detail` text;
  - AC2: the body contains no other key's id or name.

### Implementation for User Story 2

- [X] T024 [US2] Extend `Throttle.check` in `src/main/java/nzi/fhir/validator/core/throttle/Throttle.java` to both
  levels:
  - lock the key window, then the organisation window (nested `synchronized`, always in this order);
  - evaluate both, then admit only if both have room, incrementing both. A level without a cap is skipped;
  - refusal: if both levels refuse, report the key with the max wait; otherwise report the level that refused;
  - header selection: minimum remaining, key on a tie.

  T021, T022 and T023 must pass.

**Checkpoint**: US1 and US2 work on one instance.

---

## Phase 5: User Story 3 - Operators manage caps in the shared database; trial defaults (Priority: P2)

**Goal**: the `limit set|remove|list` CLI, insert-only seeding of the trial key, and changes reaching every running
instance without a restart.

**Independent Test**: quickstart.md §1 and §4.

### Tests for User Story 3 ⚠️

- [X] T025 [P] [US3] Write the new `src/test/java/nzi/fhir/validator/cli/LimitCommandsIT.java`, following
  `AdminCliIT` and contracts/operations.md §2. It covers:
  - `limit list` on a fresh database (AC1, AC8);
  - `set` and `remove` for `--key`, `--org`, `--default-key` and `--default-org`, with the JSON shapes
    (`target`, `previous`, `perMinute`, `changed`, `effective.source` = `own`/`default`/`none`);
  - `remove` with no cap → exit 0 and `changed: false`;
  - `--per-minute 0`, `-1`, `1.5`, `abc`, `1000001` → exit 2 (AC4);
  - no target or two targets → exit 2;
  - unknown key or organisation → exit 3 (AC6);
  - the audit INFO line `cli: limit set key=<name> id=<uuid> old=<v|none> new=<v>` (via `LogCapture`);
  - `service_settings.updated_by` starts with `cli:`.
- [X] T026 [P] [US3] Add to `ThrottleMigrationIT.java`:
  - on a fresh database, starting the service installs the trial validator key from `FV_VALIDATOR_API_KEY` with
    `rate_limit_per_minute = 30`;
  - after setting it to NULL, a restart and a rotation to a new `FV_VALIDATOR_API_KEY` value leave it NULL (AC2);
  - after setting it to 45, a restart leaves 45.
- [X] T027 [P] [US3] Add to `ValidationThrottleIT.java`: change a key's cap with `LimitCommands`/SQL while the service
  runs (`FV_THROTTLE_CAPS_REFRESH_MS=1000`); the new cap is enforced within 2 s without a restart (AC3, FR-014).

### Implementation for User Story 3

- [X] T028 [US3] Update `src/main/java/nzi/fhir/validator/core/security/ApiKeyRepository.java`:
  - `upsertWellKnown` adds `rate_limit_per_minute` to the INSERT column list only: `Constant.TRIAL_…_RATE_LIMIT_PER_MINUTE`
    for the trial validator key, NULL for the system administrator key;
  - its `ON CONFLICT (id) DO UPDATE SET …` list must **not** include the column;
  - add `Future<Optional<LimitTarget>> findForLimit(String name)` (id, name, role, owner, revoked,
    rate_limit_per_minute);
  - add `Future<Integer> setRateLimit(SqlClient tx, UUID id, Integer perMinute)`, which uses
    `SELECT … FOR UPDATE`, then `UPDATE`, and returns the previous value or null.
- [X] T029 [P] [US3] Add `findForLimit(String shortName)` and `setRateLimit(SqlClient tx, UUID id, Integer perMinute)`
  (returns the previous value) to `src/main/java/nzi/fhir/validator/core/org/OrganisationRepository.java`.
- [X] T030 [P] [US3] Add `get(SqlClient, String name)` and `delete(SqlClient tx, String name)` to
  `src/main/java/nzi/fhir/validator/core/settings/ServiceSettingsRepository.java`. `upsert` already exists.
- [X] T031 [US3] Implement `src/main/java/nzi/fhir/validator/cli/LimitCommands.java`:
  - `run(cmd, ctx)` dispatches `set`, `remove` and `list`;
  - target parsing: exactly one of `--key`, `--org`, `--default-key` or `--default-org`;
  - `--per-minute` parsed as a whole number from 1 to `Constant.RATE_LIMIT_MAX_PER_MINUTE`;
  - each change runs in one transaction and returns the §2.2 JSON;
  - `list` builds the §2.3 JSON (defaults, organisations ordered by short name, keys with role and revoked,
    effective cap and source);
  - each change logs the audit line, with `updated_by = "cli:" + ServiceSettingsRepository.instanceName()`.
- [X] T032 [US3] Register the `limit` group in `src/main/java/nzi/fhir/validator/cli/AdminCli.java`
  (`case "limit" -> LimitCommands.run(cmd, ctx)`) and in its usage and help text. T025–T027 must pass.

**Checkpoint**: operators manage caps without restarts, and the trial defaults are in place.

---

## Phase 6: User Story 4 - No throttling when access control is disabled (Priority: P2)

**Goal**: with `FV_GLOBAL_DISABLED_ACL_SECURITY=true`, nothing is counted, refused or given headers, and stored caps
are kept.

**Independent Test**: quickstart.md §6.

### Tests for User Story 4 ⚠️

- [X] T033 [P] [US4] Extend `src/test/java/nzi/fhir/validator/web/AclDisabledIT.java`:
  - with the trial key and organisation capped at 1, 20 validate requests (with and without an `Authorization`
    header) all get 200 with no `RateLimit-*` headers (AC1, AC2);
  - `fv_throttle_checked_total` is absent or 0;
  - the startup log contains `Rate limits are not applied: access control is disabled
    (FV_GLOBAL_DISABLED_ACL_SECURITY)`;
  - after restarting with access control enabled, `limit list` shows the same caps (AC3).

### Implementation for User Story 4

- [X] T034 [US4] Startup logging for both access-control modes in `src/main/java/nzi/fhir/validator/Bootstrap.java`.
  The mode-dependent wiring itself is already done in T019:
  - when disabled, log the INFO line from contracts/operations.md §1;
  - when enabled, log `Rate limits: default key <n|none>/min, default organisation <n|none>/min; <k> key cap(s), <o>
    organisation cap(s)` after the first cap load.

  T033 must pass.

**Checkpoint**: the disabled mode is unaffected by the feature.

---

## Phase 7: User Story 5 - Keys of the system organisation are never throttled (Priority: P2)

**Goal**: system-organisation keys (any role) are never checked or counted and get no headers. Caps can't be set for
them. ADMIN keys of other organisations are throttled.

**Independent Test**: quickstart.md §5.

**Depends on**: US3 (the CLI) for T036 and T038.

### Tests for User Story 5 ⚠️

- [X] T035 [P] [US5] Add to `ValidationThrottleIT.java`:
  - with the default key and organisation caps at 1, 20 requests with `FV_ADMIN_API_KEY` and 20 with a VALIDATE key
    created for the system organisation → all 200, with no `RateLimit-*` headers (AC1);
  - an ADMIN key of another organisation with cap 1 → second request 429 (AC2, FR-006a).
- [X] T036 [P] [US5] Add to `LimitCommandsIT.java`:
  - `limit set --org <system short name> --per-minute 5` and `limit set --key "system administrator" --per-minute 5`
    → exit 4, `The system organisation and its keys are never rate limited`, nothing changed (AC3);
  - `limit list` shows the system organisation and its keys with `source: "not-throttled"` (AC4).

### Implementation for User Story 5

- [X] T037 [US5] In `src/main/java/nzi/fhir/validator/web/ThrottleHandler.java`, call `ctx.next()` without checking,
  counting or setting headers when `ApiKeyAuthHandler.caller(ctx).system()` is true (FR-006b). T035 must pass.
- [X] T038 [US5] In `src/main/java/nzi/fhir/validator/cli/LimitCommands.java`:
  - refuse (CONFLICT, exit 4) a target that is the system organisation, or a key whose owner is
    `Organisations.SYSTEM_ID`;
  - in `list`, report `effective.source = "not-throttled"` for them.

  T036 must pass.

**Checkpoint**: all five user stories work on one instance.

---

## Phase 8: Fleet-wide enforcement across instances (FR-005, FR-019, SC-001)

**Purpose**: share counts through PostgreSQL so caps hold across instances within cap + max(1, ⌈cap/10⌉), and fall
back to local mode when sharing fails (research R4, R5). This cuts across US1 and US2.

### Tests ⚠️

- [X] T039 [P] Write `src/test/java/nzi/fhir/validator/core/throttle/ThrottleSimulationTest.java` (pure unit test,
  deterministic clock and seeded random):
  - simulate 1–3 `Throttle` instances exchanging remote buckets through an in-memory "table", with a sync every
    1000 ms and a scripted visibility lag of up to 2 intervals;
  - caps {1, 5, 10, 30, 60, 600, 1200}; sticky, round-robin and random balancing; bursts and steady load over 5
    simulated minutes;
  - assert that in every rolling 60 s window the admitted count is ≤ cap for 1 instance and ≤ cap + max(1,
    ceil(cap/10)) for 2–3 instances;
  - assert that for caps ≥ 10·peers the full cap is admitted when load exceeds it.
- [X] T040 [P] Write `src/test/java/nzi/fhir/validator/core/throttle/ThrottleMultiInstanceIT.java` (two
  `ThrottleService` instances, one Testcontainers PostgreSQL, `FV_THROTTLE_SYNC_INTERVAL_MS=200`). It covers:
  - cap 30 with alternating checks → at most 33 admitted in 60 s;
  - `throttle_instances` has two live rows, and each instance reports peers = 1;
  - a third instance started late sees the first two instances' counts from its start-up 60 s read;
  - pausing the container for 5 s gives local mode (gauge 1, the WARN line from contracts/operations.md §4), with no
    check failing or refusing because of sharing; after unpausing, the INFO line appears and the gauge is 0;
  - rows older than 120 s are cleaned up;
  - a graceful `stop()` deletes the instance's heartbeat row;
  - a burst-guard refusal (`reason UNCONFIRMED`) carries `Retry-After` = `ceil(2 × 200 / 1000)` = 1 in this IT
    (2 at the 1000 ms default, asserted in `ThrottleDecisionTest`). A retry after that wait, with no other traffic,
    is admitted (FR-009, SC-003).

### Implementation

- [X] T041 Implement `src/main/java/nzi/fhir/validator/core/throttle/ThrottleCountsRepository.java`:
  - `flush(SqlClient, UUID instance, List<Row>)`: one `INSERT INTO throttle_counts SELECT * FROM
    unnest($1::char[], $2::uuid[], $3::bigint[], $4::uuid[], $5::int[]) ON CONFLICT (scope, subject_id, epoch_second,
    instance_id) DO UPDATE SET count = EXCLUDED.count`;
  - `heartbeat(UUID, String name)`: upsert `seen_at = now()`; return the count of other rows with `seen_at > now() -
    interval '5 seconds'`;
  - `read(UUID me, long sinceSecond)`: `SELECT scope, subject_id, epoch_second, sum(count) … WHERE epoch_second >= $1
    AND instance_id <> $2 GROUP BY 1, 2, 3`;
  - `cleanup(long beforeSecond)`: delete counts older than 120 s and instances unseen for 1 h;
  - `deleteInstance(UUID)`.
- [X] T042 Extend `Window.java` and `Throttle.java` for peers (research R4):
  - track unconfirmed admissions per window; an admission is confirmed once a flush containing it has succeeded and
    one more sync interval has passed;
  - `Throttle.check` takes `peers`:
    - with `p = 0`: admit iff `V + 1 <= cap`;
    - with `p >= 1`: let `B = max(1, ceil(cap/10))` and `r = max(1, floor(B/p))`; admit iff `V + 1 + p*r <= cap + B`
      and `unconfirmed < r`. A refusal caused only by `unconfirmed >= r` is `reason UNCONFIRMED` with
      `retryAfterSeconds = ceil(2 × syncIntervalMs / 1000)` (2 at the default; research R4), the time an
      admission needs to be confirmed;
  - `applyRemote(scope, id, second, sum)` creates the window if absent and calls `setRemote`.

  T039 must pass.
- [X] T043 Implement `src/main/java/nzi/fhir/validator/core/throttle/ThrottleSync.java`:
  - a Vert.x periodic timer every `throttleSyncIntervalMs()`. If a tick is still running, the next one is skipped and
    counted;
  - each tick runs flush (changed buckets since the last flush), then heartbeat (sets `peers`), then read (last 5 s,
    applies remote, confirms admissions);
  - cleanup every 30 s;
  - local mode starts when any step fails or the last good read is older than 3 intervals: `peers` = 0, WARN once,
    gauge 1, and `fv_throttle_sync_failures_total{step}` incremented. It ends on the first fully successful tick: INFO
    with the duration, gauge 0;
  - `instanceId = UUID.randomUUID()` per process start.
- [X] T044 Integrate the sync into `ThrottleService.java`:
  - `start()` runs caps load → a full read of the last 60 s (`read(me, now − 60)`) → `ready` → the sync timer;
  - `stop()` cancels the timers and calls `deleteInstance`;
  - pass `peers` from the sync into `check`.

  T040 must pass, and the earlier ITs still pass.

**Checkpoint**: caps hold fleet-wide and degrade safely.

---

## Phase 9: Observability (FR-016–FR-019, Principle VII)

- [X] T045 [P] Add to `ValidationThrottleIT.java`:
  - after one refusal, `/metrics` has `fv_throttle_refused_total{scope="key",reason="cap"} 1.0` and
    `fv_throttle_checked_total`;
  - the log has exactly one `throttle: key <uuid> (org <uuid>) refused 1 request(s) in the last minute (cap 5);
    request <id>` line;
  - 100 more refusals in the same minute add no line; after the minute, one summary line with the count;
  - no metric carries a key, organisation or instance id;
  - extend `src/test/java/nzi/fhir/validator/core/config/NoSecretsInLogsTest.java` so that no throttle log line
    contains a key value.
- [X] T046 Add `bindThrottle(ThrottleService)` to `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java`
  with the meters of contracts/operations.md §3:
  - counters `fv_throttle_checked_total`, `fv_throttle_refused_total{scope,reason}`, `fv_throttle_evictions_total`,
    `fv_throttle_sync_failures_total{step}` and `fv_throttle_ticks_skipped_total`;
  - gauges `fv_throttle_tracked_subjects`, `fv_throttle_peers` and `fv_throttle_local_mode`;
  - timer `fv_throttle_sync_seconds`;
  - pre-register the counters per tag combination, following the existing `counters` map;
  - call it from `ServiceRegistry` when enabled; record evictions through Caffeine's removal listener (cause SIZE).
- [X] T047 Implement the bounded refusal log in `ThrottleHandler.java`, using the window's
  `refusedSinceLog`/`lastLogAtMs`/`firstRefusedRequestId`:
  - log at once on the first refusal in a minute;
  - otherwise count, and emit the summary on the next refusal after 60 s;
  - use the request id from `RequestIdHandler`.

  T045 must pass.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [X] T048 [P] Add a "Rate limits" section to `docs/operations.md`: how caps work, the `limit` CLI, the env vars,
  metrics and suggested alerts, local mode, the small-cap under-admission note (research R4), and that 429s are not in
  `validation_usage`.
- [X] T049 [P] Update `README.md`: add the three `FV_THROTTLE_*` variables to the settings table and one paragraph on
  429 and the `RateLimit-*` headers.
- [X] T050 [P] Release metadata:
  - `CHANGELOG.md`: a `[1.4.0]` entry covering:
    - **Added**: 429 `rate-limited`, `RateLimit-*` headers, the `limit` CLI group, the env vars, the metrics and V18;
    - **Changed**: refused requests are not usage-recorded, and validate can now answer 429;
  - bump `pom.xml` `<version>` to `1.4.0`.
- [X] T051 [P] Add an amendment note under the header of `specs/008-api-key-hardening/spec.md`: "Amended by spec 014
  (1.4.0): requests refused with 429 by the rate limiter are not written to validation_usage (014 research R9)".
- [X] T052 Let `perf/compare-run.sh` set the default caps before the run, through optional env `THROTTLE_DEFAULT_KEY` /
  `THROTTLE_DEFAULT_ORG` running `limit set --default-key/--default-org` in the service container. Run the three runs
  of quickstart.md §8 and record them in the new `perf/results-1.4.0.md`. The p95 difference must be ≤ 5 ms
  (SC-002), with no blocked-thread warnings.

  SC-006 (others unaffected): the at-cap run also drives a second, concurrent k6 scenario at a steady low rate with a
  key of another organisation whose caps are far above its rate. Record that key's p95 and 429 count. Its p95 must stay
  within 5 ms of the uncapped baseline, with zero 429s.
- [X] T053 Run `mvn verify` and walk through quickstart.md §1–§9 by hand. Fix any drift between `openapi.yaml`, the
  contracts and the behaviour. Update `specs/014-api-throttling/checklists/requirements.md` notes if anything changed.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none. T001–T003 run in parallel.
- **Foundational (Phase 2)**: needs T002 and T003. T004 → T005 → (T006, T011); T007 → T008; T009 → T010 → T011; T012
  is independent. It blocks every story.
- **US1 (Phase 3)**: needs Phase 2.
- **US2 (Phase 4)**: needs US1's `Throttle`/`ThrottleHandler` (T015, T018, T019).
- **US3 (Phase 5)**: needs Phase 2 and T017 (cap reload). It is independent of US2.
- **US4 (Phase 6)**: needs T019 (wiring).
- **US5 (Phase 7)**: T035/T037 need T018; T036/T038 need US3 (T031).
- **Fleet-wide (Phase 8)**: needs US1 and US2 (`Throttle` with both levels).
- **Observability (Phase 9)**: needs Phase 8 (sync metrics); the refusal log part only needs US1.
- **Polish (Phase 10)**: needs everything.

### User Story Dependencies

- US1 (P1) is the MVP. US2 (P1) builds on US1's `Throttle.check`.
- US3 (P2), US4 (P2) and US5 (P2) can proceed in parallel after US1. US5's CLI part waits for US3.

### Within Each Phase

- Tests first, and they must fail. Then implementation; the phase ends when its tests pass.
- Repositories before services, services before the handler and the CLI.

## Parallel Opportunities

- Phase 1: T001, T002 and T003.
- Phase 2: T006, T007, T009 and T012 in parallel once T005 is done (T007/T009/T012 do not need it).
- US1: T013 and T014 together; T016 and T020 alongside T015.
- US2: T021, T022 and T023 together.
- US3: T025, T026 and T027 together; T029 and T030 in parallel with T028.
- US4 (T033/T034) and US5's handler part (T035/T037) can run in parallel with US3.
- Phase 8: T039 and T040 together; T041 in parallel with T042.
- Phase 10: T048, T049, T050 and T051 together.

## Parallel Example: User Story 1

```text
Task: "T013 [US1] key-level cases in core/throttle/ThrottleDecisionTest.java"
Task: "T014 [US1] key-level cases in web/ValidationThrottleIT.java"
# then
Task: "T015 [US1] Throttle.java (key level)"
Task: "T016 [US1] ProblemException.rateLimited"     # parallel with T015
Task: "T020 [US1] CorsConfig exposed headers"       # parallel with T015
```

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 and Phase 2: contract, V18, caps snapshot, window.
2. Phase 3 (US1): one instance enforces key caps with 429 and headers.
3. **Stop and validate**: quickstart.md §2. The MVP is complete only on a single instance.

### Incremental Delivery

1. + US2 → organisation caps (both P1 stories done, still one instance).
2. + US3 → operators manage caps; trial defaults.
3. + US4, US5 → exemptions.
4. + Phase 8 → safe on several instances. **Required before any multi-instance deployment.**
5. + Phase 9 and Phase 10 → observable, documented, benchmarked; release 1.4.0.

## Notes

- [P] means different files and no dependency on an unfinished task.
- No database access on the request path. `ThrottleHandler` and `Throttle` must never call a repository.
- Do not edit V15–V17. Every schema change is in V18.
- Commit after each task or checkpoint. Do not tag a release until Phase 10 is done.
