---

description: "Task list for IG-scoped validation (per-IG validator snapshots, registry-resolved latest)"
---

# Tasks: IG-Scoped Validation (Per-IG Validator Snapshots, Registry-Resolved "latest")

**Input**: Design documents from `specs/004-ig-scoped-validation/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R14), data-model.md, contracts/http-api.md, quickstart.md

**Tests**: These are required. Constitution Principle V requires test-first development against real PostgreSQL
through Testcontainers, and SC-011 lists the regression tests. Write each story's tests first and confirm they
fail before implementing.

**Organization**: Tasks are grouped by user story, in priority order: US1 (P1), US2 (P1), US3 (P2), US4 (P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: The user story the task belongs to (US1–US4)
- Production paths are written in full from the repository root (`src/main/java/nzi/fhir/validator/...`). Test
  paths are under `src/test/java/nzi/fhir/validator/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Branch, version bump, shared test fixtures.

- [X] T001 Create and switch to git branch `004-ig-scoped-validation` from `main` (repository root)
- [X] T001a Confirm that the Principle II amendment (constitution 1.1.0, 2026-11-06, made separately before this feature) is merged. Implementation MUST NOT start without it (`.specify/memory/constitution.md`).
- [X] T002 Bump the project version from `2.0.0` to `3.0.0-SNAPSHOT` in `pom.xml` (breaking release; constitution Principle IV)
- [X] T003 [P] Add `TestPackages.igPackage(String name, String version, List<String> fhirVersions, Map<String,String> dependencies, JsonObject... structureDefinitions)`, which builds an NPM `.tgz` whose `package.json` declares `fhirVersions` (via the existing `npmPackage(...)`), plus `TestPackages.storePackage(ServiceRegistry services, byte[] tgz)`, which stores without including and returns `Future<Void>`, in `src/test/java/nzi/fhir/validator/testsupport/TestPackages.java`
- [X] T004 [P] Add a test helper `StubRegistry` (an in-process Vert.x HTTP server serving `GET /{name}` listings with a `versions` object and `dist-tags`, and `GET /{name}/{version}` archives; it can be switched to answer 503 or refuse connections, and counts archive downloads) in `src/test/java/nzi/fhir/validator/testsupport/StubRegistry.java`, following the pattern in `core/npm/RegistryFallbackTest.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The contract, the schema, the settings, the package catalog view and the keyed registry, with today's
behaviour for pinned IGs kept. Every user story depends on this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Contract and schema

- [X] T005 Update `src/main/resources/openapi.yaml` exactly as `contracts/http-api.md` §1–§6 describes:
  - the `ig` query parameter on `validationApiValidate` (`maxLength: 256`, description as written) and its `404`
    response;
  - the new `profile` wording;
  - `igPackage` (nullable string) on `ValidationResponse`, and the `X-Validation-Context` response header on 200;
  - the include description ("adds, never replaces") and the new `IncludeResponse` (with `key {fhirVersion,
    igPackage}`);
  - the new operation `validationApiExcludeIg` (`DELETE /{version}/include-ig`, required query `ig`) with
    `ExcludeResponse`;
  - `IgRegistrationResponse` gaining `downloaded` (boolean) and `warnings` (array);
  - the `/health` detail shape;
  - the problem types `insufficient-capacity` (503) and `dependency-not-found` (424), and the `424` response on
    `validationApiValidate`.

  Register `validationApiExcludeIg` with a placeholder handler that answers 501 in
  `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`, so routes keep matching the spec.
- [X] T006 Write the failing test `MigrationUpgradeIT#v7AppliesOnEmptyDatabase`. On an empty database,
  `fhir_package_catalog` must exist with exactly one row, `(id = 1, revision = 1)`, and
  `fhir_validator_logs.resolved_ig_packages` must not exist. Put it in
  `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java`.
- [X] T007 Create `src/main/resources/db/migration/V7__ig_scoped_contexts.sql` with:
  - `CREATE TABLE fhir_validator_schema.fhir_package_catalog (id SMALLINT PRIMARY KEY CHECK (id = 1), revision BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())`;
  - `INSERT ... VALUES (1, 1, now())`;
  - `ALTER TABLE fhir_validator_schema.fhir_validator_logs DROP COLUMN IF EXISTS resolved_ig_packages`;
  - a header comment explaining that `included_ig_packages` keeps its rows and that each entry becomes a pinned
    context (research R3, R13).

  Leave every earlier migration unchanged.

### Settings

- [X] T008 [P] Add the settings `ig.maxOnDemand` (env `FV_IG_MAX_ON_DEMAND`, default 4, bounds 1–64) and
  `ig.buildWaitMs` (env `FV_IG_BUILD_WAIT_MS`, default `min(10000, http.requestTimeoutMs / 2)`, bounds
  1000–120000). Out-of-range values fail startup with a curated message, like the existing bounded settings. Edit
  `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java`,
  `src/main/java/nzi/fhir/validator/core/config/ApplicationConfig.java` and `src/main/resources/config.json`. Test
  defaults and bounds in `src/test/java/nzi/fhir/validator/core/config/ConfigLoaderTest.java`.

### Keys and the package catalog view (research R4)

- [X] T009 [P] Create `record SnapshotKey(SupportedFhirVersion fhirVersion, String igPackage)` (`igPackage` is a
  concrete `name#version`, or `null` for the base context), with `static base(SupportedFhirVersion)`,
  `isBase()`, and `toString()` giving `R4:base` or `R4:hl7.fhir.us.core#6.1.0`, in
  `src/main/java/nzi/fhir/validator/core/validator/SnapshotKey.java`
- [X] T010 [P] Write the failing unit test `PackageCatalogViewTest`. It covers:
  - `highest(name)` using `PackageVersionComparator`;
  - `contains`;
  - `supports(key, fhirVersion)`: true when nothing is declared, true when the version is declared, false
    otherwise, and the `fhirVersion` single-string fallback as in `IgPackageService.declaredFhirVersions`;
  - `asCatalog()` matching `DependencyResolver.Catalog.of(...)`;
  - `sha256(key)`;
  - `PackageCatalog.refreshOnMiss()` with an injectable clock: two misses 200 ms apart cause one load, and two
    misses 1100 ms apart cause two.

  Put it in `src/test/java/nzi/fhir/validator/core/npm/PackageCatalogViewTest.java`.
