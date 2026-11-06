# Research: IG-Scoped Validation

**Feature**: `004-ig-scoped-validation` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

Code read for this research: `ValidatorRegistry`, `ConfigSync`, `ConfigRevisionRepository`, `SnapshotBuilder`,
`HeapBudget`, `ValidatorSnapshot`, `FhirValidationService`, `ValidationApi`, `IgPackageApi`, `IgPackageService`,
`PostgresPackageCacheManager`, `DependencyResolver`, `PackageCache`, `AdmissionController`, `ServiceRegistry`,
`OutcomeRenderer`, migrations `V1` to `V6`, and `openapi.yaml`.

Technical Context had no open NEEDS CLARIFICATION items after `/speckit-clarify`. The decisions below settle the
design questions the spec leaves to planning, and one factual correction.

---

## R1. What the base context contains (correction to the input)

- **Finding**: Nothing in the code loads `hl7.terminology` or `hl7.fhir.uv.extensions`. `SnapshotBuilder.assemble`
  always chains `DefaultProfileValidationSupport` (the core definitions bundled with HAPI), then the resolved
  packages through `CustomNpmPackageValidationSupport`, then `InMemoryTerminologyServerValidationSupport`,
  `CommonCodeSystemsTerminologyService` and `SnapshotGeneratingValidationSupport`. A FHIR version with no included
  IG is built from an empty package list.
- **Decision**: The base context is the snapshot built from an **empty resolved package list**, the same thing a
  version with no included IG gets today. I corrected spec FR-005, US1-2, the Key Entities and the first
  clarification to say so.
- **Rationale**: This keeps today's no-IG behaviour exactly, adds no package dependency, and needs no package in the
  database for the service to start.
- **Alternatives considered**: Loading `hl7.terminology` and `hl7.fhir.uv.extensions` into the base context was
  rejected. It would change results for every request without `ig`, beyond what the clarification intended. It would
  also make startup depend on those packages being stored, and they aren't stored by default.

## R2. Registry structure: one slot per snapshot key

- **Decision**: Replace the `EnumMap<SupportedFhirVersion, Slot>` in `ValidatorRegistry` with slots keyed by
  `SnapshotKey(SupportedFhirVersion fhirVersion, String igPackage)`, where `igPackage == null` is the base context.
  There are two collections:
  - **Pinned slots**: the base context per enabled version, plus one slot per included IG, driven by the shared
    configuration. They are never evicted.
  - **On-demand slots**: an access-ordered `LinkedHashMap` (LRU) under one lock, bounded by
    `FV_IG_MAX_ON_DEMAND` (R8) and by memory pressure (R7).

  Each slot keeps today's state machine (`ABSENT`, `BUILDING`, `ACTIVE`, `STALE`), single-flight build with
  waiters, and retry with backoff. The retry/backoff applies to pinned slots only. A failed on-demand slot is removed
  and retried on the next request (FR-017), so a bad package can't leave a timer running.
- **API**: `Future<ValidatorSnapshot> snapshot(SupportedFhirVersion v, String igPackage)` replaces
  `current(version)`:
  - **Base or pinned**: answers synchronously from memory (`Future.succeededFuture`) or with a 503 problem, exactly
    as `current` does now.
  - **On-demand**: returns the existing snapshot (and marks it used for the LRU), joins an in-flight build, or starts
    one (R5).
- **Rationale**: This is the smallest change that keeps the proven slot logic, and it satisfies Principle II: one
  validator per identity per instance, reused across requests, held in thread-safe structures.
- **Alternatives considered**: A Caffeine cache for on-demand snapshots was rejected. Its eviction runs
  asynchronously and by weight, but eviction here must happen synchronously and on demand when the heap check fails
  (R7). It must also never pick a snapshot that is being built, and it must keep single-flight waiters, all of which
  Caffeine would need to be bent around. A per-version registry holding a map of IGs was also rejected: it couples
  unrelated IGs to one version-level lock and state.

## R3. Shared configuration: included keys only, resolved per key at apply time

