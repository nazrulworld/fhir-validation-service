# Implementation Plan: IG-Scoped Validation (Per-IG Validator Snapshots, Registry-Resolved "latest")

**Branch**: `004-ig-scoped-validation` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/004-ig-scoped-validation/spec.md`

## Summary

Clients choose, per validation request, the one IG package they validate against (`?ig=name#version`, `name` or
`name#latest`), as with the CLI's `-ig`. Without `ig`, the request uses a **base context** that loads no package: the
built-in core definitions and terminology support every validator already has (research R1, a correction to the
input).

`ValidatorRegistry` moves from one slot per FHIR version to one slot per `SnapshotKey(fhirVersion, igPackage)`.
There are three kinds of slot:

- **base** slots, one per version;
- **pinned** slots for included IGs; include *adds* a slot and never replaces another version;
- a bounded LRU of **on-demand** slots, built on first use from stored packages only, with single-flight builds and
  a bounded wait.

A new in-memory **package catalog view**, kept current by a shared **catalog revision** and a `PACKAGE`
notification, answers existence, version resolution, FHIR-version and dependency questions without touching the
database on the hot path. It also invalidates contexts when a stored package is replaced.

When memory is short, LRU on-demand contexts are evicted first. If that isn't enough, the answer is 503
`insufficient-capacity`, or 413 when the context could never fit. A new `DELETE /{version}/include-ig` excludes an
IG version. `POST /igs/register` with "latest" now asks the registries, and the response says whether anything was
downloaded.

This breaks current behaviour, so it ships as **3.0.0**. Migration V7 drops `resolved_ig_packages` and adds
`fhir_package_catalog`. Existing included IGs become pinned contexts as they are.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Vert.x 4.5.x (web, web-openapi, pg-client, config), HAPI FHIR 8.x, Caffeine (existing
`PackageCache` only), Micrometer Prometheus. No new dependencies.

**Storage**: PostgreSQL. One Flyway migration, `V7__ig_scoped_contexts.sql`: it creates `fhir_package_catalog`
(single-row revision) and drops `fhir_validator_logs.resolved_ig_packages`. `included_ig_packages` keeps its type,
and each entry becomes a pinned context.

**Testing**: JUnit 5 + `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`, `AppHarness`,
`CoreHarness`), synthetic IG packages from `TestPackages`, and a stub package registry (existing
`RegistryFallbackTest` pattern) for register-latest.

**Target Platform**: Linux container (x86_64, arm64), JDK 17+ fat jar.

**Project Type**: A single-module REST web service.

**Performance Goals**: p95 under 200 ms for single-resource validation against a built context, with and without
`ig`, and no regression against 2.0.0 (SC-007, benchmark in the PR). The first request for an unbuilt context waits
at most `FV_IG_BUILD_WAIT_MS` (default `min(10 s, requestTimeout/2)`).

**Constraints**:
- No database or network wait on the validation path for a built context (FR-013). The catalog view answers
  lookups in memory.
- Builds run on `fv-packages` and never on the event loop (Principle I).
- On-demand builds hold an admin admission slot.
- Memory is bounded by `HeapBudget` with on-demand eviction and by `FV_IG_MAX_ON_DEMAND` (default 4, range 1 to 64).
- Curated client errors (Principle IV); the same supported versions on every endpoint.

**Scale/Scope**:
- About 10 production classes change: `ValidatorRegistry`, `ConfigSync`, `ConfigRevisionRepository`,
  `SnapshotBuilder`, `HeapBudget`, `ValidatorSnapshot`, `FhirValidationService`, `ValidationApi`, `IgPackageService`,
  `PostgresPackageCacheManager`, and minor changes to `IgPackageApi`, `HealthService`, `ServiceMetrics`,
  `ApiKeyAuthHandler`, `ConfigLoader`, `ApplicationConfig`, `OutcomeRenderer` and `ServiceRegistry`.
- About 3 small classes are new: `SnapshotKey`, `IgReference`, `PackageCatalogView`.
- About 7 test classes are new and about 8 are extended.
- Documentation: `openapi.yaml`, `CHANGELOG.md`, `docs/upgrade-3.0.md`, README, and the constitution's Principle II
  wording.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Non-blocking event loop | **Pass.** Builds stay on `fv-packages` (`executeBlocking`, unordered); catalog refreshes are async SQL; the heap check with GC runs on a worker. Every new API returns `Future`. No `.get()` and no `Thread.sleep`. |