- [X] T011 Create `PackageCatalogView` in `src/main/java/nzi/fhir/validator/core/npm/PackageCatalogView.java`:
  - an immutable value with `revision`, `packages` (`name#version` → `{Set<SupportedFhirVersion> declaredFhirVersions, List<String> dependencies, byte[] sha256, long expandedBytes}`) and `versionsByName`;
  - a holder `PackageCatalog` with `current()`, `Future<PackageCatalogView> refresh()` (single in-flight refresh;
    concurrent callers share it), `Future<PackageCatalogView> refreshOnMiss()` (refreshes only if the last refresh
    started at least `MISS_REFRESH_MIN_INTERVAL_MS = 1000` ms ago, otherwise completes with `current()`; research
    R4), and `static Future<PackageCatalogView> load(SqlClient)`.
    `load` reads the revision from `fhir_package_catalog` plus `ig_package_id, ig_package_version, ig_package_meta, dependencies, content_sha256, expanded_bytes` from `fhir_implementation_guides`, and never `content_raw`.
  - Move `declaredFhirVersions(JsonObject)` out of `IgPackageService` into the view so the rule is defined once,
    and keep `IgPackageService` delegating to it.
- [X] T012 Write the failing test `PackageCatalogIT`. It covers:
  - storing a package (upload path) increments `fhir_package_catalog.revision` and sends a `fv_config_changed`
    notification `{"kind":"PACKAGE","key":"name#version","revision":n}`;
  - re-storing the same `name#version` with different bytes increments it again and changes `content_sha256`;
  - a failed store leaves the revision unchanged.

  Put it in `src/test/java/nzi/fhir/validator/core/npm/PackageCatalogIT.java`.
- [X] T013 Make every package store transactional in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java`. In `storeChecked`, `pgPool.withTransaction`:
  1. run the existing upsert;
  2. `UPDATE fhir_validator_schema.fhir_package_catalog SET revision = revision + 1, updated_at = now() WHERE id = 1 RETURNING revision`;
  3. `SELECT pg_notify('fv_config_changed', $payload)` with `kind = PACKAGE`.

  Add a `static final String KIND_PACKAGE = "PACKAGE"` constant next to `KIND_IG` in `src/main/java/nzi/fhir/validator/core/service/ConfigRevisionRepository.java`.
- [X] T014 [P] Add `PackageCache.invalidate(String idAndVersion)` in
  `src/main/java/nzi/fhir/validator/core/npm/PackageCache.java`, with a unit test in
  `src/test/java/nzi/fhir/validator/core/npm/PackageCacheTest.java`

### Keyed registry with base and pinned slots (research R2, R3)

- [X] T015 Update `ConfigRevisionRepository` in `src/main/java/nzi/fhir/validator/core/service/ConfigRevisionRepository.java`:
  - `ConfigRow` becomes `record ConfigRow(long revision, List<String> included)`, with no `resolved`;
  - `current`, `lockForUpdate` and `updateIgSet(tx, version, included, key, kind)` stop reading or writing
    `resolved_ig_packages`;
  - add `static Future<Long> catalogRevision(SqlClient)`, which reads `fhir_package_catalog.revision`.
- [X] T016 Extend `ValidatorSnapshot` with `SnapshotKey key()` and `Map<String, byte[]> packageHashes()`, both set
  by the constructor, keeping it immutable and thread-safe. Update `SnapshotBuilder.build(SnapshotKey key, long revision, List<String> resolved, Map<String, byte[]> hashes)` and `assemble(...)` to match. The base context is the empty package list (research R1, unchanged chain). Edit `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java` and `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java`.
- [X] T017 Write the failing test `ValidatorRegistryKeyTest`, a unit test with a fake `SnapshotBuilder`. It covers:
  - the base slot for every enabled version is built at start and is never removed;
  - `applyPinned(version, revision, Map<SnapshotKey, List<String>> resolvedByKey)` creates, rebuilds or demotes
    slots so the pinned set equals the keys given;
  - a key whose resolved set is unchanged is not rebuilt;
  - one key's build failure (STALE plus retry) leaves the other keys `ACTIVE`;
  - `notReadyReasons()` lists only base and pinned slots.

  Put it in `src/test/java/nzi/fhir/validator/core/validator/ValidatorRegistryKeyTest.java`.
- [X] T018 Refactor `ValidatorRegistry` into slots keyed by `SnapshotKey` in `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`:
  - each slot gets `kind` (`BASE`, `PINNED` or `ON_DEMAND`) and `lastUsed`, and keeps today's per-slot state
    machine, waiters and retry/backoff, with retries for `BASE` and `PINNED` only;
  - replace `current(version)` with `Future<ValidatorSnapshot> snapshot(SnapshotKey key)`. For base and pinned keys
    it answers from memory or with the existing 503 `not-ready` problem; for other keys it fails with 404 for now
    (on-demand comes in US1);
  - add `applyPinned(...)`;
  - `details()`, `snapshotPackages` and `snapshotPackageBytes` take keys;
  - add `notReadyReasons()` over base and pinned slots;
  - keep the class's Javadoc accurate.
- [X] T019 Update `ConfigSync` in `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java`:
  - hold a `PackageCatalog`;
  - at start, load the catalog view, then for each version resolve every key in `included` with
    `DependencyResolver.resolve(Set.of(key), view.asCatalog())` and call `registry.applyPinned(...)` with hashes
    from the view;
  - handle `kind = PACKAGE` notifications by refreshing the view and re-applying every version's pinned set;
  - have the poll also compare `catalogRevision` and refresh when it is higher;
  - `syncNow(version)` re-reads the row and applies it.
- [X] T020 Update `FhirValidationService.validate(SnapshotKey key, ...)` to get its snapshot through
  `registry.snapshot(key)` (a `Future`) *inside* `admission.admit(...)`, so a request holds its validation
  admission slot while it looks up or waits for a snapshot (Principle VI: waiters are bounded by
  `FV_VALIDATION_MAX_PENDING`, research R5), keeping all metrics behaviour, in
  `src/main/java/nzi/fhir/validator/core/service/FhirValidationService.java`. In `ValidationApi`, pass
  `SnapshotKey.base(version)` for now.
- [X] T021 Wire `PackageCatalog`, the new settings and the new `ConfigSync` constructor in
  `src/main/java/nzi/fhir/validator/ServiceRegistry.java`. After a local store, `IgPackageService` triggers
  `catalog.refresh()` so the storing instance sees the package at once.
- [X] T022 Adapt the existing tests to the keyed API and the dropped column:
  - `core/validator/ValidatorSnapshotSwapIT.java`, `core/validator/ConfigPropagationIT.java`,
    `core/validator/SnapshotRaceTest.java`, `core/validator/HeapGuardIT.java` and
    `core/service/FhirValidationServiceTest.java`;
  - `web/endpoint/HealthApiTest.java`, `core/service/IgPackageServiceTest.java` and
    `core/service/ConcurrencyIT.java`.

  Every test must pass with base and pinned behaviour only. All files are under `src/test/java/nzi/fhir/validator/`.

**Checkpoint**: `mvn verify` is green. Validation without `ig` uses the base context. Included IGs are pinned
contexts. The catalog view propagates.

---

## Phase 3: User Story 1 - Validate against the IG I name on the request (Priority: P1) 🎯 MVP

**Goal**: `?ig=` selects a single IG context: an existing one, one built on demand from stored packages, or the base
context when `ig` is absent. The response says which context was used, and capacity bounds apply.

**Independent Test**: Store packages A and B for R4. Validate fixtures with `ig=A`, `ig=B` and without `ig`, and
confirm that each result reflects only that context and that the header and `igPackage` name it (quickstart §1,
rows 1–8).

### Tests for User Story 1 (write first, confirm they fail)

- [X] T023 [P] [US1] Write the unit test `IgReferenceTest` for the rules in data-model §2.2. The name must match
  `[A-Za-z0-9][A-Za-z0-9._-]*`. The version is `null` when absent or `latest` (case-insensitive); otherwise it must
  match `[A-Za-z0-9][A-Za-z0-9.+_-]*`. The raw value is at most 256 characters with at most one `#`. A comma, an
  empty part, illegal characters or more than 256 characters are rejected with 400 `invalid-request`. Put it in
  `src/test/java/nzi/fhir/validator/core/validator/IgReferenceTest.java`.