- **Decision**: Keep one `fhir_validator_logs` row per FHIR version with its `revision`. `included_ig_packages`
  keeps its type (`TEXT[]` of `name#version`) but changes meaning: **each entry is its own pinned context**, and
  include no longer removes other versions of the same package. Drop `resolved_ig_packages` (migration V7, R13).
  Each instance resolves every included key's package set from its package catalog view (R4) with the existing
  `DependencyResolver`, which is deterministic.
- **Rationale**:
  - Existing rows already hold `name#version` keys, so the migration keeps every included IG as a pinned context
    without rewriting data (FR-035).
  - Per-key resolved sets can't be computed in a SQL migration, because `DependencyResolver` is Java, and the
    constitution allows only versioned SQL migrations (Principle VIII).
  - Resolving against the shared catalog is deterministic for a given catalog state (Principle III). Every instance
    converges once catalog changes have propagated (R4).
- **Consequence**: A newly stored version of a dependency that some included IG references without a version (or as
  `latest`) changes that IG's resolved set. The context is rebuilt on every instance, and the previous one keeps
  serving until the swap, as configuration changes already behave. This matches what on-demand contexts do anyway.
- **Alternatives considered**:
  - A new table `fhir_validator_contexts(fhir_version, ig_package, resolved TEXT[])` with resolved sets persisted at
    include time. Rejected because the migration can't fill `resolved` for existing rows without Java. It would
    need a backfill on first start, which is an extra moving part that two instances could race on.
  - A Flyway Java migration. Rejected: it breaks the SQL-only rule in Principle VIII.

## R4. Package catalog view and catalog revision (hot path without the database, invalidation)

- **Decision**: Add a per-instance **`PackageCatalogView`** with metadata only: name, version, declared FHIR versions
  (`ig_package_meta`), dependencies and `content_sha256` for every stored package. Add a shared **catalog revision**
  (new single-row table `fhir_package_catalog`, migration V7). Every package write (upload, URL download or registry
  download, whether it inserts or replaces) runs in one transaction that:
  1. upserts the package;
  2. increments the catalog revision;
  3. calls `pg_notify('fv_config_changed', {"kind":"PACKAGE","key":"name#version","revision":n})`.

  `ConfigSync` handles `kind = PACKAGE` by refreshing the view, with refreshes coalesced into one. The periodic poll
  compares the catalog revision too, so a missed notification is caught within the poll interval.
- **The view serves**:
  - versionless `ig` resolution, using the highest stored version by `PackageVersionComparator`, the same rule
    include uses (FR-002);
  - the existence check (FR-003);
  - the FHIR version check (FR-004, using the same `declaredFhirVersions` logic as `checkFhirVersion`);
  - dependency resolution for every context build;
  - content-change invalidation (FR-025, R6).

  All of these run in memory, so a request for a built context never waits on the database (FR-013).
- **A miss in the view**: the package may have been stored on another instance less than one propagation window
  ago. A miss triggers one coalesced view refresh from the database before the request gets 404 `package-not-found`.
  Concurrent misses share one refresh, and a miss can force a refresh at most once every 1 s per instance
  (`MISS_REFRESH_MIN_INTERVAL_MS = 1000`, fixed). A miss inside that interval is answered from the current view.
  A burst of unknown `ig` values therefore costs at most one metadata query per second. Misses are also bounded by
  validation admission, because resolution runs inside admission (R5).
- **Rationale**: This makes the hot path database-free (Principles I and II) and fixes a gap: today, replacing a
  stored package with the same `name#version` doesn't invalidate the parsed `PackageCache` entry or rebuild anything.
  It reuses the existing channel, the `LISTEN` connection and the poll, with no new infrastructure.
- **Alternatives considered**:
  - Querying `fhir_implementation_guides` on every request that has `ig`. Rejected: it puts the database on the hot
    path, which violates FR-013.
  - A TTL cache of lookups. Rejected: it serves results that are stale by up to the TTL and still needs a separate
    path for invalidation.

## R5. On-demand build flow and the build wait

