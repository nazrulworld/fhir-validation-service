# Research: IG Version Series (`1.2.x`, `1.x.x`) on Register and Include

**Feature**: `006-ig-version-series` | **Date**: 2026-11-08 | **Spec**: [spec.md](./spec.md)

The Technical Context had no open unknowns: the stack, storage, test approach and endpoints are all existing ones.
These notes record the design decisions, based on the current code.

## R1. Where the series logic lives

**Decision**: Add a new pure value class, `nzi.fhir.validator.core.npm.VersionSeries`, next to
`PackageVersionComparator`. It has:

- `static boolean isSeries(String version)`: true when any dot-separated part is `x` or `X`.
- `static VersionSeries parse(String version)`: throws `ProblemException` 400 `invalid-request` for a malformed
  series (FR-002).
- `boolean matches(String version)`.
- `Optional<String> highest(Collection<String> candidates)`: filters with `matches`, then calls
  `PackageVersionComparator.max`.

`PackageVersionComparator` gets one small static accessor, `releaseParts(String) → Optional<BigInteger[]>`, that
reuses its existing `SEMVER` pattern and `parse`. That way matching and ordering use the same parser.

**Rationale**: Registration, include and the `?ig=` check all need the same detection and matching. A pure class
can be unit-tested exhaustively without I/O (constitution V). Reusing the comparator's parser keeps "matches" and
"highest" consistent. For example, `1.2` parses to `[1,2,0]`, so it matches `1.2.x`. A non-SemVer label parses to
nothing, so it never matches (FR-003).

**Alternatives considered**:

- SQL `LIKE '1.2.%'` on the packages table: rejected. It also matches `1.20.0` with an extra dot pattern, it can't
  express "release preferred", and it duplicates the ordering in SQL.
- A regex built from the series: rejected. It's harder to get right for `1.2` versus `1.2.0` and for pre-releases,
  and it gives no ordering.

## R2. Series syntax and detection

**Decision**:

- A value is a series when any dot-separated part equals `x` or `X` (case-insensitive). Then `parse` checks the
  grammar `^\d+\.[xX](\.[xX])?$|^\d+\.\d+\.[xX]$` (at most three parts in total). That accepts `1.x`, `1.x.x` and
  `1.2.x`.
- It rejects (400) `x`, `x.x.x`, `1.x.3`, `1.2.x.y`, `1.2.3.x` and `1.2.x.x`. The last is four parts, more than
  the three-part maximum.
- A value with no `x` part keeps today's handling: an exact version or `latest`.
- The 400 message is "Version series must be N.x, N.x.x or N.N.x (for example 1.2.x)".

**Rationale**: Detection by "any part is `x`" means malformed series such as `1.x.3` get a clear 400 (spec
US4). They don't fall through as a literal version and come back as 404. No real FHIR package version has a part
that is exactly `x`, so exact versions can't be misread.

**Alternatives considered**:

- Accept `*`: rejected (spec Assumptions).
- Treat a bare `1.2` as `1.2.x`: rejected, because it would change the meaning of existing exact-version requests.

## R3. Registration with a series: the registry lookup

**Decision**: Refactor `PostgresPackageCacheManager` so that its per-registry fetch (today inlined in
`resolveForRegistration`) becomes a private `fetchListings(name)` → `Future<List<Listing>>`. The `Listing` record
gets the full version list: `versions.fieldNames()`, or `dist-tags.latest` when the listing has no version map.

Add a public `resolveSeriesFromRegistries(String name, VersionSeries series)` →
`Future<SeriesRegistryResult(String version, String warning)>`:

- At least one registry lists a matching version: `version` is the highest match across every registry that
  answered, and `warning` is `null`.
- Registries are disabled, every registry failed, or none lists a matching version: `version` is `null`, and
  `warning` names the case:
  - `"No package registry could be consulted; using the highest stored version in series %s"`
  - `"No package registry lists a version in series %s; using the highest stored version"`

`resolveForRegistration` (for `latest`) keeps its behaviour and is rebuilt on `fetchListings`.

**Rationale**: The fetch, the SSRF-safe fetcher and the error classification are shared. Only the final selection
differs. Keeping the `latest` path's results identical satisfies FR-016/SC-006, and `RegisterLatestIT` guards it.