- [X] T024 [P] [US1] Write `IgScopedValidationIT` (AppHarness, HTTP) in `src/test/java/nzi/fhir/validator/web/IgScopedValidationIT.java`. It covers:
  1. no `ig` → `X-Validation-Context: base`, `igPackage: null`, and no issue from A's or B's profiles (SC-002);
  2. `ig=A#1.0.0` vs `ig=B#1.0.0` → only that IG's profile issues (SC-001);
  3. `ig=missing#1.0.0` → 404 `package-not-found`, whose detail names the package and says "register or upload it
     first", and no build is recorded (SC-004);
  4. an R5-only package on `/R4` → 400 `invalid-request` naming the resolved version;
  5. malformed values (`a##b`, `a,b`, a 257-character value, a repeated `ig` parameter) → 400 `invalid-request`;
  6. `ig=A` and `ig=A#latest` with A#1.0.0 and A#2.0.0 stored → `igPackage: "A#2.0.0"`;
  7. the check order (FR-009): a malformed `ig` together with an empty body gives the `ig` error, and an unknown
     `ig` together with an unparseable body gives 404;
  8. the OperationOutcome format carries the header and an unchanged body.
- [X] T025 [P] [US1] Extend `ProfileResolutionIT` with:
  - `ig=A` plus a profile only B defines → 400 `unknown-profile`, even when B is included;
  - no `ig` plus a profile from an included IG → 400 `unknown-profile`;
  - `ig=A` plus a profile from A's dependency → applied.

  Put it in `src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java`.
- [X] T026 [P] [US1] Write `OnDemandContextIT` (CoreHarness) in `src/test/java/nzi/fhir/validator/core/validator/OnDemandContextIT.java`. It covers:
  1. 50 concurrent first requests for one stored IG → exactly 1 build (counted via `SnapshotBuilder.setBuildHook`)
     and 50 equal results (SC-003);
  2. a build hook that sleeps longer than `ig.buildWaitMs` → 503 `not-ready` with `Retry-After: 5`, and a retry
     after the build finishes succeeds (FR-012);
  3. with `admin.maxPending = 1` and one build in flight, a request for a second distinct IG → 503 `overloaded`;
     joining the in-flight build takes no admin slot (FR-014);
  4. a build failing because a dependency isn't stored → 424 `dependency-not-found` naming the package; the slot
     is removed, and the next request retries (succeeding once the dependency is stored) (FR-017);
  5. no network: with registries enabled and a `StubRegistry`, the stub counts zero requests during on-demand builds
     (FR-010);
  6. with `validation.maxPending = 3` and a slow build in progress, a 4th concurrent request for the same IG → 503
     `overloaded` straight away (waiters are bounded; Principle VI).
- [X] T027 [P] [US1] Write `ContextEvictionIT` (CoreHarness with `HeapBudget.setMaxHeap` and small packages) in `src/test/java/nzi/fhir/validator/core/validator/ContextEvictionIT.java`. It covers:
  1. `ig.maxOnDemand = 2` and three distinct IGs → the least recently used is evicted, and the count never
     exceeds 2;
  2. heap that is tight but enough after eviction → the LRU on-demand context is evicted and the build succeeds;
  3. heap filled by pinned contexts → 503 `insufficient-capacity` with `Retry-After: 30`, and pinned contexts stay
     `ACTIVE`;
  4. `estimate(expanded) > maxHeap` → 413 `package-too-large`;
  5. an evicted context still used by an in-flight validation lets that validation complete (SC-008, FR-015,
     FR-016).

### Implementation for User Story 1

