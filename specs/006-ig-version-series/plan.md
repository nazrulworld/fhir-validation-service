# Implementation Plan: IG Version Series (`1.2.x`, `1.x.x`) on Register and Include

**Branch**: `006-ig-version-series` | **Date**: 2026-11-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/006-ig-version-series/spec.md` (GitHub issue #56)

## Summary

`POST /igs/register` and `POST /{version}/include-ig` accept a version series (`N.x`, `N.x.x`, `N.N.x`) and
resolve it to one concrete version. From then on the request runs through the existing exact-version path
unchanged.

- **Registration** matches the series against the versions listed by the package registries. If the registries
  can't decide, it falls back, with a warning, to the highest matching stored version the caller may use.
- **Include** matches only against stored versions the caller may use, and never contacts a registry.
- **Validation** (`?ig=` on `POST /{version}/validate`) rejects a series with 400 (clarification Q1).

The work is:

- a new pure `VersionSeries` value class that reuses `PackageVersionComparator`'s parser and ordering;
- a refactor of the registry listing fetch so the `latest` and series paths can share it;
- one resolution branch each in `IgPackageService.register` and `include`;
- a guard in `IgReference.parse`;
- an additive `requestedVersion` field in both responses.

There's no schema change and no new shared state.

**3.2.1 follow-up (Session 2026-11-08 (2), FR-019 to FR-023, research R10–R12)**:

- dependencies declared with a series or `latest`/`current` are resolved during registration (registries first,
  then stored) and by `DependencyResolver` at include and build time (highest stored match);
- the `latest` registration fallback only considers stored versions the caller may use, through one fallback helper
  shared with the series path.

## Technical Context

**Language/Version**: Java (project toolchain, as in `pom.xml`, version 3.1.0)

**Primary Dependencies**: Vert.x (core, web, web-openapi-router, pg-client), HAPI FHIR. No new dependencies.

**Storage**: PostgreSQL. Reads only: the existing packages table joined with organisations. No Flyway migration.

**Testing**: JUnit 5 + `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`), `AppHarness` and
`StubRegistry` test support.

**Target Platform**: Linux server / container, horizontally scaled instances behind a load balancer.

**Project Type**: Web service (single Maven module).

**Performance Goals**:

- Registration with a series costs the same as `latest`: one parallel listing fetch per configured registry,
  plus a download only when the version is new.
- Include with a series adds an in-memory filter over a package's stored versions (typically fewer than 50).
- The validation hot path changes only by one string check in `IgReference.parse` for `?ig=`. The expected impact is
  negligible: p95 stays under 200 ms and throughput is within run-to-run noise of the baseline. Because it's on the
  hot path, the PR attaches a before/after `perf/validate.js` k6 result (constitution VI; tasks T032).

**Constraints**:

- No blocking on the event loop. All new I/O is Vert.x `Future`-based (`SqlClient` and the existing
  `SafePackageFetcher`).
- Include and validation never download.
- Curated error text, never internal messages.

**Scale/Scope**:

- About 6 production files touched, 1 new class.
- About 5 test classes added or extended.
- The OpenAPI contract and CHANGELOG are updated.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Assessment | Status |
|-----------|------------|--------|
| I. Non-blocking event loop | `VersionSeries` is pure CPU over small lists. New DB access (`storedVersionsWithAccess`) is a single `SqlClient` query returning a `Future`. Registry access reuses the async `SafePackageFetcher`. | PASS |
| II. Stateless, horizontally scalable | No new shared or in-memory state. Include writes the concrete key through the existing revisioned configuration, so propagation (`LISTEN/NOTIFY` + poll) is unchanged. A series is never stored. | PASS |
| III. FHIR conformance / deterministic resolution | The same candidate set and series always give the same version (FR-005). It reuses `PackageVersionComparator`. Packages are still identified by concrete `name#version`. | PASS |
| IV. Contract-first API | `openapi.yaml` is updated with or before the code ([contracts/http-api.md](./contracts/http-api.md)). New fields are additive. The `?ig=` series status changes 404 → 400 (see Complexity Tracking). The errors keep the problem-details format. | PASS (justified) |
| V. Test-first with real dependencies | Failing tests first. Unit tests for the pure class. Testcontainers ITs for register, include and ownership. Contract tests for both endpoints. | PASS |
| VI. Performance and capacity | Impact stated above. No new caches or queues. `IgReference.parse` is on the validation hot path, so the PR attaches a before/after k6 run of `perf/validate.js` with an `ig` query (`http_reqs`, p95, `http_req_failed`), per `perf/README.md` (T032). | PASS |
| VII. Observability | It reuses the existing structured logs (registration info and warn lines include the requested series). No new metrics. | PASS |
| VIII. Simplicity and dependency discipline | No new dependency or infrastructure. No migration. | PASS |