- **Decision**: For an `ig` with no slot, `snapshot()` follows these steps:
  1. resolve the key from the view (R4);
  2. take one admin admission slot (R6);
  3. create a `BUILDING` slot whose waiters include this request;
  4. call `SnapshotBuilder.build(key, resolved)`.

  Requests for the same key that arrive while it is building join the waiters and take no *admin* admission slot (they still hold their validation slot; see below). Each
  request waits at most **`FV_IG_BUILD_WAIT_MS`**. The default is `min(10 000, FV_HTTP_REQUEST_TIMEOUT_MS / 2)`, the
  same rule as `localApplyWaitMs`, with bounds 1 000 to 120 000. On timeout the request gets 503 `not-ready` with
  `Retry-After: 5`, and the build continues. When it completes, the slot is `ACTIVE` and a retry finds it.
- **Bounded waiting (Principle VI)**: a request takes its validation admission slot (`FV_VALIDATION_MAX_PENDING`)
  *before* it resolves `ig` or looks up or waits for a snapshot, and holds it while it waits. The waiters of a
  building slot are therefore bounded by the validation admission limit. A request that can't get a slot is
  refused at once with 503 `overloaded`, as today. Order: validation admission → `ig` resolution → `snapshot()`
  (lookup, join or build) → validation on `fv-validation`.
- **Rationale**: A request never waits without bound (FR-012). Builds that run longer than one request timeout still
  finish, so retries succeed. The rule matches the existing local-apply wait.
- **Alternatives considered**: Always answering 503 on the first request and building in the background was
  rejected. Small IGs build in a few seconds, and making every first request fail is a worse experience than a
  bounded wait.

## R6. Admission, invalidation and request ordering

- **Admission (FR-014)**: An on-demand build holds one slot of the existing admin admission controller
  (`FV_ADMIN_MAX_PENDING`, default 4) from start to finish, using `tryAcquire`/`release`. With no free slot the
  request gets 503 `overloaded` with `Retry-After: 1`. Builds also pass through `SnapshotBuilder`'s existing
  `AsyncLimiter`, which allows `packageWorkers - 1` concurrent builds. The requesting validation also holds its
  validation admission slot (`FV_VALIDATION_MAX_PENDING`) from before resolution until it completes (R5).