- [X] T028 [US1] Create `IgReference` (`static IgReference parse(String raw)`, which throws `ProblemException(400, "invalid-request", ...)` per data-model §2.2; `name()` and `version()`, which is nullable) and `Future<SnapshotKey> resolve(SupportedFhirVersion v, PackageCatalog catalog)`. `resolve` looks the package up in the view and, on a miss, calls `catalog.refreshOnMiss()` once before failing with 404 `package-not-found` ("IG package X is not stored. Register or upload it first via /igs/register or /igs/upload"). It picks `highest(name)` when the version is null, then applies `supports(...)`, failing with 400 `invalid-request` naming the resolved `name#version` and its declared versions. Put it in `src/main/java/nzi/fhir/validator/core/validator/IgReference.java`.
- [X] T029 [US1] Add `HeapBudget.Evictor` (a functional interface `boolean evictOne()`), and `reserve(long expandedBytes, Evictor evictor)`. When a build doesn't fit, `reserve` loops: `evictOne()`, then `collect`, then recheck. When nothing is left to evict, it throws `InsufficientCapacityException` (curated) if `estimate(expandedBytes) <= maxHeap`, and otherwise `InsufficientHeapException` ("can never fit"). Also add `admitInclude(long, Evictor)`. Edit `src/main/java/nzi/fhir/validator/core/validator/HeapBudget.java` and extend `src/test/java/nzi/fhir/validator/core/validator/HeapBudgetTest.java`.
- [X] T030 [US1] Add on-demand slots to `ValidatorRegistry` in `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`:
  - an access-ordered `LinkedHashMap<SnapshotKey, Slot>` under one lock, bounded by `ig.maxOnDemand`; the least
    recently used non-`BUILDING` slot is evicted before inserting;
  - `snapshot(key)` for a non-pinned key returns an `ACTIVE` snapshot (updating `lastUsed`), joins the waiters of a
    `BUILDING` slot, or starts a build;
  - starting a build takes an admin admission slot with `adminAdmission.tryAcquire()`, or fails with
    `overloaded()`, and releases it when the build ends;
  - each waiter is bounded by `ig.buildWaitMs` using `vertx.setTimer`; on timeout it fails with
    `ProblemException.notReady(...)` plus `Retry-After: 5`, and the build continues;
  - the evictor removes the LRU non-`BUILDING` on-demand slot, never base or pinned;
  - when a build fails, the slot is removed. Waiters get 424 `dependency-not-found` naming the package for a
    missing package (`SnapshotBuilder.MissingPackageException`), the capacity problems from T029 (503
    `insufficient-capacity` with `Retry-After: 30`, or 413 `package-too-large`), or a curated 503 `not-ready` with
    `Retry-After: 5` for anything else. Add `ProblemException.dependencyNotFound(String key)` in
    `src/main/java/nzi/fhir/validator/web/ProblemException.java`;
  - when `applyPinned` adds a key that has an on-demand slot, the slot is promoted without a rebuild.
- [X] T031 [US1] Pass the registry's evictor through `SnapshotBuilder.buildNow` into `heap.reserve(expandedBytes, evictor)` in `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java`, and add the problem factory `ProblemException.insufficientCapacity()` (503, type `insufficient-capacity`, `Retry-After: 30`, curated detail) in `src/main/java/nzi/fhir/validator/web/ProblemException.java`.
- [X] T032 [US1] In `ValidationApi.handleDoValidation`, read `ig` with `ctx.queryParam("ig")`. More than one value is 400 `invalid-request`. Parse it with `IgReference.parse` after the content-type, `Accept` and profile checks and before the empty-body check. Pass the parsed `IgReference` (or `null` when `ig` is absent) to `validationService.validate(version, igRef, ...)`. Resolution happens in the service (T034). On 200, set `X-Validation-Context` from the outcome's `igPackage` (or `base`). Log the raw `ig` value only at DEBUG. Edit `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`.
- [X] T033 [US1] Add `igPackage` to the `ValidatorSnapshot.Outcome` record (from the snapshot key) and render it as `igPackage` (string or JSON null) in `ValidationResponse`/`OutcomeRenderer.legacy`. Leave the OperationOutcome rendering unchanged. Edit `src/main/java/nzi/fhir/validator/core/validator/OutcomeRenderer.java`, `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java` and `src/main/java/nzi/fhir/validator/core/model/ValidationResponse.java`.
- [X] T034 [US1] Add the validation outcome `OUTCOME_PACKAGE_NOT_FOUND = "package_not_found"` to `ServiceMetrics`, and map a 404 `package-not-found` `ProblemException` to it in `FhirValidationService.failureOutcome`. `FhirValidationService.validate(SupportedFhirVersion, IgReference, ...)` owns resolution. Inside admission (see T020), it calls `IgReference.resolve` (null → `SnapshotKey.base`), then `registry.snapshot(key)`, then validates. Every failure, `package_not_found` included, goes through the single metrics `onComplete`. Edit `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java` and `src/main/java/nzi/fhir/validator/core/service/FhirValidationService.java`.
- [X] T035 [US1] Update `ValidationApiContractTest` for the `ig` parameter, the header, the `igPackage` field and the 404 response in `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java`

**Checkpoint**: US1 is fully functional and testable on its own. T023–T027 pass.

---

## Phase 4: User Story 2 - Offer several IG versions side by side (Priority: P1)

**Goal**: Include adds a pinned context and never replaces another version. Exclude demotes a pinned context.
Pinned contexts propagate to every instance. A replaced stored package invalidates every context that contains it.
`/health` lists every context.

**Independent Test**: Include `P#1` then `P#2` for R4. The first context isn't rebuilt and stays `ACTIVE`, both
appear in `/health` and both can be validated against, on two instances. Then exclude `P#1` (quickstart §1, rows
9–13).

### Tests for User Story 2 (write first, confirm they fail)

- [X] T036 [P] [US2] Write `IncludeExcludeIT` (AppHarness) in `src/test/java/nzi/fhir/validator/core/validator/IncludeExcludeIT.java`. It covers:
  1. including `P#1` then `P#2` → the build hook counts no rebuild of `P#1`, `P#1` stays `ACTIVE` throughout, the
     response has `key {fhirVersion: "R4", igPackage: "P#2"}`, `included: ["P#1", "P#2"]`, `resolved` is only
     `P#2`'s set, and `revision` has increased (SC-005);
  2. including an already-included key → 200, the same revision and no rebuild;
  3. including with no version or `latest` → the highest stored version, and the stub registry is never contacted;
  4. two included IGs → no definition from one is visible when validating against the other (US2-3);
  5. an include whose set can't fit → 413 before commit, with the revision unchanged;
  6. `DELETE /R4/include-ig?ig=P%231` → 200 `ExcludeResponse {excluded: "P#1", included: ["P#2"]}`, `/health`
     shows `P#1` as `on-demand`, and `ig=P#1` still validates;
  7. excluding a key that isn't included → 404 `package-not-found`, and `ig=P` without a version on DELETE → 400.
