# Feature Specification: IG-Scoped Validation (Per-IG Validator Snapshots, Registry-Resolved "latest")

**Feature Branch**: `004-ig-scoped-validation`

**Created**: 2026-11-06

**Status**: Implemented (released in v3.0.0, 2026-11-06; convergence fixes in #47 and #48)

**Input**: User description: "@var/specify_instruction_ig_scoped_validation.md". In summary: let a client choose,
on each validation request, the one Implementation Guide (`name#version`) the resource is validated against, the
way `validator_cli.jar -version 4.0.1 -ig hl7.fhir.us.core#6.1.0` does. Replace the single shared validator
configuration per FHIR version with one validator snapshot per (IG package, FHIR version) plus a core-only base
snapshot per FHIR version. Make `POST /igs/register` with "latest" resolve against the package registry rather than
the highest version already stored.

**Background**: `specs/001-service-hardening/spec.md` (global per-version configuration, revisions, propagation,
heap admission) and `specs/003-ig-only-profile-resolution/spec.md` (profiles only from included IGs, `unknown-profile`).

## Context

The reference validator CLI validates each run against exactly one set of conformance content: the core
specification, the IG named with `-ig`, and that IG's dependencies. Separate runs can use different IGs, or
different versions of the same IG, and don't affect each other.

Today the service keeps **one** validator configuration per FHIR version, built from every IG that has been
included for that version. Including an IG *replaces* any other version of the same package, so US Core 6.1.0 and
7.0.0 can't both be offered for R4. Every included IG also affects every request for that version, so including an
IG for one consumer silently changes results for all the others.

Separately, registering a package by name with "latest" only asks the package registry when no version of that
package is stored yet. Once any version is stored, "latest" never notices a newer release.

This feature makes the validation context a per-request choice, keeps several IG versions available side by side,
and makes "latest" on registration mean the registry's latest release.

## Clarifications

### Session 2026-11-06

- Q: When a validation request names no IG, what is the resource validated against? → A: The core specification
  only (plus `hl7.terminology` and `hl7.fhir.uv.extensions`). This is a breaking change shipped as 3.0.0 with an
  upgrade note. There is no compatibility setting that restores the combined set. *Correction (planning,
  2026-11-06): the service doesn't load those two packages today. With no IG, a validator has only the built-in core
  definitions and built-in terminology support. The base context is exactly that, and it loads no package (see
  research.md R1).*
- Q: May a request name any stored IG package in `ig`, or only packages included for that FHIR version? → A: Any
  stored package that supports the FHIR version. Included packages are pre-built and pinned; any other stored
  package is built on demand at first use and may be evicted. This supersedes spec 003 FR-004. There is no setting
  to restrict requests to included packages.
- Q: When an unbuilt snapshot is requested and the memory budget is full, what happens? → A: Idle on-demand
  snapshots are evicted at request time, least recently used first, and then the snapshot is built. Pinned
  snapshots (base and included) are never evicted. If it still doesn't fit, the answer is 503 with a retry hint. If
  the snapshot alone exceeds the whole budget, the answer is 413 `package-too-large`.
- Q: Does this feature include a way to remove an included IG version? → A: Yes. An administrative exclude
  operation removes `name#version` from the included set for a FHIR version, propagates like inclusion and releases
  the pinned snapshot on every instance. The package can still be named in `ig` and is then built on demand. There
  is no deny list.
- Q: When `ig` names a package without an exact version (`name` or `name#latest`), is it rejected or resolved? →
  A: It is resolved to the highest version stored, the same rule inclusion uses. The FHIR version check then applies
  to that version, and the response reports the concrete `name#version` used.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Validate against the IG I name on the request (Priority: P1)

An API consumer validates a resource and names the IG package and version their exchange is governed by, for
example `hl7.fhir.us.core#6.1.0`. The resource is validated against the core specification, that IG and its
dependencies, and nothing else. The response says which IG package was used. If the consumer names no IG, the
resource is validated against the core specification only.

**Why this priority**: This is the core capability. It gives consumers the CLI's `-ig` semantics per request and
removes cross-consumer interference. It delivers value on its own even before side-by-side inclusion or the
registration fix.

**Independent Test**: Store two different IG packages, A and B, for R4. Validate one fixture that violates a
profile from A and one that violates a profile from B, each with `ig=A`, with `ig=B` and without `ig`. Confirm
that each result reflects only the named IG's content (or only the core specification), and that the response
names the IG used.

**Acceptance Scenarios**:

1. **Given** IG package `P#v` is stored and supports R4, **When** a consumer validates an R4 resource naming
   `ig=P#v`, **Then** the resource is validated against the core specification, `P#v` and its resolved
   dependencies only, and the response identifies `P#v` as the validation context.
2. **Given** no `ig` is named, **When** a consumer validates an R4 resource, **Then** it is validated against the
   base context (the built-in core specification definitions and terminology support, no IG package), no
   included IG contributes, and the response says the base context was used.
3. **Given** `P#v` is stored but no instance has validated against it yet, **When** the first request names it,
   **Then** the service prepares the context from stored packages only, then validates, and later requests naming
   `P#v` reuse the prepared context without preparing it again.
4. **Given** N requests naming the same unprepared `P#v` arrive at the same time on one instance, **When** they
   are served, **Then** the context is prepared exactly once and all N requests are validated with it.
5. **Given** `P#v` is not stored, **When** a consumer names it, **Then** the request is rejected as
   `package-not-found` (HTTP 404) naming the package and telling the client to register or upload it first, and
   no validation, preparation or download happens.
6. **Given** `P#v` declares FHIR versions that do not include the version in the request path, **When** a consumer
   names it, **Then** the request is rejected as `invalid-request` (HTTP 400), as including it for that version
   would be.
7. **Given** a malformed `ig` value (empty parts, more than one `#`, illegal characters or too long), **When** it
   is sent, **Then** the request is rejected as `invalid-request` before any lookup.
10. **Given** `P#1` and `P#2` are stored and support R4, **When** a consumer names `ig=P` or `ig=P#latest`, **Then**
    the resource is validated with the `P#2` context, and the response reports `P#2` as the context used.
8. **Given** `ig=P#v` and `profile=X`, **When** X is defined by `P#v`, its dependencies or the core
   specification, **Then** X is applied; **When** X is defined only by another IG (even one that is included),
   **Then** the request is rejected as `unknown-profile` (spec 003 rules, scoped to the chosen context).
9. **Given** no `ig` and `profile=X` where X is defined only by an IG, **When** the request is sent, **Then** it
   is rejected as `unknown-profile`.

---

### User Story 2 - Offer several IG versions side by side (Priority: P1)

A conformance administrator includes US Core 6.1.0 and later US Core 7.0.0 for R4 during a migration. Including
7.0.0 adds a second prepared context and leaves 6.1.0 untouched, so consumers on either version keep working and
choose theirs per request. Included contexts are prepared on every instance ahead of time, so the first consumer
request does not pay the preparation cost, and they are never evicted.

**Why this priority**: Side-by-side versions are the main operational reason for the feature, and pre-preparing
keeps the existing include workflow meaningful.

**Independent Test**: Include `hl7.fhir.us.core#6.1.0` for R4, then `#7.0.0`. Confirm the first context was not
rebuilt and stayed active, that both are listed in health details, and that validation with each `ig` reflects
only that version's profiles, on two instances.

**Acceptance Scenarios**:

1. **Given** `P#1` is included for R4, **When** the administrator includes `P#2` for R4, **Then** a separate
   context for `P#2` is added, the `P#1` context is not rebuilt and stays active, and both can be named per
   request.
2. **Given** an inclusion is accepted, **When** it has been committed, **Then** the context is prepared on every
   instance within the existing propagation window (10 seconds, spec 001 SC-002), and the include response reports
   the context key, its resolved package list and the configuration revision.
3. **Given** two IGs included for the same FHIR version, **When** a request names one of them, **Then** no
   definition from the other IG is visible to that validation.
4. **Given** an inclusion whose context would not fit in the instance's memory budget, **When** it is requested,
   **Then** it is refused as `package-too-large` (HTTP 413) before anything is committed.
5. **Given** an include request with no version, or `latest`, **When** it is processed, **Then** it uses the
   highest version already stored, and nothing is downloaded.
6. **Given** an included `P#v`, **When** the administrator excludes it for that FHIR version, **Then** it is
   removed from the shared configuration, the pinned context is released on every instance within the propagation
   window, and later requests naming `P#v` fall back to on-demand preparation like any other stored package.
7. **Given** the service's health details, **When** an operator reads them, **Then** every prepared context per
   FHIR version is listed with its key, state, whether it is pinned (included or base) or on-demand, its resolved
   packages and its package size.

---

### User Story 3 - "latest" on registration means the registry's latest release (Priority: P2)

An administrator registers a package by name only, or with version `latest`. The service asks the configured
package registries for the highest released version. If that version is already stored, it is returned without
downloading it again. If it is new, it is downloaded and stored with its dependencies. The response says which
concrete version was resolved and whether it was downloaded or already stored.

**Why this priority**: It fixes a defect that leaves deployments silently behind the newest release, but it is
independent of the validation-context changes.

**Independent Test**: With a registry stub offering `P#1` and `P#2` and only `P#1` stored, register `P` with
`latest`, then again; then disable the registry and register again.

**Acceptance Scenarios**:

1. **Given** the registry's latest release of `P` is `P#2` and only `P#1` is stored, **When** `P` is registered
   with `latest` or no version, **Then** `P#2` and its dependencies are downloaded and stored, and the response
   reports `P#2` as newly downloaded.
2. **Given** the registry's latest release `P#2` is already stored, **When** `P` is registered with `latest`,
   **Then** the stored package is returned, the archive is not downloaded again, and the response reports `P#2`
   as already stored.
3. **Given** no registry is reachable, or registries are disabled, and some version of `P` is stored, **When** `P`
   is registered with `latest`, **Then** the highest stored version is returned with a warning that the registry
   could not be consulted.
4. **Given** no registry is reachable or registries are disabled and no version of `P` is stored, **When** `P` is
   registered with `latest`, **Then** the request fails as `package-not-found` (HTTP 404).
5. **Given** an exact version, **When** `P#v` is registered, **Then** behaviour is unchanged: a stored version is
   returned as is, and a missing one is downloaded.

---

### User Story 4 - Operators upgrade with predictable behaviour (Priority: P2)

An operator upgrading from 2.0.0 reads the changelog and upgrade guide, learns that requests without `ig` now use
the core specification only, and that every IG previously included for a version becomes its own pinned context.
After the upgrade, every previously included IG is still prepared and can be named per request.

**Why this priority**: The default changes for existing consumers, so the upgrade must be explicit and lossless,
but it is a one-time step around the feature.

**Independent Test**: Start from a 2.0.0 database with two IGs included for R4. Upgrade, then confirm both are
pinned contexts, both can be named per request, a request without `ig` uses the base context, and the changelog
and upgrade guide describe the change under a new major version.

**Acceptance Scenarios**:

1. **Given** a 2.0.0 database with IGs included for one or more FHIR versions, **When** the service is upgraded,
   **Then** each previously included IG becomes a pinned context for its FHIR version, and no stored package,
   API key or configuration revision history is lost.
2. **Given** the changelog and upgrade guide, **When** an operator reads them before upgrading, **Then** they
   state under a new major version that requests without `ig` move from "all included IGs" to "core only", and
   how consumers keep the old result by naming the IG.

---

### Edge Cases

- **Same package name, different versions in one context**: An IG's dependencies pull in a different version of a
  package that is also included separately. Each context resolves its own dependency set independently; contexts
  never share or overwrite each other's packages.
- **Stored package replaced**: A package with the same `name#version` as a stored one is uploaded again. Every
  context that contains it (as the chosen IG or as a dependency), pinned or on-demand, is marked stale on every
  instance through the existing propagation, and is rebuilt (pinned) or dropped and re-prepared on next use
  (on-demand).
- **Context not ready or stale**: The named context is being prepared beyond the request's preparation wait, or
  is stale. The request is refused as "not ready" (HTTP 503 with a retry hint), per context, exactly as today's
  per-version behaviour; other contexts keep serving.
- **Preparation fails**: Preparing an on-demand context fails (for example a dependency is not stored). The
  request is rejected with HTTP 424 `dependency-not-found`, naming the missing package and telling the client to
  register or upload it. Any other preparation failure gets 503 `not-ready` with a retry hint. Causes stay in the
  log. A failed preparation is not cached as a permanent failure: a later request tries again.
- **Preparation exceeds the wait**: The first request for a context waits up to a configurable preparation wait.
  If preparation takes longer, that request gets "not ready" with a retry hint, and preparation continues so a
  retry finds it ready.
- **Memory pressure on demand**: An on-demand context does not fit in the memory budget. Idle on-demand contexts
  are evicted least-recently-used first to make room; pinned contexts are never evicted. If it still does not fit
  only because pinned contexts use the budget, the request is refused as "not ready / insufficient capacity" (503
  with a retry hint). If the context alone is larger than the whole budget, it is refused as `package-too-large`
  (413).
- **Burst of distinct IGs**: Many requests name different unprepared IGs at once. Preparations count against the
  existing administrative/package admission limits; requests over the limit are refused as overloaded (503 with a
  retry hint) instead of queueing without bound.
- **Too many on-demand contexts**: The configurable maximum number of on-demand contexts per instance is reached.
  The least-recently-used idle on-demand context is evicted before a new one is prepared.
- **In-flight change**: A context is replaced or released while a request is validating with it. The request
  completes with the context it started with; it is never partly old and partly new.
- **Package lists several FHIR versions**: A package declares, for example, both R4 and R4B. It can be named on
  either path; each FHIR version gets its own context.
- **URL encoding**: `#` in the query value must be encoded (`%23`). An unencoded `#` truncates the value at the
  client, so the server sees a bare name and resolves it to the highest stored version (FR-002). The response
  reports the version actually used, so clients can detect the mistake; the API description warns about it.
- **Versionless `ig` after a new version is stored**: A newer version of `P` is registered or uploaded. Once that
  change has propagated (within the existing 10-second window), versionless requests for `P` resolve to the new
  version and build its context on demand. Contexts for older versions are not rebuilt and are evicted as usual.
- **Highest stored version doesn't support the FHIR version**: `ig=P` resolves to the highest stored version of
  `P`, and if that version doesn't declare the path's FHIR version, the request is rejected as `invalid-request`
  (FR-004). It doesn't fall back to an older version; the error names the resolved version.
- **Registry newest is lower than stored**: The registry's latest release is lower than a version already stored
  (for example a locally uploaded pre-release). Registration with `latest` returns the registry's latest release
  (downloading it if missing), and the response says which version that is.
- **Package unknown to the registry but stored**: The registry is reachable but does not list `P` (for example a
  locally uploaded package). Registration with `latest` falls back to the highest stored version with a warning.

## Requirements *(mandatory)*

### Functional Requirements

**Choosing the validation context**

- **FR-001**: A validation request MUST accept an optional `ig` parameter whose value is an IG package identity:
  `name#version`, `name#latest` or a bare `name`. The validation context is the one for the resolved package
  version and the FHIR version in the request path.
- **FR-002**: The `ig` value MUST be checked for shape before any lookup: a non-empty package name, optionally
  followed by a single `#` and a non-empty version, package-name and version characters only, at most 256
  characters in total. A malformed value MUST be rejected as `invalid-request` (HTTP 400). A bare `name` or
  `name#latest` MUST be resolved to the highest version of that package stored, the same rule inclusion uses
  (FR-022); the FHIR version check (FR-004) then applies to the resolved version. Resolving a versionless name MUST
  NOT wait on the database when the resolved context is already built; what "highest stored" means MUST be
  refreshed on every instance when packages are stored or replaced, through the existing propagation.
- **FR-003**: When `ig` names a package that is not stored, the request MUST be rejected as `package-not-found`
  (HTTP 404) in the standard problem-details format, naming the package and telling the client to register or
  upload it first. Nothing is validated, prepared or downloaded.
- **FR-004**: When `ig` names a package whose declared FHIR versions do not include the path's version, the
  request MUST be rejected as `invalid-request` (HTTP 400), using the same rule as inclusion.
- **FR-005**: When `ig` is absent, the request MUST be validated with the base context for the FHIR version: the
  built-in core specification definitions and terminology support that every validator has today, and no IG package
  at all.
- **FR-006**: Any stored package that supports the path's FHIR version MAY be named in `ig`, whether or not it is
  included. Naming it MUST make only that package, its resolved dependencies and the base content available to
  that request. This supersedes spec 003 FR-004: "included" now means "pre-prepared and pinned", not "the only
  packages that may contribute profiles".
- **FR-007**: Requested profiles (`profile`) MUST follow the spec 003 rules, scoped to the chosen context: a
  profile resolves only from that context's packages. An unresolved requested profile MUST still be rejected as
  `unknown-profile` (HTTP 400), with the spec 003 limits (10 profiles, 1024 characters) and `appliedProfiles`
  truthfulness unchanged. Profiles are never fetched from the network.
- **FR-008**: The validation response MUST identify the context used: the concrete IG package `name#version`
  (the resolved version when the request named none or `latest`), or that the base context was used. The OperationOutcome form MUST carry the same information where the format allows.
- **FR-009**: A request's checks MUST run in this order, and the first failure wins: request shape (FHIR version,
  content type, accepted response format, `ig` shape, profile limits, empty body); then validation admission
  (overload); then the `ig` package lookup and version resolution (FR-002, FR-003, FR-004); then context
  readiness and preparation (the request holds its validation admission slot while it waits); then parsing; then
  the Bundle entry limit; then unresolved requested profiles; then validation.