**Alternatives considered**: a second, separate fetch loop. Rejected because it would duplicate the
`ListingOutcome` classification and the logging.

## R4. Registration with a series: stored fallback and access

**Decision**: The stored fallback for a series is done in `IgPackageService.register`, not in the cache manager,
because it needs the caller (FR-009: "the caller may use").

Add a new `static Future<Map<String, Access>> storedVersionsWithAccess(SqlClient, String name)`. It's one query,
the same join as `packageAccess` without the version predicate. The fallback is
`series.highest(versions filtered by AccessPolicy.canUse)`, plus the warning from R3. If nothing matches, the
answer is 404 `package-not-found`: "IG package {name} has no version in series {series} in the configured package
registries and none is stored".

Once a concrete version is chosen, the existing register code path runs unchanged:

- stored and not usable → 404;
- stored and usable → returned with `downloaded=false`;
- not stored → `loadPackage(key, false, includeDependency, ownership)`.

**Rationale**: It reuses the spec 005 access model, so a private version of another organisation is never chosen
in the fallback (SC-007). When the registry picks a version that another organisation has stored privately, the
answer is the same 404 as today (US3 scenario 3).

**Note**: In 3.2.0, `latest`'s fallback picked the highest stored version without checking access, then answered
404 when it wasn't usable. 3.2.1 aligns it with the series fallback: see R11 (FR-023).

## R5. Include with a series

**Decision**: In `IgPackageService.include(Caller, …)`, a series is checked up front: `VersionSeries.isSeries`,
then `parse`, which gives 400 when malformed. The series check comes before the existing `isValidVersionLabel`
check.

Inside the transaction, `concrete` is computed from the same `visible` list used for `latest`, which is already
filtered by `AccessPolicy.canUse`, so: `concrete = series.highest(visible)`.

When nothing matches: 404 `package-not-found`, labelled `name#1.2.x`, with the existing "Register it first via
/igs/upload or /igs/register" hint.

Everything after `concrete` is unchanged: `checkFhirVersion`, lock, dependency resolution, heap admission,
revision bump, propagation. An incompatible highest match fails without trying lower matches (FR-015).

**Rationale**: It's a one-line change at the resolution point, and every guarantee from specs 001/004/005
follows automatically (FR-013). Only the concrete key is written to the configuration (FR-014). That's already
true, because the configuration stores keys, not requests.

## R6. `?ig=` on validation (clarification Q1 → A)

**Decision**: In `IgReference.parse`, after splitting `name#version`, a version where `VersionSeries.isSeries` is
true is rejected with 400 `invalid-request`: "The ig parameter needs an exact version (for example the version
reported by include-ig), not a version series".

**Rationale**: `IgReference.parse` already runs before the body is read and before any lookup (spec 004 FR-009),
so no snapshot lookup or build happens (FR-018). Today `1.2.x` matches the `VERSION` pattern and leads to 404. The
new answer is 400 with a clear message.

## R7. Reporting the requested version

**Decision**: Add an optional `requestedVersion` string to `IgRegistrationResponse` and `IncludeResponse`. It
echoes the version from the request (after defaulting to `latest`). It's set at the API layer:
`IgPackageApi.handleRegisterIg` and `ValidationApi.handleIncludeImplementationGuide` add it to the service result.
The concrete version stays where it is today: `version` for registration, `key.igPackage` for include.

**Rationale**: FR-010 asks for the requested series in the response. Setting the field in the handlers keeps the
service signatures and the existing include response paths (already included, newly included, applied, still
applying) unchanged. The field is additive (FR-017).

**Alternatives considered**: a `series` field only present for a series. Rejected because one always-present
field is simpler for clients.

## R8. Versioning and changelog

**Decision**: Minor release, 3.2.0. The CHANGELOG `[Unreleased]` section gets:

- **Added**: version series on register/include, and `requestedVersion`.
- **Changed**: `?ig=` with a series is now 400 instead of 404.

**Rationale**: Constitution IV: additive contract changes are minor. The `?ig=` status change affects only
requests that could never succeed: a 4xx becomes a different, clearer 4xx, with no path or payload changes. It's
recorded as a behaviour change, not treated as breaking. See the Constitution Check in plan.md.