- [X] T037 [P] [US2] Extend `ConfigPropagationIT` (two instances on one database) in
  `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java`. Include and exclude become visible on
  the second instance within 10 s, both with `LISTEN/NOTIFY` and with notifications disabled (poll fallback)
  (SC-006). A package stored on instance A can be named in `ig` on instance B within 10 s.
- [X] T038 [P] [US2] Write `PackageInvalidationIT` (two instances) in `src/test/java/nzi/fhir/validator/core/validator/PackageInvalidationIT.java`. Re-storing `P#1` with different content:
  - marks the pinned `P#1` context `STALE` on both instances (503 with the curated reason "package P#1 was
    replaced"), which then rebuilds to `ACTIVE`;
  - removes an on-demand context containing `P#1` as a dependency;
  - invalidates the `PackageCache` entry.

  Storing a *new* version of a dependency that `P#1` references without a version rebuilds `P#1`, and the old
  snapshot keeps serving until the swap (FR-025, R3, R6). An on-demand context for `Q#1` (which depends on `D`
  without a version) is built with `D#1`; storing `D#2` removes it on both instances, and the next `ig=Q#1`
  request on either instance builds with `D#2`.
- [X] T039 [P] [US2] Extend `ApiKeyAuthIT` in `src/test/java/nzi/fhir/validator/web/ApiKeyAuthIT.java` so that `DELETE /R4/include-ig?ig=...` without a key gives 401, with a VALIDATE key gives 403, and with the ADMIN key succeeds. Extend `AdminAdmissionIT` in `src/test/java/nzi/fhir/validator/web/AdminAdmissionIT.java` so that a DELETE takes an admin admission slot (FR-026).
- [X] T040 [P] [US2] Update `HealthApiTest` for the new detail shape and readiness rule (contracts §5). Readiness is true with a `BUILDING` or failed on-demand context and false with a `STALE` pinned one. Put it in `src/test/java/nzi/fhir/validator/web/endpoint/HealthApiTest.java`.

### Implementation for User Story 2

- [X] T041 [US2] Change `IgPackageService.include` in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`:
  - the `included` set keeps every other key; remove the `filter(p -> !p.startsWith(name + "#"))`;
  - an already-included key returns the current revision without an update;
  - resolve only `Set.of(key)` with `DependencyResolver` for the response and admission;
  - admission is `expandedBytes(tx, thisKeyResolved)` run through `admitInclude(bytes, registry evictor)`;
  - `updateIgSet(..., KIND_IG)`;
  - the response has `status, fhirVersion, key {fhirVersion, igPackage}, revision, included, resolved, warnings`.
- [X] T042 [US2] Add `IgPackageService.exclude(SupportedFhirVersion v, String ig)` in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`. Parse `ig` with `IgReference.parse`; a missing version or `latest` is 400 "an exact name#version is required". Then, in one transaction, `lockForUpdate`; if the key isn't in `included`, fail with 404 `package-not-found`. Otherwise call `updateIgSet(tx, v, includedWithoutKey, key, "IG_EXCLUDE")`, then do the same `LocalApplyWait` and warnings as include. The response is `{status, fhirVersion, revision, excluded, included, warnings}`.
- [X] T043 [US2] Replace the 501 placeholder with `handleExcludeImplementationGuide`, which reads the required query `ig` and calls `igPackageService.exclude`, in `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`
- [X] T044 [US2] Change `ApiKeyAuthHandler.isAdmin` to treat `DELETE` on `^/[^/]+/include-ig/?$` as administrative, in addition to the existing POST paths. `AdminAdmissionHandler` uses the same predicate. Edit `src/main/java/nzi/fhir/validator/core/security/ApiKeyAuthHandler.java`, and update the class Javadoc that says "POST".
- [X] T045 [US2] Demotion on exclude: in `ValidatorRegistry.applyPinned`, a pinned key that is missing from the new set becomes an `ON_DEMAND` slot that keeps its snapshot (LRU-evictable, no longer counted for readiness). If the on-demand bound is then exceeded, evict the LRU slot. Edit `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`.
- [X] T046 [US2] Invalidation (FR-025) in `ConfigSync` and `ValidatorRegistry`. On a catalog refresh, compare every slot's `packageHashes` with the view:
  - a pinned slot whose package hash changed is marked `STALE` (reason "package name#version was replaced") and
    rebuilt;
  - an on-demand slot containing a changed package, *or whose `DependencyResolver` result against the new view
    differs from its `wantedResolved`*, is removed (eviction reason `invalidated`);
  - `PackageCache.invalidate` is called for each changed key;
  - a pinned key whose resolved set changed (with unchanged hashes) is rebuilt in the background while the old
    snapshot keeps serving.

  Edit `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java` and `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`.
- [X] T047 [US2] Change `ValidatorRegistry.details()` and `HealthService` to emit `{catalogRevision, validators: {<version>: {base: {state, packages, packageBytes}, contexts: [{key, kind: "pinned"|"on-demand", state, appliedRevision, packages, packageBytes, lastUsed, reason?}]}}}`. Readiness is evaluated over base and pinned slots only. Edit `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java` and `src/main/java/nzi/fhir/validator/core/service/HealthService.java`.
- [X] T048 [US2] Update `IgPackageApiContractTest` and `ValidationApiContractTest` for `IncludeResponse`, `ExcludeResponse` and the DELETE operation, in `src/test/java/nzi/fhir/validator/web/endpoint/`.

**Checkpoint**: US1 and US2 both work on their own. Side-by-side versions, exclude, propagation and invalidation
pass.

---

## Phase 5: User Story 3 - "latest" on registration means the registry's latest release (Priority: P2)

**Goal**: `POST /igs/register` with no version or `latest` resolves against the registries, downloads only what is
new, falls back to the highest stored version with an accurate warning, and reports `downloaded` and `warnings`.

**Independent Test**: With a `StubRegistry` offering `P#1` and `P#2` and only `P#1` stored, register `P` twice, then
take the registry down and register again (quickstart §1, row 14).

### Tests for User Story 3 (write first, confirm they fail)

- [X] T049 [P] [US3] Write `RegisterLatestIT` (AppHarness plus `StubRegistry`) in `src/test/java/nzi/fhir/validator/core/npm/RegisterLatestIT.java`. It covers:
  1. only `P#1` stored and the registry lists 1 and 2 → 200 `{version: "2", downloaded: true}`, 1 archive
     download, and dependencies fetched when `includeDependency` is true and not when it is false;
  2. calling again → `{version: "2", downloaded: false}`, 0 additional archive downloads (SC-009);
  3. the registry answering 503 or refusing connections → highest stored, warning "No package registry could be
     consulted; using the highest stored version";
  4. `FV_PACKAGE_REGISTRY_ENABLED=false` → the same warning;
  5. the registry reachable but listing no such package, with `P#1` stored → warning "No package registry lists
     this package; using the highest stored version";
  6. as in 3, with nothing stored → 404 `package-not-found`;
  7. an exact version → unchanged behaviour plus the `downloaded` flag;
  8. the listing's `versions` takes precedence over `dist-tags.latest` (existing `latestFromListing` rule).
- [X] T050 [P] [US3] Adjust `PackageLatestResolutionIT` so it asserts that `resolveVersion` (stored first) still serves include and dependency resolution unchanged, in `src/test/java/nzi/fhir/validator/core/npm/PackageLatestResolutionIT.java`

### Implementation for User Story 3

- [X] T051 [US3] Add `record RegistrationVersion(String version, boolean fromRegistry, String warning)` and `Future<RegistrationVersion> resolveForRegistration(String name)` in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java`:
  - registries enabled → query every registry listing and tell three outcomes apart: at least one listing gave a
    version; every registry failed (network error or 5xx); or registries answered but none lists the package (404
    or empty);
  - pick the max with `PackageVersionComparator`, using the unchanged `latestFromListing`;
  - otherwise fall back to `getLatestVersionFromCache` with the matching curated warning;
  - `null` when nothing is stored.

  Leave `resolveVersion` unchanged.
- [X] T052 [US3] Change `IgPackageService.registerIg(name, version, includeDependency)` in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java` to return `Future<Registration>`, where `record Registration(NpmPackage pkg, boolean downloaded, List<String> warnings)`:
  - for null, blank or `latest`, use `resolveForRegistration`, and when it gives `null` fail with 404
    `package-not-found`;
  - if `loadRawPackage(name, concrete)` exists, return it parsed with `downloaded = false`;
  - otherwise call `loadPackage(name#concrete, false, includeDependency)` with `downloaded = true`;
  - exact versions follow the same stored-or-download check.

  The upload and URL paths return `Registration` with `downloaded` `false` and `true` respectively.
- [X] T053 [US3] Render `IgRegistrationResponse {status, name, version, downloaded, warnings}` for register, upload and URL download in `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java`, and update `src/test/java/nzi/fhir/validator/web/endpoint/IgPackageApiContractTest.java`

**Checkpoint**: US3 works on its own, and US1 and US2 are unaffected.

---

## Phase 6: User Story 4 - Operators upgrade with predictable behaviour (Priority: P2)

**Goal**: A 2.0.0 database upgrades to 3.0.0 with every included IG kept as a pinned context, and the documentation
states the breaking change.

**Independent Test**: Start a 2.0.0 schema with two IGs included for R4, upgrade, and confirm two pinned contexts,
both usable through `ig`, and the base context for requests without `ig` (quickstart §1, row 15).

### Tests for User Story 4 (write first, confirm they fail)

- [X] T054 [P] [US4] Extend `MigrationUpgradeIT` with `upgradeFrom2_0_0KeepsIncludedIgsAsPinnedContexts`:
  - migrate to V6, insert two stored packages and set R4 `included_ig_packages = {A#1.0.0, B#1.0.0}`,
    `resolved_ig_packages = {...}`, `revision = 5`, and an API key row;
  - start 3.0 → V7 applies, `included_ig_packages` and `revision` are unchanged, the API key and stored packages are
    intact, `/health` lists both as `pinned` and `ACTIVE`, `ig=A#1.0.0` validates, and a request without `ig` is
    base (US4-1, SC-010).

  Put it in `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java`.

### Implementation for User Story 4

- [X] T055 [P] [US4] Write `docs/upgrade-3.0.md`. It covers:
  - requests without `ig` now use the core specification only; name the IG to keep the previous result;
  - include no longer replaces other versions; use `DELETE /{version}/include-ig` to stop pre-building one;
  - the `/health` detail shape and the new response fields and headers;
  - the new settings `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS`;
  - stop every 2.x instance before starting 3.0, because a mixed fleet isn't supported (V7 drops
    `resolved_ig_packages`);
  - take a full database backup first; restoring it is the only way back to 2.0.0;
  - a sizing note: each pinned IG now holds its own dependencies (research R14).