**Preparing contexts**

- **FR-010**: When a named context is not yet prepared on an instance, the instance MUST prepare it from stored
  packages only (the package and its dependencies, resolved by the same rules inclusion uses), register it, and
  then validate. It MUST NOT contact a package registry while a request waits.
- **FR-011**: Concurrent requests for the same unprepared context on one instance MUST share a single preparation.
- **FR-012**: A request MUST wait for an on-demand preparation for at most a configurable preparation wait. On
  timeout it MUST be refused as "not ready" (HTTP 503 with a retry hint) while preparation continues.
- **FR-013**: For an already prepared context, validation MUST NOT wait on the database or the network.
  Preparation and package lookups MUST NOT run on request-dispatch threads.
- **FR-014**: On-demand preparations MUST count against the existing administrative/package admission limits and
  the memory budget. Requests that would exceed admission MUST be refused as overloaded (HTTP 503 with a retry
  hint).
- **FR-015**: On-demand contexts MUST be bounded by a configurable maximum count per instance and by the memory
  budget, and MUST be evicted least-recently-used first when either bound would be exceeded. Pinned contexts (the
  base context and included IGs) MUST never be evicted.
- **FR-016**: When an on-demand context cannot fit after evicting all idle on-demand contexts, the request MUST be
  refused with HTTP 503 and a retry hint. When the context alone exceeds the whole memory budget, it MUST be refused
  as `package-too-large` (HTTP 413).