## R9. Tests

**Decision**:

- **Unit**: `VersionSeriesTest`, covering grammar, matching and highest, including `1.20.0`, `1.2`, pre-releases,
  non-SemVer labels and case.
- **Unit**: additions to `IgReferenceTest` for a series in `?ig=`.
- **Integration (Testcontainers)**:
  - `RegisterSeriesIT`: `StubRegistry` + `AppHarness`, modelled on `RegisterLatestIT`.
  - `IncludeSeriesIT`: modelled on `IncludeExcludeIT`.
  - Ownership cases added to `IncludeOwnershipIT` and `RegisterSeriesIT`.
- **Contract**: `IgPackageApiContractTest` and `ValidationApiContractTest` for the 400 responses and
  `requestedVersion`.

**Rationale**: Constitution V. Persistence and access paths run against real PostgreSQL, and the registry is
stubbed with the existing `StubRegistry`.

## R10. Dependencies declared with a series or `latest` (3.2.1, FR-019 to FR-022)

**Decision**: Fix the two places that resolve a dependency's declared version.

- **Registration walk** (`PostgresPackageCacheManager.loadPackage(…, visited, ownership)`): resolve each version
  with a new private `resolveDependencyVersion(name, requested, cacheOnly)` instead of `resolveVersion`.
  - Series → `VersionSeries.parse`. A malformed series is logged and skipped (`null`). Otherwise
    `resolveSeriesFromRegistries`, then `series.highest(stored versions)` when the registries can't decide.
  - `null`/`latest`/`current` → the registries' highest release (the same listing rule as top-level `latest`),
    then the highest stored version.
  - Exact → unchanged.
  - With `cacheOnly` (or registries disabled), only stored versions are used.
- **Context resolution** (`DependencyResolver.concrete`, used by include, on-demand contexts and snapshot builds via
  `ResolvedContext.of`): a series resolves to `series.highest(stored)`. A malformed series resolves to nothing, so
  the dependency is reported as missing under its literal label.

The public `resolveVersion` keeps its stored-first meaning: other callers and tests (`RegistryFallbackTest`,
`PackageLatestResolutionIT`, `PostgresPackageCacheManagerTest`) rely on it.

**Rationale**: Registration and context resolution must agree, or a dependency stored by registration would still be
"missing" at include. `DependencyResolver` isn't caller-aware on purpose: `ResolvedContext.of` runs on every instance
without a caller, and it must give the same package list everywhere (constitution II, III). The include's
existing per-dependency access check (`IgPackageService.include`) still runs on the result, so a private dependency
of another organisation is refused, as it is for `latest` dependencies today.

**Note**: Contexts already included whose series dependency was reported as missing resolve to a different package
list after the upgrade. The existing reconciliation (`ResolvedContext.sameAs`) rebuilds them. This is recorded in
the CHANGELOG.

**Alternatives considered**: a caller-filtered catalog in `DependencyResolver`. Rejected because snapshot builds on
other instances have no caller, so the context would differ between instances.

## R11. Access-aware `latest` fallback (3.2.1, FR-023)

**Decision**: `PostgresPackageCacheManager.resolveForRegistration(name)` returns a registry-only result,
`RegistryResult(version, warning)`, the same shape that series resolution uses (`SeriesRegistryResult` is renamed
`RegistryResult`). The stored fallback moves to `IgPackageService`, as one helper shared by `latest` and series:
`storedFallback(caller, name, pick, warning)`. It reads `storedVersionsWithAccess`, keeps the versions where
`AccessPolicy.canUse`, and applies `pick` (`PackageVersionComparator::max` for `latest`, `series::highest` for a
series). The warning texts and the 404 messages don't change.

**Rationale**: One fallback rule for both paths. A private version of another organisation is no longer a candidate,
so the caller gets their highest usable version instead of 404, and nothing about the private version leaks.

## R12. Versioning for the fixes

**Decision**: Patch release 3.2.1, CHANGELOG **Fixed**. No field, status code or path changes. Only the `version`
description in `openapi.yaml` is clarified ("the caller may use" for the `latest` fallback, and dependencies resolve
series).
