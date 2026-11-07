---

description: "Task list for 006-ig-version-series"
---

# Tasks: IG Version Series (`1.2.x`, `1.x.x`) on Register and Include

**Input**: Design documents from `specs/006-ig-version-series/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R9), data-model.md, contracts/http-api.md, quickstart.md

**Tests**: Included. Constitution V requires test-first with real PostgreSQL (Testcontainers) for persistence and
IG-loading code, plus a contract test for every endpoint change. Write each test task first and confirm it fails
before the matching implementation task.

**Organization**: Tasks are grouped by user story (spec.md US1–US4) so each story can be built and tested on its
own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: The user story the task belongs to (US1–US4)

## Path Conventions

- Production code: `src/main/java/nzi/fhir/validator/`
- Tests: `src/test/java/nzi/fhir/validator/`
- Contract: `src/main/resources/openapi.yaml`
- Tests run under surefire, `*IT` classes included: `mvn -q test -Dtest=<Class>`

---

## Phase 1: Setup

**Purpose**: Confirm a green baseline. There are no new dependencies, modules or migrations.

- [X] T001 Create the feature branch `006-ig-version-series` from `main`. Run `mvn -q test -Dtest='PackageVersionComparatorTest,IgReferenceTest,RegisterLatestIT,IncludeExcludeIT,IncludeOwnershipIT,IgPackageApiContractTest,ValidationApiContractTest'` and record that everything is green before any change (baseline for FR-016/SC-006)

---

## Phase 2: Foundational (blocking prerequisites)

**Purpose**: The pure `VersionSeries` value class (research R1, R2; data-model §1). US1, US2 and US4 all use it.

**⚠️ CRITICAL**: No user story work can start until this phase is complete.

### Tests for Foundational ⚠️

- [X] T002 [P] Create a failing unit test `src/test/java/nzi/fhir/validator/core/npm/VersionSeriesTest.java` (JUnit 5, no I/O). Cover:
  - **`isSeries`**: true for `1.2.x`, `1.2.X`, `1.x`, `1.x.x`, `1.x.3`, `x.x.x`, `x`. False for `1.2.3`, `1.2`, `latest`, `current`, `1.2.3-ballot`, `1.2.x-ballot`. A part must equal `x` or `X` exactly.
  - **`parse`** accepts `1.x`, `1.x.x`, `1.2.x`, `1.2.X`, matching the grammar `^\d+\.[xX](\.[xX])?$|^\d+\.\d+\.[xX]$`.
  - **`parse`** throws `ProblemException` with status 400, type `invalid-request` and the exact message `Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)` for `x`, `x.x.x`, `1.x.3`, `1.2.x.y`, `1.2.3.x`, `1.2.x.x`.
  - **`matches`** for `1.2.x`: true for `1.2`, `1.2.0`, `1.2.7`, `1.2.7-ballot`, `1.2.7+build`. False for `1.20.0`, `1.21.3`, `1.3.0`, `1.1.9`, `current`, `2020Sep`.
  - **`matches`** for `1.x`: true for `1.0.0`, `1.9.9` and `1`. False for `2.0.0`.
  - **Missing parts count as `0`** (spec Assumptions): for `1.2.x`, `1` (that is, `1.0.0`) is false, and `1.2` (that is, `1.2.0`) is true.
  - **`highest`**, using the examples table in data-model.md §1:
    - `1.2.x` over [`1.2.0`, `1.2.5`, `1.20.1`] → `1.2.5`
    - `1.x` over [`1.2.3`, `1.3.0`, `2.0.0`] → `1.3.0`
    - `1.2.x` over [`1.2`, `1.1.9`] → `1.2`
    - `1.2.x` over [`1.2.0`, `1.2.1-ballot`] → `1.2.0`
    - `1.2.x` over [`1.2.1-ballot`] → `1.2.1-ballot`
    - `1.2.x` over [`current`, `1.3.0`] → empty
  - **Determinism**: `highest` gives the same result for the same candidates in shuffled order (FR-005).
  - **`raw()`** returns the input unchanged (for example `1.2.X`).
- [X] T003 [P] Add failing tests to `src/test/java/nzi/fhir/validator/core/npm/PackageVersionComparatorTest.java` for the new `releaseParts(String)`:
  - `1.2` → `[1,2,0]`
  - `1.2.7-ballot` → `[1,2,7]`
  - `1.2.7+b` → `[1,2,7]`
  - `current`, `null`, `1.2.x` → empty

### Implementation for Foundational

- [X] T004 Add `public static Optional<BigInteger[]> releaseParts(String version)` to `src/main/java/nzi/fhir/validator/core/npm/PackageVersionComparator.java`. Reuse the existing private `parse` (the `SEMVER` pattern; missing parts are `0`; build metadata is ignored) and return a copy of `core`, or empty when `parse` returns `null`. Don't change `compare`, `max` or `isRelease`. Makes T003 pass.
- [X] T005 Create `src/main/java/nzi/fhir/validator/core/npm/VersionSeries.java` as a `public final` class (or record) with `raw` (String) and `fixed` (`BigInteger[]`, length 1 or 2). It needs:
  - **`static boolean isSeries(String v)`**: null-safe; true when any part of `v.split("\\.", -1)` equals `x`/`X`, case-insensitively.
  - **`static VersionSeries parse(String v)`**: validates against `^\d+\.[xX](\.[xX])?$|^\d+\.\d+\.[xX]$`. Otherwise throws `new ProblemException(400, "invalid-request", "Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)")`. `fixed` is the numeric leading parts.
  - **`boolean matches(String version)`**: `PackageVersionComparator.releaseParts(version)` is present and `parts[i].equals(fixed[i])` for every `i < fixed.length`.
  - **`Optional<String> highest(Collection<String> candidates)`**: `PackageVersionComparator.max(candidates.stream().filter(this::matches).toList())`.
  - **`String raw()`**.
  - **Javadoc**: cite spec 006 FR-001 to FR-005.

  Makes T002 pass.

**Checkpoint**: `mvn -q test -Dtest='VersionSeriesTest,PackageVersionComparatorTest'` is green. User stories can start.

---

## Phase 3: User Story 1 — Register the newest release of a version series (Priority: P1) 🎯 MVP

**Goal**: `POST /igs/register` with `version: "1.2.x"` resolves against the registry listings, then stores or
returns the concrete version (FR-006 to FR-010; research R3, R4, R7).

**Independent Test**: The registry stub lists `1.2.0`, `1.2.3` and `1.3.0`. Registering `1.2.x` stores and reports
`1.2.3` with `requestedVersion` `1.2.x`.

### Tests for User Story 1 ⚠️

- [X] T006 [P] [US1] Create a failing integration test `src/test/java/nzi/fhir/validator/core/npm/RegisterSeriesIT.java`, modelled on `RegisterLatestIT` (extends `BaseTestContainer`, `VertxExtension`, `@Timeout(120 s)`, `StubRegistry` + `AppHarness`, the same `start(vertx, registryEnabled)` / `register(body)` / `storeLocally` / `stored` helpers).

  Registry setup:
  - `ser.p` versions `1.2.0`, `1.2.3`, `1.3.0`, `1.20.1`, `2.0.0-ballot`;
  - `ser.pre` version `1.2.1-ballot` only;
  - `ser.dep` `1.0.0`, with `ser.p#1.2.3` depending on `ser.dep#1.0.0`.

  Tests:
  - (a) `1.2.x` → 200, `version` `1.2.3`, `requestedVersion` `1.2.x`, `downloaded` true, and `ser.dep#1.0.0` stored. A second call gives `downloaded` false, and `registry.archiveDownloads()` is unchanged.
  - (b) `1.x.x` and `1.x` → `1.3.0`.
  - (c) `1.2.X` → `1.2.3`.
  - (d) `ser.pre` with `1.2.x` → `1.2.1-ballot`.
  - (e) `includeDependency:false` → `ser.dep` not stored.
  - (f) `registryEnabled=false` with `ser.p#1.2.4` stored locally → 200, `version` `1.2.4`, `downloaded` false, `warnings` = [`No package registry could be consulted; using the highest stored version in series 1.2.x`].
  - (g) Registry `Mode.UNAVAILABLE` → the same warning as (f).
  - (h) Registry lists `ser.p`, but series `1.4.x` with `ser.p#1.4.2` stored locally → `1.4.2` with the warning `No package registry lists a version in series 1.4.x; using the highest stored version`.
  - (i) Series `3.x` with nothing listed or stored → 404 `package-not-found`, with a detail containing `ser.p` and `3.x` (use `ContractAssertions.assertProblem`).
  - (j) `1.20.x` never yields a `1.2.*` version, and `1.2.x` never yields `1.20.1`.
  - (k) Exact `1.2.0` and `latest` behave as in `RegisterLatestIT` (no `requestedVersion` regression: it's `1.2.0` and `latest`, respectively).
  - (l) **Two registries, one failing** (spec Edge Cases). Start a second `StubRegistry` in `Mode.UNAVAILABLE` and set `packages.sources` to both URLs, comma-separated (`ApplicationConfig.splitList`). Registering `ser.p` `1.2.x` → `1.2.3` from the answering registry, `warnings` empty.
  - (m) **The same version in two registries** (spec Edge Cases). Two `StubRegistry` instances in `Mode.NORMAL` both list `ser.p#1.2.3`, and only the second lists `ser.p#1.2.4`. Registering `1.2.x` → `1.2.4` (the highest across every registry that answered), downloaded once, with `archiveDownloads()` summed over both stubs equal to 1.
- [X] T007 [P] [US1] Add failing contract tests to `src/test/java/nzi/fhir/validator/web/endpoint/IgPackageApiContractTest.java`:
  - a 200 `IgRegistrationResponse` for a series validates against `openapi.yaml`, including the new `requestedVersion` string;
  - `version: "1.x.3"` gives a 400 problem-details body, type `invalid-request`.

### Implementation for User Story 1

- [X] T008 [US1] Refactor `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java` (research R3). Extract the per-registry loop in `resolveForRegistration` into a private `Future<List<Listing>> fetchListings(String name)`.
  - Change `Listing` to `record Listing(ListingOutcome outcome, List<String> versions)`, filled from `versions.fieldNames()`, or `[dist-tags.latest]` when the listing has no `versions` map. That's a new private helper, `versionsFromListing(JsonObject)`.
  - Keep the 404 → `NOT_LISTED` / other error → `FAILED` classification and the info log.
  - Rebuild `resolveForRegistration(name)` on `fetchListings`, with results identical to today: the per-listing max via `PackageVersionComparator.max`, then the max across listings, and the same `NO_REGISTRY_WARNING` / `NOT_LISTED_WARNING` fallback.

  `RegisterLatestIT` must stay green.
- [X] T009 [US1] In `PostgresPackageCacheManager.java`, add `public record SeriesRegistryResult(String version, String warning)` and `public Future<SeriesRegistryResult> resolveSeriesFromRegistries(String name, VersionSeries series)`:
  - Registries disabled or `registryUrls` empty → `(null, SERIES_NO_REGISTRY_WARNING)`.
  - Otherwise, pool every version from listings with outcome `LISTED` and take `series.highest(pooled)`:
    - present → `(v, null)`;
    - empty and any registry answered (outcome other than `FAILED`) → `(null, SERIES_NOT_LISTED_WARNING)`;
    - else → `(null, SERIES_NO_REGISTRY_WARNING)`.
  - Add the constants as `String.format` templates:
    - `SERIES_NO_REGISTRY_WARNING = "No package registry could be consulted; using the highest stored version in series %s"`
    - `SERIES_NOT_LISTED_WARNING = "No package registry lists a version in series %s; using the highest stored version"`

    Format them with `series.raw()`.
- [X] T010 [US1] In `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`, add `static Future<Map<String, Access>> storedVersionsWithAccess(SqlClient client, String name)` (research R4). It's the same SQL as `packageAccess`, `SELECT g.ig_package_version, g.owner, o.name_short, g.is_shareable … JOIN organisations … WHERE g.ig_package_id = $1`, with no version predicate, mapped to `Access`.
- [X] T011 [US1] In `IgPackageService.register(Caller, String name, String version, boolean includeDependency, boolean shareable)`, add the series branch before the existing `latest` handling (FR-006 to FR-009):
  - If `VersionSeries.isSeries(version)`, call `VersionSeries.parse(version)`. A thrown `ProblemException` returns as a failed future.
  - Then `cacheManager.resolveSeriesFromRegistries(name, series)`.
  - When the result's `version` is null, call `storedVersionsWithAccess(pgPool, name)`, filter the entries with `AccessPolicy.canUse(caller, access)`, and take `series.highest(keys)`. Present → `RegistrationVersion(v, false, result.warning())`. Empty → fail with `new ProblemException(404, ProblemException.TYPE_PACKAGE_NOT_FOUND, "IG package " + name + " has no version in series " + series.raw() + " in the configured package registries and none is stored")`.
  - When `version` is non-null → `RegistrationVersion(version, true, null)`.
  - Feed the result into the existing `chosen.compose(rv -> …)` chain unchanged: the stored-but-not-usable 404, the stored → returned path, and the `loadPackage(key, false, includeDependency, ownership)` path.
  - Make sure a series never reaches `loadPackage` as a literal label.
  - Include the requested `version` in the existing success and failure log lines.
- [X] T012 [US1] In `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java` `handleRegisterIg`:
  - Keep the existing `isValidVersionLabel` check. A series passes it, and the grammar check happens in the service.
  - Add `requestedVersion` to the JSON built by `registered(...)`. Add a `String requestedVersion` parameter to the private `registered(NpmPackage, boolean, List<String>, Access)` and pass `version` (default `latest`) from the name path.
  - Leave the `downloadUrl` and upload paths without `requestedVersion`. They have no requested version.

  FR-010, research R7.
- [X] T013 [US1] Update `src/main/resources/openapi.yaml` for `/igs/register`, per contracts/http-api.md §1 and §4:
  - the `version` property description, with `maxLength: 64`;
  - the description paragraph "Version series (3.2.0): …";
  - the `requestedVersion` property on `IgRegistrationResponse`, described as "The version value of the request (latest, an exact version or a version series) (3.2.0)".

**Checkpoint**: `mvn -q test -Dtest='RegisterSeriesIT,RegisterLatestIT,IgPackageApiContractTest'` is green. US1 works on its own.

---

## Phase 4: User Story 2 — Include the highest stored release of a version series (Priority: P1)

**Goal**: `POST /{version}/include-ig` with `igPackageVersion: "1.2.x"` includes the highest matching stored version
the caller may use, without contacting a registry (FR-011 to FR-015; research R5, R7).

**Independent Test**: Store `1.2.0`, `1.2.5` and `1.20.1`. Including `1.2.x` on R4 pins `key.igPackage` to
`name#1.2.5`.

### Tests for User Story 2 ⚠️

- [X] T014 [P] [US2] Create a failing integration test `src/test/java/nzi/fhir/validator/core/validator/IncludeSeriesIT.java`, modelled on `IncludeExcludeIT`. Use `TestPackages.igPackage(name, version, List.of("4.0.1"), Map.of(), …)` and `TestPackages.storePackage`.

  Tests:
  - (a) Stored `inc.p#1.2.0`, `#1.2.5`, `#1.20.1`; include R4 `1.2.x` → 200, `key.igPackage` `inc.p#1.2.5`, `requestedVersion` `1.2.x`, and the snapshot for `inc.p#1.2.5` becomes ACTIVE.
  - (b) Only `inc.pre#1.2.1-ballot` stored → it's included.
  - (c) `2.x.x` with no `2.*` stored → 404 `package-not-found`, with a detail containing `inc.p#2.x.x` and `Register it first`.
  - (d) After (a), store `inc.p#1.2.6` and include `1.2.x` again → `inc.p#1.2.6` is added as a second snapshot, and `inc.p#1.2.5` stays ACTIVE and isn't rebuilt (FR-014).
  - (e) Highest match `inc.r5#1.2.3` declares only FHIR `5.0.0`, and `inc.r5#1.2.1` declares `4.0.1`; include R4 `1.2.x` → 400 `invalid-request`, the same as including `1.2.3` exactly. `1.2.1` is NOT chosen (FR-015).
  - (f) The service is started with a `StubRegistry` as source, and include `1.2.x` makes zero requests to it: `registry.requests()` is unchanged (SC-004).
  - (g) Exact `1.2.0` and `latest` are unchanged.
- [X] T015 [P] [US2] Add failing contract tests to `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java`:
  - a 200 `IncludeResponse` for a series validates against `openapi.yaml`, including `requestedVersion`;
  - `igPackageVersion: "1.x.3"` gives a 400 `invalid-request` problem body.

### Implementation for User Story 2

- [X] T016 [US2] In `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java` `include(Caller, SupportedFhirVersion, String name, String requestedVersion, Optional<Boolean> shareable)`:
  - Before the existing `isValidVersionLabel` check, if `VersionSeries.isSeries(requestedVersion)`, parse it. A malformed series returns a failed future with the 400 from `VersionSeries.parse`.
  - Inside the transaction, where `concrete` is computed from `visible` (already filtered by `AccessPolicy.canUse`): when a series was given, `concrete = series.highest(visible)`.
  - The 404 label for an empty result is `name + "#" + series.raw()`. Keep the existing message: "IG package {label} is not stored. Register it first via /igs/upload or /igs/register".
  - Leave everything after `concrete` unchanged: `checkFhirVersion`, `lockForUpdate`, `DependencyResolver.resolve`, the access checks on dependencies, heap admission, revision and propagation (FR-013, FR-015).
- [X] T017 [US2] In `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java` `handleIncludeImplementationGuide` (around the `body.getString("igPackageVersion", "latest")` call):
  - Capture the requested value.
  - `.map(json -> json.put("requestedVersion", requested))` on the include result before it's sent, so every include response path (newly included, already included, still applying) carries it (research R7).
- [X] T018 [US2] Update `src/main/resources/openapi.yaml` for `/{version}/include-ig`, per contracts/http-api.md §2 and §4:
  - the `igPackageVersion` description (series → highest matching stored version your organisation may use; no registry; pinned to the concrete version);
  - append a sentence about series to the operation description;
  - the `requestedVersion` property on `IncludeResponse`, described as "The igPackageVersion value of the request (latest, an exact version or a version series) (3.2.0)".

**Checkpoint**: `mvn -q test -Dtest='IncludeSeriesIT,IncludeExcludeIT,ValidationApiContractTest'` is green. US2 works on its own.

---

## Phase 5: User Story 3 — A private version of another organisation is never chosen (Priority: P2)

**Goal**: Series resolution respects spec 005 visibility on both endpoints (SC-007; US3 scenarios 1–3).

**Independent Test**: Organisation B stores a private `1.2.9` and a shareable `1.2.4` exists. When organisation A
includes `1.2.x`, it gets `1.2.4`, and `1.2.9` is named nowhere.

### Tests for User Story 3 ⚠️

- [X] T019 [P] [US3] Add failing cases to `src/test/java/nzi/fhir/validator/core/validator/IncludeOwnershipIT.java`. Use `TestPackages.createOrganisation`, `createKey` and `setPackageAccess`.
  - (a) `own.p#1.2.4` (system org, shareable) and `own.p#1.2.9` (org B, private). Org A includes R4 `1.2.x` → `key.igPackage` `own.p#1.2.4`, and the response body doesn't contain `1.2.9`.
  - (b) Only `own.q#1.2.9` (org B, private). Org A includes `1.2.x` → 404 `package-not-found`, and the body contains neither `1.2.9` nor org B's short name.
  - (c) Org B includes `1.2.x` for `own.p` → `own.p#1.2.9`.
- [X] T020 [P] [US3] Add failing cases to `src/test/java/nzi/fhir/validator/core/npm/RegisterSeriesIT.java`:
  - (a) Registries disabled. `own.r#1.2.4` is shareable and `own.r#1.2.9` is private to org B. Org A registers `1.2.x` → `1.2.4`.
  - (b) The registry lists `own.s#1.2.9` as the highest `1.2` release, and org B stored it privately. Org A registers `1.2.x` → 404 `package-not-found`, the same as registering `1.2.9` exactly, and the body doesn't contain org B's short name.

### Implementation for User Story 3

- [X] T021 [US3] Run T019 and T020 against the US1/US2 implementation. If anything fails, fix only the filtering:
  - include: `visible` in `IgPackageService.include`;
  - register fallback: the `AccessPolicy.canUse` filter in T011;
  - registry-chosen version: the existing `packageAccess` check in `register`.

  Don't change `AccessPolicy`. Record in the commit message whether a fix was needed.

**Checkpoint**: `mvn -q test -Dtest='IncludeOwnershipIT,RegisterSeriesIT'` is green.

---

## Phase 6: User Story 4 — Malformed series are rejected clearly (Priority: P3)

**Goal**: Malformed series answer 400 on both endpoints, and `?ig=` with any series answers 400 (FR-002, FR-018;
research R2, R6).

**Independent Test**: `1.x.3` on register or include, and `?ig=name%231.2.x` on validate, each give 400
`invalid-request`. Nothing is stored, included or validated, and the registry gets no requests.

### Tests for User Story 4 ⚠️

- [X] T022 [P] [US4] Add failing unit tests to `src/test/java/nzi/fhir/validator/core/validator/IgReferenceTest.java`: `IgReference.parse` throws a `ProblemException` with status 400, type `invalid-request` and the message `The ig parameter needs an exact version (for example the version reported by include-ig), not a version series` for:
  - `p#1.2.x`
  - `p#1.x`
  - `p#1.X.x`
  - `p#1.x.3`

  `p#1.2.3`, `p#latest` and `p` still parse as before.
- [X] T023 [P] [US4] Add failing contract tests to `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java`: `POST /R4/validate?ig=some.pkg%231.2.x` with a valid Patient → 400 `invalid-request` problem body, and no `X-Validation-Context` header.
- [X] T024 [P] [US4] Add failing cases to `RegisterSeriesIT.java` and `IncludeSeriesIT.java`. Each of `x`, `x.x.x`, `1.x.3`, `1.2.x.y`, `1.2.3.x` gives 400 `invalid-request` with the message `Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)`, `registry.requests()` is unchanged, and no package is stored or included (SC-005).

### Implementation for User Story 4

- [X] T025 [US4] In `src/main/java/nzi/fhir/validator/core/validator/IgReference.java` `parse`: after the `latest` check, and before or alongside the `VERSION` pattern check, throw `new ProblemException(400, "invalid-request", "The ig parameter needs an exact version (for example the version reported by include-ig), not a version series")` when `VersionSeries.isSeries(version)`. Update the class Javadoc to cite spec 006 FR-018.
- [X] T026 [US4] Update `src/main/resources/openapi.yaml`: append to the `ig` query parameter description of `POST /{version}/validate` (contracts/http-api.md §3): "The version must be exact (or `latest`, or omitted). A version series such as `name#1.2.x` is rejected with 400 invalid-request; use the concrete version reported by include-ig."
- [X] T027 [US4] Check that T024 passes with the US1/US2 code. The 400 comes from `VersionSeries.parse`, called in `IgPackageService.register` and `include` before any I/O. If a malformed series reaches a registry or the database, move the `parse` call earlier.

**Checkpoint**: `mvn -q test -Dtest='IgReferenceTest,ValidationApiContractTest,RegisterSeriesIT,IncludeSeriesIT'` is green.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T028 Add to the `## [Unreleased]` section of `CHANGELOG.md` (research R8):
  - **Added**: version series (`N.x`, `N.x.x`, `N.N.x`) on `POST /igs/register` (resolved from the package registries, with a stored fallback and warning) and on `POST /{version}/include-ig` (resolved from stored packages only); `requestedVersion` on `IgRegistrationResponse` and `IncludeResponse`. Reference GitHub issue #56.
  - **Changed**: `?ig=name#<series>` on `POST /{version}/validate` is now 400 `invalid-request` (was 404 `package-not-found`). The request could never succeed before.
- [X] T029 [P] Set the `info.version` in `src/main/resources/openapi.yaml` and the `<version>` in `pom.xml` to `3.2.0` only if this repository bumps versions in feature branches (check `git log` for #54 `release-3.1.0`). Otherwise leave both, and note in the PR description that the release PR does the bump.
- [X] T030 Run the full suite (`mvn -q test`) and fix any regressions. In particular, `RegisterLatestIT`, `IncludeExcludeIT`, `IncludeOwnershipIT` and `IgScopedValidationIT` must pass unchanged (FR-016, SC-006).
- [X] T031 Walk through the automated checks in `specs/006-ig-version-series/quickstart.md`. Correct any command or expected output that doesn't match the implementation (for example warning texts and field names).
- [X] T032 Benchmark the validation hot path (constitution VI; T025 changes `IgReference.parse`).
  - Add an optional `IG` environment variable to `perf/validate.js`. When it's set, post to `` `${BASE}/R4/validate?ig=${encodeURIComponent(__ENV.IG)}` `` instead of `/R4/validate`. Keep the `validate` tag and the thresholds.
  - Document the variable in `perf/README.md`.
  - Run the measurement with the same IG, which must be included first, for example `-e IG=hl7.fhir.us.core#6.1.0`:
    - on `main` (baseline);
    - on the feature branch, after T025;
    - both with `docker compose up -d --wait`, a short warm-up, and `k6 run -e VUS=8 -e DURATION=60s -e IG=… perf/validate.js`.
  - Paste `http_reqs`, `http_req_duration p(95)` and `http_req_failed` from both runs into the PR description.
  - Expected: p95 < 200 ms, `http_req_failed` 0, and throughput within run-to-run noise of the baseline.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: depends on Setup. **Blocks all user stories.**
- **US1 (Phase 3)** and **US2 (Phase 4)**: depend only on Foundational. They're independent of each other, but both edit `IgPackageService.java` and `openapi.yaml`, so sequence those edits (or merge carefully) when working in parallel.
- **US3 (Phase 5)**: depends on US1 (register fallback) and US2 (include resolution). It's verification plus targeted fixes.
- **US4 (Phase 6)**: T022/T023/T025/T026 depend only on Foundational. T024/T027 depend on US1 and US2, because they extend those ITs.
- **Polish (Phase 7)**: depends on every story you deliver.

### Within each story

Tests first, and they must fail. Then the cache manager / service, then the API layer, then `openapi.yaml`. The
contract tests turn green only once `openapi.yaml` is updated.

### Task-level dependencies

- T004 → T005 (`VersionSeries` uses `releaseParts`)
- T008 → T009 → T011
- T010 → T011
- T011 → T012
- T016 → T017
- T025 depends on T005
- T032 depends on T025 (measures the changed `IgReference.parse`); the baseline run on `main` can happen any time

## Parallel Opportunities

- **Phase 2**: T002 ∥ T003 (different test files).
- **US1 tests**: T006 ∥ T007.
- **US2 tests**: T014 ∥ T015.
- **US3 tests**: T019 ∥ T020.
- **US4 tests**: T022 ∥ T023 ∥ T024.
- **Across stories, after Phase 2**: US2's T014–T015 and US4's T022/T023/T025 can be written while US1 is in progress.

### Parallel example: User Story 1

```text
Task: "T006 [US1] RegisterSeriesIT in src/test/java/nzi/fhir/validator/core/npm/RegisterSeriesIT.java"
Task: "T007 [US1] register contract tests in src/test/java/nzi/fhir/validator/web/endpoint/IgPackageApiContractTest.java"
```

### Parallel example: User Story 2

```text
Task: "T014 [US2] IncludeSeriesIT in src/test/java/nzi/fhir/validator/core/validator/IncludeSeriesIT.java"
Task: "T015 [US2] include contract tests in src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java"
```

## Implementation Strategy

### MVP first (User Story 1)

1. Phase 1 → Phase 2 (`VersionSeries`).
2. Phase 3 (register by series): this is the failure reported in issue #56.
3. **Stop and validate**: `RegisterSeriesIT` + `RegisterLatestIT` are green, and quickstart scenarios 1–3 pass.

### Incremental delivery

1. Add US2 (include by series). It covers the second endpoint in the issue.
2. Add US3 (ownership verification). The access rules should already hold, so this mostly adds tests.
3. Add US4 (clear 400s, including `?ig=`).
4. Polish: CHANGELOG, full suite, quickstart.

### Notes

- Each story ends at a checkpoint with a single `mvn -q test -Dtest=…` command.
- Commit after each task or logical group, with conventional commit messages (for example
  `feat(igs): resolve version series on register`).
- Don't change `latest` semantics. Its stored fallback without access filtering is a known difference (research R4) and out of scope.