- **FR-017**: A failed preparation MUST return a curated client-facing message (causes only in the log). When a
  dependency of the chosen package isn't stored, the request MUST be rejected with HTTP 424,
  `dependency-not-found`, naming the missing `name#version` and telling the client to register or upload it.
  Any other preparation failure MUST be answered 503 `not-ready` with a retry hint. Failures MUST NOT be cached
  as permanent: a later request tries again.
- **FR-018**: "Not ready" (being prepared, stale) MUST be tracked per context. One context's state MUST NOT affect
  requests for another context.

**Including and excluding IGs**

- **FR-019**: The shared configuration MUST record, per FHIR version, a set of included IG package identities.
  Including `P#v2` when `P#v1` is included MUST add `P#v2` and MUST NOT remove, replace or rebuild `P#v1`.
- **FR-020**: Including an IG MUST keep the spec 001 guarantees: committed in one transaction, propagated to every
  instance (revision counter, change notification, poll fallback) within 10 seconds, refused as `package-too-large`
  (HTTP 413) before commit if the context would not fit, and answered after the local apply or after the local
  apply wait with a warning.
- **FR-021**: Each included IG MUST be prepared as its own pinned context on every instance, containing only that
  IG, its dependencies and the base content.
- **FR-022**: Inclusion with no version or `latest` MUST resolve to the highest version already stored and MUST
  NOT download anything.