| II. Stateless, horizontally scalable | **Pass.** PostgreSQL stays the source of truth: the included set per version and the package catalog. Contexts and the catalog view are derived caches, rebuildable from the database. Cross-instance consistency is described: per-version revision plus the new catalog revision, both over the existing `LISTEN/NOTIFY` with poll fallback (R3, R4). One validator per identity per instance (single-flight per `SnapshotKey`). No new infrastructure. Profile sourcing follows the Principle II text as amended in constitution **1.1.0** (2026-11-06, a separate amendment made before implementation): profiles come only from the package chosen for a request (or none), its dependencies and the core specification. |
| III. FHIR conformance | **Pass.** Each context is the CLI's `-ig` model (one IG, its dependencies and core). Dependency resolution is still the deterministic `DependencyResolver`. Issues are never suppressed. The OperationOutcome body is unchanged, and the context is given in a header (R9). |
| IV. Contract-first API | **Pass.** `openapi.yaml` changes land first (contracts/http-api.md): the `ig` parameter, `igPackage`, the header, `DELETE /{version}/include-ig`, and the changed `IncludeResponse`, `IgRegistrationResponse` and health shape. Breaking changes mean a MAJOR bump to 3.0.0 with a changelog entry and an upgrade guide. Errors stay curated problem JSON, with two new types: `insufficient-capacity` (503) and `dependency-not-found` (424). |
| V. Test-first with real dependencies | **Pass.** Every scenario has a failing test first (quickstart table), using Testcontainers PostgreSQL for the registry, sync, migration and invalidation. A Mockito-free stub HTTP registry is used for register-latest. Two-instance propagation tests extend `ConfigPropagationIT`. |
| VI. Performance and capacity | **Pass.** Latency and memory impact are stated (R14). New bounds are configurable and limited (`FV_IG_MAX_ON_DEMAND`, `FV_IG_BUILD_WAIT_MS`); there is no unbounded cache. The hot path changes, so a benchmark is required in the PR (quickstart §3). Readiness reflects base and pinned contexts. |
| VII. Observability | **Pass.** New metrics for contexts, builds, evictions and lookups, and a `package_not_found` validation outcome (R12). Health details per context. Request ids flow through the existing log layout. New settings come through `ConfigLoader` and the environment. |
| VIII. Simplicity and dependencies | **Pass.** No new dependency. Caffeine was considered for the on-demand LRU and rejected in favour of a small locked `LinkedHashMap` (R2). Schema changes go only through `V7__…sql`, and no Java migration is used (R3, R13). |
| Security and compliance | **Pass, with an explicit fix.** `DELETE /{version}/include-ig` must be added to `ApiKeyAuthHandler.isAdmin`, which matches POST only today, with a regression test (contracts §3). Validation never contacts a registry. Register keeps using the configured registries only. `ig` values are syntax-checked and length-bounded before use, and are logged only at DEBUG. |

**Gate result**: PASS. No violations, and Complexity Tracking is empty.

**Re-check after Phase 1**: PASS. The design adds three small classes and one table, and no infrastructure. The
constitution 1.1.0 amendment to Principle II is a prerequisite for implementation (task T001a). Waiting for an
on-demand build holds a validation admission slot, so no queue is unbounded (Principle VI, research R5).

## Project Structure

### Documentation (this feature)