**Post-design re-check (after Phase 1)**: unchanged. All gates pass. The single justified item is listed below.

## Project Structure

### Documentation (this feature)

```text
specs/006-ig-version-series/
├── spec.md
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1–R9
├── data-model.md        # Phase 1: VersionSeries, candidate sets, outcome
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── http-api.md      # Phase 1: openapi.yaml changes
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── core/npm/
│   ├── VersionSeries.java                 # NEW: detect, parse (400), matches, highest  (R1, R2)
│   ├── PackageVersionComparator.java      # + releaseParts(String) accessor             (R1)
│   ├── PostgresPackageCacheManager.java   # fetchListings refactor; resolveSeriesFromRegistries (R3)
│   │                                      # 3.2.1: RegistryResult; resolveDependencyVersion (R10, R11)
│   └── DependencyResolver.java            # 3.2.1: series branch in concrete()               (R10)
├── core/service/
│   └── IgPackageService.java              # register(): series branch + access-aware fallback;
│                                          # include(): series resolution; storedVersionsWithAccess (R4, R5)
│                                          # 3.2.1: storedFallback shared by latest and series (R11)
├── core/validator/
│   └── IgReference.java                   # parse(): reject a series with 400                (R6)
└── web/endpoint/
    ├── IgPackageApi.java                  # requestedVersion on the register response        (R7)
    └── ValidationApi.java                 # requestedVersion on the include response         (R7)

src/main/resources/openapi.yaml            # contract changes (contracts/http-api.md)
CHANGELOG.md                               # [Unreleased] → 3.2.0: Added / Changed            (R8)
pom.xml                                    # version 3.2.0 at release time

src/test/java/nzi/fhir/validator/
├── core/npm/VersionSeriesTest.java        # NEW (unit)
├── core/npm/RegisterSeriesIT.java         # NEW (StubRegistry + AppHarness, modelled on RegisterLatestIT)
├── core/npm/RegisterDependencySeriesIT.java # NEW 3.2.1 (US5)
├── core/npm/DependencyResolverTest.java   # + series dependencies (3.2.1)
├── core/npm/RegisterLatestIT.java         # + access-aware fallback (3.2.1)
├── core/validator/IncludeSeriesIT.java    # NEW (modelled on IncludeExcludeIT)
├── core/validator/IncludeOwnershipIT.java # + series ownership cases
├── core/validator/IgReferenceTest.java    # + series rejected
└── web/endpoint/{IgPackageApiContractTest,ValidationApiContractTest}.java  # + 400s, requestedVersion
```

**Structure Decision**: This is the existing single-module service layout. The new logic sits in `core/npm`, next
to the version ordering it builds on. The endpoint changes are limited to response decoration.

## Implementation Order

1. **`VersionSeries` + `PackageVersionComparator.releaseParts`**: `VersionSeriesTest` first (red → green).
2. **`IgReference.parse` guard**: `IgReferenceTest`, then the contract test for the 400 on `?ig=`.
3. **Include series branch** (`IgPackageService.include`): `IncludeSeriesIT`, then the ownership cases in
   `IncludeOwnershipIT`.
4. **Registry refactor**: `fetchListings`, with `RegisterLatestIT` kept green. Then
   `resolveSeriesFromRegistries`, `storedVersionsWithAccess` and the register series branch: `RegisterSeriesIT`,
   covering registry match, already stored, registries disabled, not listed, malformed, and a private version of
   another organisation.
5. **`requestedVersion`** in both handlers, plus the contract tests.
6. **`openapi.yaml`** (with step 5 at the latest), CHANGELOG, and the version bump.
7. **3.2.1: access-aware `latest` fallback** (R11): `RegistryResult`, `IgPackageService.storedFallback`; new
   `RegisterLatestIT` ownership cases first.
8. **3.2.1: dependency series and `latest`** (R10): `DependencyResolver.concrete` series branch
   (`DependencyResolverTest` first), then `resolveDependencyVersion` in the registration walk
   (`RegisterDependencySeriesIT`), then the include check in `IncludeSeriesIT`. CHANGELOG **Fixed** and 3.2.1.

Steps 1–3 deliver US2 and US4 independently of the registry work. Step 4 delivers US1. US3 is covered across
steps 3 and 4.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| A status code changes on an existing operation (`?ig=name#1.2.x`: 404 → 400) without a major version | Recorded as a decision in spec.md Clarifications (Session 2026-11-08): not a breaking change under constitution IV. Clarification Q1 asked for a clear client error. The request could never succeed before, so no working client depends on the old answer. It's a 4xx → 4xx change with no path or payload change, and it's recorded under "Changed" in the CHANGELOG. | Keeping 404 (option C) was rejected by the user. A major version for a change that affects only requests that already fail would overstate the impact. |