- **FR-023**: The include response MUST report the context key (`name#version`, FHIR version), its resolved
  package list and the configuration revision.
- **FR-024**: Administrators MUST be able to exclude an included IG version for a FHIR version. Exclusion MUST be
  committed and propagated like inclusion, MUST release the pinned context on every instance, and MUST NOT affect
  any other context. Excluding an IG that is not included MUST be answered as `package-not-found` (HTTP 404).
  Exclusion MUST NOT block the package from being named in `ig`; it is then built on demand like any other stored
  package.
- **FR-025**: Replacing a stored package (same `name#version`) MUST mark every context that contains it stale on
  every instance through the existing propagation. Pinned contexts MUST be rebuilt; on-demand contexts MUST be
  dropped and prepared again on next use.
- **FR-026**: Include and exclude MUST remain administrative operations under the existing administrator key and
  admission rules.

**Registration "latest"**

- **FR-027**: Registering a package with no version or `latest` MUST ask the configured package registries for
  the highest released version, using the existing selection rule (highest by package version ordering, with the
  registry's `latest` tag only as a fallback).
- **FR-028**: If that version is already stored, the stored package MUST be returned without downloading the
  archive again. If it is not stored, it MUST be downloaded and stored with its dependencies (recursively, skipping
  core packages, honouring the existing dependency-inclusion option).
- **FR-029**: If no registry is reachable, registries are disabled, or no registry lists the package, the highest
  stored version MUST be returned with a warning. If none is stored, the request MUST fail as `package-not-found`
  (HTTP 404).
- **FR-030**: The registration response MUST report the concrete version resolved and whether it was newly
  downloaded or already stored.
- **FR-031**: Registration with an exact version MUST behave as today. Only registration may contact a package
  registry; inclusion, exclusion and validation MUST NOT.

**Observability, contract and upgrade**

- **FR-032**: Health details MUST list every prepared context per FHIR version with its key, state, pinned or
  on-demand, resolved packages and package size. Readiness MUST reflect the base and pinned contexts only; an
  on-demand context's state MUST NOT make the instance unready.
- **FR-033**: Metrics MUST expose, per FHIR version, the number of prepared contexts (pinned and on-demand),
  on-demand preparations (by outcome), evictions, and context reuse versus preparation (hit ratio). Validation
  metrics MUST count `package-not-found` rejections under their own outcome, not as server errors.
- **FR-034**: The published API description MUST document the `ig` parameter, the context identification in the
  response, the exclusion operation, the changed include and register responses, and every new error case, before
  or with the implementation.
- **FR-035**: Upgrading from 2.0.0 MUST convert the stored configuration so that every previously included IG
  becomes an included (pinned) context for its FHIR version, without losing stored packages, API keys or
  revision history. The schema change MUST go through a new versioned migration.
- **FR-036**: The change of default for requests without `ig` MUST ship under a new major version (3.0.0) with a
  changelog entry and an upgrade note that tells consumers how to keep the previous result by naming the IG.
- **FR-037**: The supported FHIR versions (STU3, R4, R4B, R5) MUST be the same on every endpoint touched by this
  feature, and client-facing errors MUST use the existing problem-details format with curated text only.

### Key Entities

- **Validation context (snapshot)**: The prepared validator for one key. Attributes: key, state (preparing,
  active, stale, failed), pinned or on-demand, resolved package list, package size, last used time.
- **Context key**: (FHIR version, IG package identity `name#version`), or (FHIR version, base) for the base
  context.
- **Base context**: Per FHIR version, the built-in core specification and terminology support, with no IG package.
  Always pinned.
- **Included IG set**: Per FHIR version, the IG package identities that are pre-prepared and pinned on every
  instance. Part of the shared, revisioned configuration.
- **Stored IG package**: A package `name#version` held in the shared package store, with its declared FHIR
  versions and dependencies. The only source a context is prepared from.
- **Registration result**: The concrete version resolved, whether it was downloaded or already stored, and any
  warning (for example registry not consulted).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In one deployment, R4 requests naming `hl7.fhir.us.core#6.1.0` and `hl7.fhir.us.core#7.0.0` both
  succeed, and for a fixture that differs between the two versions' profiles, each result reflects only the named
  version (verified by fixtures with known expected outcomes).
- **SC-002**: A request without `ig` reports zero issues that come only from IG-specific profiles, for every
  supported FHIR version.
- **SC-003**: For 50 concurrent first requests naming the same stored, unprepared IG, exactly 1 preparation
  happens and all 50 requests complete with the same result.
- **SC-004**: A request naming an IG that is not stored is rejected with `package-not-found` in under 1 second,
  and zero preparations or downloads are recorded.
- **SC-005**: Including a second version of an IG causes zero rebuilds of the first version's context, which
  stays active throughout.
- **SC-006**: An included or excluded IG takes effect on a second instance within 10 seconds (spec 001 SC-002).
- **SC-007**: Validation against an already prepared context keeps the existing target of p95 under 200 ms for a
  single resource; the feature adds no measurable latency to that path (benchmark in the PR).
- **SC-008**: Memory used by contexts never exceeds the configured budget, and the number of on-demand contexts
  never exceeds the configured maximum, under a burst of requests naming 20 distinct stored IGs.
- **SC-009**: Registering a package with `latest` downloads the newer release when the registry has one, and
  transfers zero archive bytes when the stored version is already the registry's latest.
- **SC-010**: After upgrading a 2.0.0 database, 100% of previously included IGs are available as pinned contexts
  without any administrator action.
- **SC-011**: Regression tests cover: base context without `ig`; an unknown `ig`; a malformed `ig`; an `ig` for
  the wrong FHIR version; `profile` scoped to the chosen IG; two IG versions side by side; on-demand preparation
  sharing; versionless `ig` resolution; memory refusal and eviction; exclusion; replaced-package invalidation; and `register` latest with
  stored-latest, newer-in-registry and registry-down cases.

## Assumptions

The input's open questions Q1, Q2, Q4, Q5 and Q7 are settled under Clarifications. These defaults cover the rest.

- **Unknown `ig` status (input Q3)**: 404 `package-not-found`, consistent with inclusion.
- **Lifecycle limits**: the default maximum number of on-demand contexts per instance is set in planning
  (eviction behaviour is fixed by the clarifications).
- **Several IGs per request (input Q6)**: out of scope. One `ig` per request; a repeated or comma-separated value
  is `invalid-request`. Consumers needing several IGs package a combining IG that depends on them.
- **Registration without a registry (input Q8)**: fall back to the highest stored version with a warning; 404 when
  nothing is stored.
- **Preparation wait**: a configurable bound (default set in planning) on how long a request waits for an
  on-demand preparation, so requests never wait indefinitely.
- **Memory impact**: each context holds its own copy of its packages' definitions. Memory grows with the number of
  distinct prepared contexts, which FR-014 to FR-016 bound. Sharing definitions between contexts is not required.
- **Cross-instance consistency**: pinned contexts follow the shared, revisioned configuration and its existing
  propagation. On-demand contexts are derived per instance from stored packages, which are the source of truth;
  replacing a stored package invalidates them through the same propagation (FR-025). No new shared infrastructure
  is introduced.
- **Dependencies**: relies on the spec 001 configuration, propagation, heap admission and retry machinery, and on
  the spec 003 profile resolution rules.