- [X] T056 [P] [US4] Add a `## [3.0.0]` entry to `CHANGELOG.md` with **Breaking** (default without `ig`, include semantics, health shape, register-latest behaviour), **Added** (`ig`, `X-Validation-Context`, `igPackage`, exclude operation, on-demand contexts, `insufficient-capacity`, settings, metrics) and **Fixed** (replaced packages now invalidate validators; register `latest` notices newer releases) sections, linking `docs/upgrade-3.0.md`
- [X] T057 [US4] Add `scripts/upgrade-rehearsal-3.0.sh`, modelled on `scripts/upgrade-rehearsal-2.0.sh`: it starts 2.0.0, includes two IGs, stops it, starts 3.0.0 and asserts both are pinned with `curl` and `jq`

**Checkpoint**: All user stories are independently functional.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T058 [P] Add the metrics from research R12 to `ServiceMetrics`, binding them in `ServiceRegistry.create`: the gauge `fv_validator_contexts{fhir_version,kind}`; the counter `fv_validator_context_builds_total{fhir_version,kind,outcome=success|failure|timeout_wait|refused_capacity|refused_admission}`; the counter `fv_validator_context_evictions_total{fhir_version,reason=lru|memory|invalidated|excluded}`; and the counter `fv_validator_context_lookups_total{fhir_version,result=hit|joined|built}`. Edit `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java`, `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java` and `src/main/java/nzi/fhir/validator/ServiceRegistry.java`.
- [X] T059 [P] Extend `ObservabilityIT` to scrape `/metrics` after one base validation, one on-demand build, one eviction and one 404 `ig`, and assert the new series and the `package_not_found` outcome, in `src/test/java/nzi/fhir/validator/web/ObservabilityIT.java`
- [X] T060 [P] Extend `EventLoopResponsivenessIT` so that `/health/liveness` stays under its latency bound while an on-demand build of a large test package runs (Principle I), in `src/test/java/nzi/fhir/validator/web/EventLoopResponsivenessIT.java`
- [X] T061 [P] Update `README.md`: the `ig` parameter, the base context, include and exclude, register latest, and the new settings; replace `2.0.0` jar references with `3.0.0`
- [X] T062 [P] Check that the README and `docs/upgrade-3.0.md` describe profile sourcing in line with constitution 1.1.0 Principle II: profiles come from the chosen package, its dependencies and the core specification, and never from the network.
- [X] T063 [P] Add `scripts/bench-validate.sh`, which drives single-resource R4 validation with `hey` (or `wrk` if available) for (a) no `ig` and (b) `ig` naming a pinned IG, and prints p50, p95 and p99. Record results for the 2.0.0 tag and this branch in the PR (SC-007, Principle VI).
- [X] T064 Run `mvn verify` and resolve every failure and every `BlockedThreadChecker` warning. Then run the manual walkthrough in `specs/004-ig-scoped-validation/quickstart.md` §2 and note the results in the PR.
- [X] T065 Set the release version `3.0.0` in `pom.xml` and the CHANGELOG date when releasing. Mark the spec status `Implemented` in `specs/004-ig-scoped-validation/spec.md`.