- **Invalidation (FR-025)**: Each snapshot records the `content_sha256` of every package it was built from. On a
  catalog refresh:
  - a **pinned** snapshot containing a package whose hash changed is marked `STALE` (503, curated reason "package
    name#version was replaced") and rebuilt;
  - an **on-demand** snapshot containing it is removed;
  - an **on-demand** snapshot whose resolved set *would change* under the new catalog view (for example a newer
    stored version of a dependency referenced without a version) is removed too. Every instance's on-demand
    snapshots then match the current catalog, as pinned ones do. Removed snapshots are rebuilt on next use;
  - the `PackageCache` entry for that package is invalidated.

  A pinned key whose *resolved set* changed but whose content didn't (R3) is rebuilt while the old snapshot keeps
  serving.
- **Order of checks (FR-009)**:
  1. in `ValidationApi`, the request shape: version, content type, `Accept`, `ig` syntax (`IgReference.parse`),
     profile limits, then an empty body;
  2. in `FhirValidationService.validate(version, igRef, …)`, which owns everything after this step: validation
     admission (R5);
  3. the `ig` resolution (view lookup, versionless resolution, 404 or FHIR-version 400);
  4. `snapshot()`: readiness, build admission and build;
  5. parsing;
  6. the Bundle entry limit;
  7. profile resolution;
  8. validation.

  Every failure from step 2 on goes through the service's single metrics `onComplete`.

## R7. Memory pressure: evict on demand, then 503 or 413

- **Decision**: Give `HeapBudget.reserve(bytes, Evictor)` and `admitInclude(bytes, Evictor)` an evictor callback.
  When the estimate doesn't fit, the budget asks the registry to evict the least recently used **on-demand**
  snapshot that isn't being built. It then runs a full collection (`collect`) and checks again, until the estimate
  fits or nothing is left to evict.
  - **Still doesn't fit, and `estimate(expanded) <= maxHeap`**: `InsufficientCapacityException`, which on demand
    becomes 503 `insufficient-capacity` with `Retry-After: 30`. Pinned snapshots alone fill the budget, so an
    administrator has to exclude something or raise memory.
  - **`estimate(expanded) > maxHeap`**: 413 `package-too-large`, because it can never fit.
  - **Pinned builds and include admission use the same evictor**, so administrator choices take priority over
    on-demand caching.
- **Evicted snapshots still in use**: a request that is validating keeps its reference and finishes, and the heap is
  reclaimed afterwards. No reference counting is needed. "Idle" in the spec therefore means "least recently used";
  in-flight requests are never interrupted.
- **Rationale**: `HeapBudget` measures the real live heap (after a GC), so dropping registry references is what
  frees memory. The 413/503 split tells clients whether a retry can ever help.
- **Alternatives considered**: Accounting snapshots against a fixed byte budget was rejected. Retained size is 3.4
  to 9 times the expanded size (measured; see the `HeapBudget` Javadoc), so a byte budget would drift from reality.
  The existing estimate-plus-live-heap approach is already proven.

## R8. Bounds and defaults

| Setting | Env var | Default | Bounds | Notes |
|---|---|---|---|---|
| Max on-demand contexts per instance | `FV_IG_MAX_ON_DEMAND` | 4 | 1 to 64 | The minimum is 1 because on-demand can't be switched off (clarification Q2) |
| Build wait per request | `FV_IG_BUILD_WAIT_MS` | `min(10000, requestTimeout/2)` | 1 000 to 120 000 | R5 |
| `ig` value length | (fixed) | 256 characters | | FR-002 |

- **Why 4 is the default**: four warmed core validators take about 0.9 GB. A US Core sized context retains a few
  hundred MB, so 4 on-demand contexts plus pinned ones fit a 4 GB heap. Operators raise the limit together with
  `FV_MEMORY_LIMIT`. The heap check (R7) is the real safety net, and the count is a predictability bound.

## R9. `ig` syntax and how the response identifies the context

- **Syntax (FR-002)**: `^(?<name>[A-Za-z0-9][A-Za-z0-9._-]*)(#(?<version>[A-Za-z0-9][A-Za-z0-9.+_-]*))?$`, at most
  256 characters, with exactly one `#` and nothing repeated. `latest` (case-insensitive) or no version means the
  highest stored version. A repeated `ig` parameter, or a comma in the value, is `invalid-request` (one IG per
  request).
- **Response (FR-008)**:
  - The `ValidationResponse` JSON gains `igPackage` (string `name#version`, or `null` for the base context).
  - Every 200 response, whatever the format, carries the header `X-Validation-Context: <name#version>` or
    `X-Validation-Context: base`.
  - The `OperationOutcome` body is left unchanged.
- **Rationale**: Adding an informational issue to the OperationOutcome would change issue counts and could break
  consumers that treat any issue as a finding. A header works for every format, and the JSON shape gets an explicit
  field.
- **Alternatives considered**: `OperationOutcome.meta.tag` was rejected. It isn't commonly read, and HAPI's
  `toOperationOutcome()` would need post-processing.

## R10. The exclude operation

- **Decision**: `DELETE /{version}/include-ig?ig=name#version`. An exact version is required, because excluding
  "latest" is ambiguous. It runs in one transaction with the configuration row locked:
  1. remove the key from `included_ig_packages` (404 `package-not-found` if it isn't included);
  2. bump `revision`;
  3. `pg_notify`.

  It answers after the local apply or after the local-apply wait, with the same warnings as include. The response is
  `ExcludeResponse {status, fhirVersion, revision, excluded, included, warnings}`. It is an administrative operation:
  administrator key and admin admission.
- **Effect on instances**: when an instance applies the revision, the slot loses its pinned status. It is demoted
  to an on-demand slot, so the LRU or memory pressure can evict it, and it no longer counts for readiness. It isn't
  rebuilt again if the IG is requested soon after. The package stays stored and can still be named in `ig`
  (clarification Q4).
- **Alternatives considered**: `POST /{version}/exclude-ig` with a body was rejected. DELETE on the same resource
  path is the conventional REST shape, and the query parameter matches the validate endpoint's `ig`.

## R11. `register` with "latest"

- **Decision**: A new `PostgresPackageCacheManager.resolveForRegistration(name)` returns
  `{version, origin: REGISTRY | STORED_FALLBACK, warning}`:
  - registries enabled → `latestFromRegistries(name)` (the existing `latestFromListing` rule, unchanged);
  - every registry failed (network or 5xx) or registries are disabled → the highest stored version, with the warning
    "No package registry could be consulted; using the highest stored version";
  - registries answered but none lists the package → the highest stored version, with the warning "No package
    registry lists this package; using the highest stored version";
  - no stored version → `null`, which becomes 404 `package-not-found`.

  `IgPackageService.registerIg` then checks whether that exact version is stored. If it is, it returns the stored
  package without a download. If it isn't, it calls `loadPackage(name#version, false, includeDependency)`, which
  downloads it with its dependencies. `resolveVersion` (stored first) stays as it is for `loadPackage` callers that
  aren't registration, so include and dependency resolution keep their behaviour.
- **Response**: `IgRegistrationResponse` gains `downloaded` (boolean) and `warnings` (array). `version` is always the
  concrete version.
- **Rationale**: Registry listing requests already fail soft to `Optional.empty()`. They need a small change to tell
  "unreachable" from "not listed" so the warning is accurate (FR-029).

## R12. `/health`, readiness and metrics

- **Health details (FR-032)**: each version maps to `{ "base": {state, packages, packageBytes}, "contexts": [ {key,
  kind: "pinned" | "on-demand", state, appliedRevision, packages, packageBytes, lastUsed, reason?} ] }`, plus
  top-level `catalogRevision`. This changes the `/health` detail shape, which goes in the CHANGELOG under 3.0.0.
- **Readiness**: the instance is ready when every base slot and every pinned slot is `ACTIVE`. The states of
  on-demand slots don't count.
- **Metrics (FR-033)**, named like the existing `ServiceMetrics`:
  - gauge `fv_validator_contexts{fhir_version, kind}`;
  - counter `fv_validator_context_builds_total{fhir_version, kind, outcome = success | failure | timeout_wait |
    refused_capacity | refused_admission}`;
  - counter `fv_validator_context_evictions_total{fhir_version, reason = lru | memory | invalidated | excluded}`;
  - counter `fv_validator_context_lookups_total{fhir_version, result = hit | joined | built}`, from which the hit
    ratio is computed;
  - a new validation outcome `package_not_found`, next to `unknown_profile`.

## R13. Migration and upgrade

- **Decision**: `V7__ig_scoped_contexts.sql`:
  - creates `fhir_package_catalog (id SMALLINT PRIMARY KEY CHECK (id = 1), revision BIGINT NOT NULL, updated_at
    TIMESTAMPTZ)` and seeds it with revision 1;
  - drops `fhir_validator_logs.resolved_ig_packages`;
  - leaves `included_ig_packages` unchanged, because each entry becomes a pinned context.

  It runs cleanly both on an empty database and on top of the 2.0.0 schema (quality gate). The service version
  becomes **3.0.0**.
- **Upgrade guide (`docs/upgrade-3.0.md`)**:
  - requests without `ig` now use the core specification only; to keep the old result, name the IG;
  - include no longer replaces other versions, so use the new exclude operation;
  - the `/health` detail shape has changed;
  - stop every 2.x instance before starting 3.0. A 2.x instance would read the missing `resolved_ig_packages`
    column and fail, so a mixed fleet isn't supported;
  - a database backup is the rollback path, as for 2.0.
- **Alternatives considered**: Keeping `resolved_ig_packages` for rollback was rejected. A 2.x instance would read a
  stale combined set that 3.0 no longer maintains, which is worse than a clear failure.

## R14. Performance and memory impact (Principle VI)

- **Hot path for a built context**: one in-memory view lookup plus one registry map lookup per request, which is
  negligible against p95 under 200 ms. The PR includes a benchmark comparing the current release with this branch
  for single-resource validation, without `ig` and with a pinned `ig` (SC-007).
- **Memory**: each context holds its own validation support chain. Parsed `NpmPackage` objects are shared through
  `PackageCache` (keyed by `name#version`, bounded by `FV_PACKAGE_CACHE_MAX_MB`). Total use is bounded by the heap
  check (R7) and `FV_IG_MAX_ON_DEMAND` (R8). Before, IGs that shared dependencies shared one chain; now each pinned
  IG pays for its own dependencies. This is documented in the upgrade guide's sizing note.
- **First request for an unbuilt context**: build time (seconds to tens of seconds for large IGs) plus validation,
  capped by `FV_IG_BUILD_WAIT_MS`.