```text
specs/004-ig-scoped-validation/
├── spec.md
├── plan.md              # this file
├── research.md          # Phase 0: R1–R14
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1: validation guide and test map
├── contracts/
│   └── http-api.md      # Phase 1: openapi.yaml changes, notifications
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── ServiceRegistry.java                      # wire catalog view, evictor, new settings
├── core/
│   ├── config/{ApplicationConfig,ConfigLoader}.java      # FV_IG_MAX_ON_DEMAND, FV_IG_BUILD_WAIT_MS
│   ├── concurrency/AdmissionController.java  # unchanged (used via tryAcquire for on-demand builds)
│   ├── metrics/ServiceMetrics.java           # context gauges/counters, package_not_found outcome
│   ├── npm/
│   │   ├── PackageCatalogView.java           # NEW: in-memory metadata view + refresh
│   │   ├── PackageCache.java                 # + invalidate(name#version)
│   │   └── PostgresPackageCacheManager.java  # store in tx + catalog revision + PACKAGE notify; resolveForRegistration
│   ├── security/ApiKeyAuthHandler.java       # isAdmin also matches DELETE /{v}/include-ig
│   ├── service/
│   │   ├── ConfigRevisionRepository.java     # drop resolved column use; exclude; catalog revision read
│   │   ├── FhirValidationService.java        # validate(version, igRef, ...) via registry.snapshot()
│   │   ├── HealthService.java                # new details shape
│   │   └── IgPackageService.java             # include adds; exclude; registerIg latest from registry
│   └── validator/
│       ├── SnapshotKey.java                  # NEW
│       ├── IgReference.java                  # NEW: parse/validate the ig parameter
│       ├── ValidatorRegistry.java            # slots by key: base, pinned, on-demand LRU; snapshot()
│       ├── ConfigSync.java                   # per-version pinned sync + catalog sync (PACKAGE kind, poll)
│       ├── SnapshotBuilder.java              # build(key, resolved, hashes); evictor passed to heap
│       ├── HeapBudget.java                   # reserve/admitInclude with Evictor; 503 vs 413 decision
│       ├── ValidatorSnapshot.java            # + key, packageHashes
│       └── OutcomeRenderer.java              # igPackage in ValidationResponse
└── web/endpoint/{ValidationApi,IgPackageApi}.java   # ig param, header, exclude route, register response

src/main/resources/
├── openapi.yaml                              # contract changes (first)
├── config.json                               # ig.maxOnDemand, ig.buildWaitMs
└── db/migration/V7__ig_scoped_contexts.sql   # NEW

src/test/java/nzi/fhir/validator/
├── core/validator/{IgReferenceTest, ValidatorRegistryKeyTest, OnDemandContextIT, ContextEvictionIT,
│                   IncludeExcludeIT, PackageInvalidationIT}.java          # NEW
├── core/npm/{PackageCatalogViewTest, RegisterLatestIT}.java               # NEW
├── web/IgScopedValidationIT.java                                          # NEW
└── (extended) ProfileResolutionIT, ConfigPropagationIT, HeapGuardIT, ValidatorSnapshotSwapIT,
    MigrationUpgradeIT, PackageLatestResolutionIT, ApiKeyAuthIT, ValidationApiContractTest,
    IgPackageApiContractTest, HealthApiTest, ObservabilityIT

docs/upgrade-3.0.md, CHANGELOG.md, README.md, (constitution 1.1.0 amended separately beforehand)
scripts/bench-validate.sh                                                  # NEW: PR benchmark
```

**Structure decision**: the existing single-module Maven layout. New classes go into existing packages
(`core.validator`, `core.npm`). No new modules.

## Implementation phases (input for /speckit-tasks)

1. **Contract and schema first**: the `openapi.yaml` changes (contracts/http-api.md), `V7__ig_scoped_contexts.sql`,
   and `MigrationUpgradeIT` (empty database and 2.0.0 → 3.0.0 with two included IGs). Version bump to 3.0.0.
2. **Catalog view (R4)**:
   - `PackageCatalogView` with its unit test;
   - package store in one transaction with the catalog revision and `PACKAGE` notify;
   - `ConfigSync` handling of `PACKAGE` and catalog poll;
   - `PackageCache.invalidate`.
3. **Registry by key (R2)**: `SnapshotKey`, slots for base and pinned contexts, and `ConfigSync` resolving each
   included key from the view. Include adds without replacing (FR-019) and its admission covers this key's set
   only. Existing propagation and swap tests are adapted.
4. **On-demand contexts (R5 to R7)**:
   - `IgReference` and `snapshot()` with single-flight builds, build wait and admin admission;
   - the LRU bound;
   - the `HeapBudget` evictor with the 503/413 split;
   - failure handling (FR-017).
5. **Validate endpoint (R6, R9)**: the `ig` parameter, check order, `igPackage` and the `X-Validation-Context`
   header, `profile` scoped to the chosen context, and the `package_not_found` metric outcome.
6. **Invalidation (FR-025)**: hash-based STALE or drop on package replacement, across instances.
7. **Exclude (R10)**: repository, service and route; the admin predicate fix with an auth regression test; demotion
   to on-demand.
8. **Register latest (R11)**: `resolveForRegistration`, `downloaded` and `warnings`; a stub-registry IT for all four
   cases.
9. **Observability (R12)**: health detail shape, readiness over base and pinned contexts, and new metrics.
10. **Release**:
    - CHANGELOG 3.0.0 and `docs/upgrade-3.0.md` (default change, include semantics, exclude, health shape, stop 2.x
      before starting 3.0, backup to roll back, sizing note);
    - README;
    - the benchmark script and results.

## Complexity Tracking

No constitution violations, so this section is empty.