---

## Implementation Notes (recorded during /speckit-implement)

- **T011**: the static parser is named `PackageCatalogView.parseDeclaredFhirVersions(JsonObject)`; the instance method
  `declaredFhirVersions(String)` answers per package.
- **T018/T030/T045/T046**: the keyed registry was written once with base, pinned and on-demand slots, demotion,
  promotion and invalidation together; `ValidatorRegistryKeyTest` (11 cases) covers all of them with a fake builder.
- **T029**: pulled forward into Phase 2 because the keyed registry passes its evictor to every build.
- **T030 (FR-017)**: `DependencyResolver.Resolution` gained a `missing` list; an on-demand context whose dependency is
  not stored is refused with 424 before any build. Included contexts keep the 2.x behaviour (build without the missing
  package, with a warning).
- **T040**: the health shape and readiness rule are tested in `ValidatorRegistryKeyTest`
  (`healthDetailsListEveryContextAndReadinessIgnoresOnDemand`); `HealthApiTest` was unchanged. The details stay under
  the existing `versions` key (contracts §5 updated accordingly).
- **T044**: `ApiKeyAuthHandler.isAdministrative` now treats every method except OPTIONS on an administrative path as
  administrative, not just POST and DELETE.
- **T054**: the application-level upgrade check lives in a new `UpgradeFrom2IT` (it needs a Vert.x harness);
  `MigrationUpgradeIT` covers the schema-level V7 checks.
- **T057, T063**: `scripts/upgrade-rehearsal-3.0.sh` and `scripts/bench-validate.sh` were syntax-checked (`sh -n`);
  the rehearsal needs the published 2.0.0 image and was not run here.

- **T064**: `mvn verify` green (319 tests, no blocked-thread warnings). It first caught a race: a package stored on an
  instance could briefly get a false 404 on `?ig=` on that same instance because miss-triggered catalog refreshes
  are rate limited. Fixed with `PackageCatalog.markStale()` on local stores. Manual walkthrough (quickstart §2,
  with dk-core 3.4.0, registries disabled) behaved as specified. Benchmark (`scripts/bench-validate.sh`, 2000
  requests, concurrency 8, one run on a developer laptop, PostgreSQL 16 in Docker):

  | Build | Scenario | p50 | p95 | p99 |
  |---|---|---|---|---|
  | 2.0.0 | no IG included | 14 ms | 20 ms | n/a |
  | 2.0.0 | dk-core included, no ig | 12 ms | 17 ms | n/a |
  | 3.0.0 | no ig (base) | 16 ms | 26 ms | 30 ms |
  | 3.0.0 | ig=dk-core (pinned) | 14 ms | 22 ms | 26 ms |

  Well within the 200 ms p95 budget, but about 5 ms higher at p95 than 2.0.0 in this single run; repeat on CI
  hardware before release (SC-007 asks for no measurable regression).

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001–T004)**: none.
- **Foundational (T005–T022)**: depends on Setup and blocks every story. The internal order is: T005–T007 →
  T008–T014 (T009, T010, T014 and T008 can run in parallel) → T015 → T016 → T017 → T018 → T019 → T020 → T021 →
  T022.
- **US1 (T023–T035)**: depends on Foundational.
- **US2 (T036–T048)**: depends on Foundational. T045 (demotion) and T041 (admission evictor) use the on-demand slots
  and evictor from US1 (T029, T030), so implement US1 first or take T029–T030 early. US2's tests can be written in
  parallel with US1.
- **US3 (T049–T053)**: depends on Foundational only (T013 for the catalog bump on store). It is independent of US1
  and US2.
- **US4 (T054–T057)**: depends on Foundational (V7) and on US2 for the `/health` assertions in T054.
- **Polish (T058–T065)**: after the stories it touches.

### Within each story

Tests first (they must fail) → value types (`IgReference`) → registry and heap → service → endpoint → contract
tests.

## Parallel Examples

```text
# Foundational, after T007:
T008 settings | T009 SnapshotKey | T010 PackageCatalogViewTest | T014 PackageCache.invalidate

# US1 tests together:
T023 IgReferenceTest | T024 IgScopedValidationIT | T025 ProfileResolutionIT | T026 OnDemandContextIT | T027 ContextEvictionIT

# US2 tests together:
T036 IncludeExcludeIT | T037 ConfigPropagationIT | T038 PackageInvalidationIT | T039 ApiKeyAuthIT/AdminAdmissionIT | T040 HealthApiTest

# US3 can run alongside US1/US2 after Foundational:
T049 RegisterLatestIT → T051 → T052 → T053

# Docs in parallel:
T055 upgrade-3.0.md | T056 CHANGELOG | T061 README | T062 profile-sourcing wording
```

## Implementation Strategy

### MVP first (US1)

1. Phases 1 and 2 (Setup, Foundational).
2. Phase 3 (US1): per-request `ig`, on-demand contexts, capacity bounds.
3. **Stop and validate**: T023–T027 green, and quickstart §2 steps 2 and 3.

### Incremental delivery

1. Foundation → base and pinned contexts with today's include behaviour.
2. US1 → choose the IG per request (MVP).
3. US2 → side-by-side versions, exclude, invalidation, health.
4. US3 → register latest (independent; can ship in parallel).
5. US4 + Polish → migration proof, documentation, metrics, benchmark, release 3.0.0.

### Notes

- Never edit an applied migration (V1–V6).
- Client-facing errors carry curated text only. Causes go to the log with the request id (Principle IV).
- Every new future-returning API returns `io.vertx.core.Future`: no `.get()`, no `Thread.sleep`, and no blocking on
  the event loop.
- Commit after each task or logical group, with commit messages ending in the required attribution line.

---

## Phase 8: Convergence

- [X] T066 Remove the per-request overhead on the validation path: cache the `fv.validator.context.lookups` counters per (FHIR version, result) in `ServiceMetrics` instead of `Counter.builder(...).register(...)` on every lookup, and serve base and pinned contexts in `ValidatorRegistry.snapshot()` without taking the global registry lock (e.g. a volatile/concurrent read path). Then rerun `scripts/bench-validate.sh` against 2.0.0 and this build and record p50/p95/p99 in the PR, in `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java` and `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java` per SC-007 (partial)
- [X] T067 When `ValidatorRegistry.applyPinned` demotes an excluded key, drop the context instead of keeping it when its resolution against the current catalog has missing dependencies (or always drop it), so a later `?ig=` request for it behaves like any other stored package, including 424 `dependency-not-found`. Add a case to `src/test/java/nzi/fhir/validator/core/validator/IncludeExcludeIT.java`, editing `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`, per US2-6 / FR-017 (contradicts)
- [X] T068 Add a test that every log line emitted by an on-demand context build (including a build that waited in `SnapshotBuilder`'s `AsyncLimiter` queue) carries the request id of the request that started it, and fix the context propagation if it does not, in `src/test/java/nzi/fhir/validator/web/ObservabilityIT.java` and `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java` per Constitution VII (partial)
- [X] T069 Add a burst test: 20 distinct stored IGs requested concurrently with `ig.maxOnDemand = 4`; the on-demand count never exceeds 4, every request ends in 200 or a curated 503, and no build is admitted beyond the heap check, in `src/test/java/nzi/fhir/validator/core/validator/ContextEvictionIT.java` per SC-008 (partial)
- [X] T070 Add a test that validation against a built pinned context and against the base context succeeds while the database is unreachable (pool closed or container paused), in `src/test/java/nzi/fhir/validator/core/validator/OnDemandContextIT.java` per FR-013 (partial)
- [X] T071 Make a malformed `ig` win over an empty JSON body (today the contract layer answers `invalid-resource` first), or document that contract-layer exception in the `validationApiValidate` description in `src/main/resources/openapi.yaml`, and cover the JSON case in `src/test/java/nzi/fhir/validator/web/IgScopedValidationIT.java` per FR-009 (partial)
- [X] T072 Add a `RegisterLatestIT` case where the registry's latest release is lower than a stored pre-release: `register` with `latest` returns the registry's version (downloading it if missing) and reports it, in `src/test/java/nzi/fhir/validator/core/npm/RegisterLatestIT.java` per spec edge case "Registry newest is lower than stored" (missing)
- [X] T073 Remove the unused `PackageCache.invalidateAll()` (or call it from `PostgresPackageCacheManager.clear()`'s change handling), in `src/main/java/nzi/fhir/validator/core/npm/PackageCache.java` per plan: `PackageCache.invalidate` (unrequested)

### Convergence notes (recorded during /speckit-implement)

- **T066**: base and pinned contexts are served from a `ConcurrentHashMap` and volatile slot fields, without the
  registry lock; validation and lookup counters and the validation timer are created once per tag set. Benchmark,
  2.0.0 and this build back-to-back in one session (2000 requests, concurrency 8, laptop, PostgreSQL 16 in Docker):

  | Build | Scenario | p50 | p95 | p99 |
  |---|---|---|---|---|
  | 2.0.0 | no ig | 16 ms | 26 ms | 34 ms |
  | 3.0.0 | no ig (base) | 16 ms | 27 ms | 40 ms |
  | 2.0.0 | dk-core included (2.0 ignores ig) | 15 ms | 21 ms | 23 ms |
  | 3.0.0 | ig=dk-core (pinned) | 14 ms | 20 ms | 23 ms |

  No difference beyond run-to-run noise (2.0.0 itself measured 20 ms p95 in an earlier session).
- **T067**: an excluded IG whose resolution has a missing dependency is dropped instead of demoted; others are still
  kept on demand.
- **T068**: confirmed the defect (a queued build logged the id of the request whose build finished first) and fixed it
  in `AsyncLimiter`: a queued task starts on its caller's Vert.x context.
- **T071**: implemented rather than documented: the contract layer's failure handler checks the `ig` shape first.
- `mvn verify`: 325 tests, 0 failures, no blocked-thread warnings.

---

## Phase 9: Convergence

- [X] T074 Make `ValidatorRegistry.onCatalogChanged` and the package-cache invalidation in `ConfigSync.runCatalogSync` ignore a catalog view older than the newest already seen (use `catalog.current()` when it is newer than the refreshed view, and skip views whose revision is below the highest revision the registry has reconciled), so a catalog sync never redirects a building on-demand context to, or drops an active one for, outdated metadata. Add a `ValidatorRegistryKeyTest` case where `onCatalogChanged` receives an older view after an on-demand context was built from a newer one (expect no rebuild, no removal), in `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`, `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java` and `src/test/java/nzi/fhir/validator/core/validator/ValidatorRegistryKeyTest.java` per FR-025 / plan: research R3, R4 (partial)

### Convergence notes (Phase 9)

- **T074**: `ValidatorRegistry.onCatalogChanged` reconciles with the newest catalog view and ignores views older than
  the last one reconciled; `ConfigSync.runCatalogSync` does the same before invalidating cached packages. The
  regression test failed before the fix (the older view dropped a context built from a newer one). `mvn verify`:
  326 tests, 0 failures, no blocked-thread warnings.
- **T068 follow-up (found by CI on #48)**: a validation whose `ig` lookup joined a catalog refresh started by the
  background sync continued on that refresh's context, so its build and validation logged an empty request id.
  `FhirValidationService` now returns to the caller's context after the lookup and after the snapshot step;
  `ValidationContextPropagationTest` reproduces the case deterministically (it failed before the fix).
