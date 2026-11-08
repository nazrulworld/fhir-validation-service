# Feature Specification: IG Version Series (`1.2.x`, `1.x.x`) on Register and Include

**Feature Branch**: `006-ig-version-series`

**Created**: 2026-11-07

**Status**: Draft

**Input**: User description: "@var/specify_instruction_ig_version_wildcard.md" (GitHub issue #56). In summary: let
clients ask for an IG package by version series instead of an exact version. `POST /igs/register` resolves a series
such as `1.2.x` against the versions listed by the package registries and picks the highest match.
`POST /{version}/include-ig` resolves a series against the versions already stored, and picks the highest match.

**Background**: `specs/004-ig-scoped-validation/spec.md` (registration resolves `latest` from the registries,
FR-027 to FR-031; one validator snapshot per IG package and FHIR version; include adds and never replaces) and
`specs/005-organisation-ownership/spec.md` (a caller only sees packages its organisation owns or that are shareable).

## Context

FHIR package dependencies are often declared as a version series: `1.2.x` means "any `1.2` patch release",
`1.x.x` means "any release of major version 1". Administrators and CI pipelines want to ask for an IG the same way,
so they get the newest compatible patch without first looking up which versions exist.

Today the service doesn't recognise a series. `1.2.x` passes the version label check (1-64 characters, no
whitespace) and is used as a literal version. Registration tries to download `name#1.2.x` from the registries and
fails with 404 `package-not-found`. Including `name#1.2.x` looks for a stored version labelled `1.2.x` and fails
with 404 too.

This feature resolves a series to one concrete version on both endpoints. Registration looks at the registries,
like `latest` does today. Include looks only at stored packages, like it does today. After resolution, everything
works on the concrete `name#version` exactly as before.

## Clarifications

### Session 2026-11-07

- Q: Should `?ig=` on `POST /{version}/validate` also accept a version series? → A: No. It's out of scope, and a
  series in `?ig=` is answered 400 `invalid-request` (FR-018) instead of today's 404 for a literal `1.2.x`.

### Session 2026-11-08

- Q: Is the `?ig=` status change for a version series (404 `package-not-found` → 400 `invalid-request`) a breaking
  change under constitution principle IV, requiring a major version? → A: No. It ships in a minor version (3.2.0).
  - **Why it isn't breaking:** the request could never succeed before. No package version is labelled `1.2.x`, so
    the 404 was always the result, and the path, the request and the success payload don't change. Only the
    reason a request that already failed is reported changes, to a clearer client error.
  - **Where it's recorded:** under "Changed" in the CHANGELOG, with the old and new status codes. A client that
    reacted to that 404 by registering the package would also have failed, because registering `1.2.x` as a
    literal version was itself a 404 before this feature.

### Session 2026-11-08 (2): follow-up fixes after 3.2.0

- Q: A package's manifest can declare a dependency as a series (`hl7.terminology.r4#1.0.x`). Registration fetched it
  literally and dropped it, and include reported it as "not stored". Is that in scope? → A: Yes, it's a defect.
  A dependency series resolves like a top-level series (US5, FR-019, FR-021, FR-022).
- Q: A dependency declared as `latest`, `current` or without a version resolved to the highest **stored** version
  during registration, while top-level `latest` asks the registries first. Should they match? → A: Yes. A dependency
  goes to the registries first, like top-level `latest` (FR-020).
- Q: The `latest` registration fallback picked the highest stored version without checking access, so a private
  version of another organisation caused a 404 although a usable lower version was stored. → A: A defect. The
  fallback uses only versions the caller may use, like the series fallback (FR-023, US3 scenario 4).
- Q: Dependency resolution picked the highest stored version regardless of who owns it. When that version was private
  to another organisation, include (and `?ig=` on demand) failed with 424, even though a lower usable version was
  stored. → A: A defect. A dependency declared without an explicit version is chosen only among versions the
  context's owner may use: shareable ones, or the owner's own (FR-024, US5 scenarios 6 and 7). Exact versions keep
  their meaning. Dependency versions stay unpinned, as spec 004 research R3 decided.
- These ship as a patch release (3.2.1). The contract shape doesn't change.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Register the newest release of a version series (Priority: P1)

A conformance administrator registers an IG by series, for example `hl7.fhir.us.core` with version `6.1.x`. The
service looks up which versions the package registries list, picks the highest one in the `6.1` series, and stores
it (with its dependencies) unless it's already stored. The response states which concrete version was chosen and
whether it was downloaded.

**Why this priority**: This is the failure reported in issue #56. Registration is the only way to bring a new
package version into the service from a registry, and include depends on the package being stored.

**Independent Test**: With a registry (or test double) listing `1.2.0`, `1.2.3` and `1.3.0` for a package, register
`1.2.x` and check that `1.2.3` is stored and reported, and that `1.3.0` isn't.

**Acceptance Scenarios**:

1. **Given** the registries list `1.2.0`, `1.2.3` and `1.3.0` and none is stored, **When** the administrator
   registers version `1.2.x`, **Then** `1.2.3` is downloaded and stored with its dependencies, and the response
   reports version `1.2.3`, the requested series `1.2.x`, and that it was newly downloaded.
2. **Given** the same listing, **When** the administrator registers `1.x.x` (or `1.x`), **Then** `1.3.0` is chosen.
3. **Given** `1.2.3` is already stored and is the highest `1.2` version listed, **When** the administrator
   registers `1.2.x`, **Then** the stored package is returned without downloading it again, and the response says
   it was already stored.
4. **Given** the registries list `1.2.0` and `1.2.1-ballot`, **When** the administrator registers `1.2.x`,
   **Then** `1.2.0` is chosen (a release ranks above a pre-release).
5. **Given** no registry is reachable (or registries are disabled) and `1.2.4` is stored and visible to the
   caller, **When** the administrator registers `1.2.x`, **Then** the stored `1.2.4` is returned with a warning
   saying no registry could be consulted.
6. **Given** no registry lists any `2.*` version and none is stored, **When** the administrator registers `2.x.x`,
   **Then** the request fails with 404 `package-not-found`, naming the package and the series.

---

### User Story 2 - Include the highest stored release of a version series (Priority: P1)

A conformance administrator includes an IG for a FHIR version by series, for example `hl7.fhir.us.core` with
`igPackageVersion` `6.1.x` on R4. The service picks the highest stored version in that series that the caller may
use, and includes it exactly as if that version had been given. It never contacts a registry.

**Why this priority**: Issue #56 names this endpoint explicitly. It lets scripts include "the newest stored 6.1
patch" without tracking exact versions.

**Independent Test**: Store `1.2.0`, `1.2.5` and `1.20.1` of a package, include `1.2.x` for a FHIR version, and check
that the snapshot for `1.2.5` is built and reported.

**Acceptance Scenarios**:

1. **Given** `1.2.0`, `1.2.5` and `1.20.1` are stored, **When** the administrator includes `1.2.x`, **Then**
   `1.2.5` is included and the response reports that concrete version. `1.20.1` is never a match.
2. **Given** only `1.2.1-ballot` is stored in the `1.2` series, **When** the administrator includes `1.2.x`,
   **Then** `1.2.1-ballot` is included (a pre-release is used only when no release matches).
3. **Given** no `2.*` version is stored, **When** the administrator includes `2.x.x`, **Then** the request fails
   with 404 `package-not-found`, naming the package and the series and telling the caller to register or upload
   it first, and no registry is contacted.
4. **Given** `1.2.5` is already included and `1.2.6` is stored later, **When** the administrator includes `1.2.x`
   again, **Then** `1.2.6` is included as an additional snapshot, and the `1.2.5` snapshot is left unchanged.
5. **Given** the highest matching stored version declares FHIR versions that don't include the path's FHIR
   version, **When** the administrator includes the series, **Then** the request fails with the same
   `invalid-request` error as for that exact version today. The service doesn't fall back to a lower match.

---

### User Story 3 - A private version of another organisation is never chosen (Priority: P2)

Two organisations use the same deployment. Organisation B has stored a private `1.2.9` of a package that is also
publicly available as `1.2.4`. When organisation A registers or includes `1.2.x`, the series resolves only among
versions A may use. B's private version is never chosen for A and is never named in a response or error.

**Why this priority**: The spec 005 visibility rules must hold for the new resolution path. It reuses the existing
access rules, so it's lower risk than the core resolution.

**Independent Test**: As organisation B, store a private `1.2.9`. As organisation A, include `1.2.x` with a
shareable `1.2.4` stored, and check that `1.2.4` is chosen and that `1.2.9` appears nowhere in the response.

**Acceptance Scenarios**:

1. **Given** a shareable `1.2.4` and organisation B's private `1.2.9` are stored, **When** organisation A includes
   `1.2.x`, **Then** `1.2.4` is included.
2. **Given** only organisation B's private `1.2.9` is stored in the `1.2` series, **When** organisation A includes
   `1.2.x`, **Then** the request fails with 404 `package-not-found`, as if nothing matched.
3. **Given** the registries list `1.2.9` as the highest `1.2` release and organisation B has stored it privately,
   **When** organisation A registers `1.2.x`, **Then** the request is answered as today for a registration of
   `1.2.9` by a caller who may not use it (404), without naming organisation B.
4. **Given** registries are disabled (or none lists the package), a shareable `1.0.0` is stored and organisation B
   has stored a private `2.0.0`, **When** organisation A registers `latest`, **Then** `1.0.0` is returned with the
   no-registry (or not-listed) warning, and `2.0.0` and organisation B are named nowhere (FR-023).

---

### User Story 4 - Malformed series are rejected clearly (Priority: P3)

A client sends a version that looks like a series but isn't valid, such as `1.x.3`, `x.x.x` or `1.2.3.x`. The
service rejects it with 400 `invalid-request` and a message that explains the accepted series form, instead of
treating it as a literal version and answering 404.

**Why this priority**: It turns a confusing 404 into a clear client error. It doesn't block the main flows.

**Independent Test**: Send each malformed value to both endpoints and check for 400 `invalid-request`, with no
registry call and no change to stored or included packages.

**Acceptance Scenarios**:

1. **Given** any package, **When** a client registers or includes version `1.x.3`, `x.x.x`, `x`, `1.2.x.y` or
   `1.2.3.x`, **Then** the request fails with 400 `invalid-request` and the message describes the accepted form.
2. **Given** any package, **When** a client validates with `?ig=name#1.2.x`, **Then** the request fails with 400
   `invalid-request` saying `ig` needs an exact version, and nothing is validated.
3. **Given** any package, **When** a client registers or includes an exact version (`1.2.3`) or `latest`, **Then**
   the behaviour is exactly as before this feature.

---

### User Story 5 - Dependencies declared with a series or `latest` are resolved (Priority: P2)

A package's manifest declares a dependency by series (for example `"dep": "1.0.x"`) or as `latest`/`current`. When
the administrator registers the package with its dependencies, the service resolves each such dependency the way it
resolves a top-level registration and stores the concrete version. When the package is later included (or built on
demand), the dependency resolves to the matching stored version, so the validator context contains it.

**Why this priority**: Many published IGs declare dependencies as a series. Before this fix the dependency was
fetched as the literal label `1.0.x`, failed with 404 and was silently dropped, and include reported it as "not
stored; skipped", so validation ran without it.

**Independent Test**: The registry lists `dep` `1.0.0`, `1.0.3` and `1.1.0`, and `parent#1.0.0` depends on
`dep#1.0.x`. Register `parent#1.0.0` and check that `dep#1.0.3` is stored. Include `parent#1.0.0` and check that
`resolved` contains `dep#1.0.3`, with no "not stored; skipped" warning.

**Acceptance Scenarios**:

1. **Given** the registries list `dep` `1.0.0`, `1.0.3` and `1.1.0`, **When** `parent` (depending on `dep#1.0.x`) is
   registered with its dependencies, **Then** `dep#1.0.3` is downloaded and stored, and `1.1.0` isn't.
2. **Given** registries are disabled and `dep#1.0.2` is stored, **When** `parent` is registered, **Then** the stored
   `dep#1.0.2` is used and nothing is downloaded.
3. **Given** `dep#1.0.0` is stored and the registries list `1.1.0`, **When** `parent` depends on `dep` with
   `latest` (or `current`, or no version) and is registered, **Then** `dep#1.1.0` is downloaded and stored.
4. **Given** `parent#1.0.0` and `dep#1.0.3` are stored, **When** `parent#1.0.0` is included, **Then** `resolved`
   contains `dep#1.0.3` and the warnings don't say the dependency is not stored.
5. **Given** `parent` declares a malformed series dependency such as `dep#1.x.3`, **When** `parent` is registered,
   **Then** registration succeeds, the dependency is skipped with a logged warning, and nothing is stored for it.
6. **Given** `parent#1.0.0` (shareable) depends on `dep#1.0.x`, a shareable `dep#1.0.3` is stored and organisation B
   has stored a private `dep#1.0.5`, **When** organisation A includes `parent#1.0.0`, **Then** the context resolves
   `dep#1.0.3` on every instance, the include succeeds, and `1.0.5` and organisation B are named nowhere. When
   organisation B includes it instead, its context resolves `dep#1.0.5`.
7. **Given** the same packages with `parent#1.0.0` owned by organisation A, **When** any caller validates with
   `?ig=parent#1.0.0` on demand, **Then** the context resolves `dep#1.0.3` and validation answers 200, not 424.

---

### Edge Cases

- **Prefix that isn't a part boundary**: `1.2.x` never matches `1.20.0` or `1.21.3`. Matching compares whole
  version parts, never text prefixes.
- **Case**: `1.2.X` is the same series as `1.2.x`.
- **Equivalent forms**: `1.x` and `1.x.x` are the same series.
- **Versions with fewer parts**: missing parts count as `0`. A stored or listed version `1.2` (two parts) matches
  `1.2.x` and `1.x`. A version `1` matches `1.x` but not `1.2.x`.
- **Non-numeric version labels** (for example `current`, `2020Sep`, `dev`): they never match a series.
- **Pre-releases only**: when every match is a pre-release, the highest pre-release is chosen, as for `latest`.
- **Same version in several registries**: it counts once. The highest match across all registries wins.
- **Some registries fail, others answer**: the answering registries decide. The fallback to stored versions
  applies only when no registry lists a matching version.
- **Registries list the package but no version in the series, while a matching version is stored**: the stored
  match is used with a warning that no registry lists a matching version (same rule as `latest` today, when no
  registry lists the package).
- **Series on an upload or download-URL registration**: not applicable. Those paths take the version from the
  package archive itself.
- **Series used as `latest`**: `latest` keeps its own meaning. A series never contains `latest`.
- **Highest match is incompatible with the FHIR version** (include only): the include fails. It doesn't try the
  next lower match, so the result is predictable.

## Requirements *(mandatory)*

### Functional Requirements

**Version series**

- **FR-001**: The system MUST accept a version series wherever `POST /igs/register` accepts `version` and wherever
  `POST /{version}/include-ig` accepts `igPackageVersion`. A series is one to three dot-separated parts: a numeric
  first part, then zero or more numeric parts, then one or more trailing wildcard parts written `x` or `X`
  (examples: `1.x`, `1.x.x`, `1.2.x`). The series always ends with a wildcard part.
- **FR-002**: A value with a wildcard part followed by a non-wildcard part, a wildcard first part, more than three
  parts, or any other part that is neither numeric nor a wildcard, where at least one part is a wildcard, MUST be
  rejected with 400 `invalid-request`. The message MUST describe the accepted form. Values without a wildcard part
  MUST be handled as today (exact version or `latest`).
- **FR-003**: A version MUST match a series when its numeric release parts equal the series' numeric parts, compared
  part by part (`1.2.x` matches `1.2`, `1.2.0` and `1.2.7-ballot`, and not `1.20.0` or `1.3.0`). Version labels whose
  release parts aren't numeric MUST NOT match any series.
- **FR-004**: Among matching versions, the system MUST choose the highest by the same ordering used for `latest`
  today: releases rank above pre-releases, and a pre-release is chosen only when no release matches.
- **FR-005**: Resolution MUST be deterministic: the same set of candidate versions and the same series always give
  the same concrete version.

**Registration (`POST /igs/register`)**

- **FR-006**: When `version` is a series, the system MUST ask the configured package registries for the versions
  they list for the package, and choose the highest matching version across all registries that answered.
- **FR-007**: If the chosen version is already stored and the caller may use it, the system MUST return the stored
  package without downloading it again. If it's stored but the caller may not use it, the system MUST answer as
  registration of that exact version answers today (404, without naming the owner).
- **FR-008**: If the chosen version isn't stored, the system MUST download and store it as registration of that
  exact version does today: with its dependencies when `includeDependency` is true, owned by the caller's
  organisation, and with the requested `shareable` setting.
- **FR-009**: If registries are disabled, no registry can be reached, or no registry lists a matching version, the
  system MUST fall back to the highest matching stored version the caller may use, and add a warning saying which
  case applied. If there is no such stored version, it MUST answer 404 `package-not-found`, naming the package and
  the requested series.
- **FR-010**: The registration response MUST report the concrete version resolved, the requested series, and
  whether the package was newly downloaded or already stored.

**Include (`POST /{version}/include-ig`)**

- **FR-011**: When `igPackageVersion` is a series, the system MUST choose the highest matching version among the
  versions of the package that are stored and that the caller may use. It MUST NOT contact a package registry.
- **FR-012**: If no stored version the caller may use matches, the system MUST answer 404 `package-not-found`,
  naming the package and the series, and telling the caller to register or upload the package first. Versions the
  caller may not use MUST NOT be named.
- **FR-013**: After resolution, the include MUST behave exactly as an include of the concrete `name#version`: the
  same FHIR-version compatibility check, dependency resolution, memory admission, configuration revision,
  propagation to other instances, and response. The response MUST report the concrete version.
- **FR-014**: The series MUST NOT be stored as configuration. The included snapshot is pinned to the concrete
  version chosen at include time. Storing a newer matching version later MUST NOT change existing included
  snapshots. Including the same series again includes the then-highest match, as an additional snapshot if it
  differs.
- **FR-015**: If the highest matching version fails the FHIR-version compatibility check, the include MUST fail with
  the same error as for that exact version, without trying lower matches.

**Unchanged behaviour and contract**

- **FR-016**: Exact versions and `latest` MUST behave exactly as before on both endpoints, except for the 3.2.1
  fixes in FR-020 (dependency `latest`) and FR-023 (access-aware `latest` fallback). Validation requests and include
  never download packages.
- **FR-017**: The HTTP contract MUST document the series form on the `version` field of registration and the
  `igPackageVersion` field of include, the 400 response for a malformed series, the 400 response for a series in the validation request's `ig` parameter,
  and the new response field for the
  requested series. The change is additive and MUST ship as a minor version with a changelog entry.
- **FR-018**: The validation request's IG parameter (`?ig=name#version`) MUST stay exact-version only. A value whose
  version is a series (for example `?ig=name#1.2.x`) MUST be rejected with 400 `invalid-request`, with a message
  saying that `ig` needs an exact version (the one reported by include). Nothing is validated, and no snapshot
  lookup or build happens.

**Dependencies and the `latest` fallback (3.2.1 fixes, Session 2026-11-08 (2))**

- **FR-019**: When registration loads dependencies (`includeDependency` true), a dependency whose declared version is
  a series MUST resolve as FR-006 and FR-009 do: the highest match listed by the registries that answered, else the
  highest matching stored version. It MUST NOT be fetched as a literal version label.
- **FR-020**: When registration loads dependencies, a dependency declared as `latest`, `current` or without a version
  MUST resolve as spec 004 FR-027 and FR-029 do for a top-level `latest`: the registries' highest release first, else
  the highest stored version. Exact dependency versions MUST behave as before.
- **FR-021**: Dependency resolution for include, on-demand validation contexts and snapshot builds MUST resolve a
  dependency series to the highest matching stored version, by the FR-004 ordering. This choice MUST NOT depend on
  the caller, so every instance resolves the same context; it is restricted by FR-024. The existing include check
  that the caller may use every resolved dependency still applies afterwards.
- **FR-022**: A dependency series that is malformed (FR-002) MUST be treated as unresolvable: registration logs a
  warning and skips it, and dependency resolution reports it as missing. It MUST NOT fail the request with 400,
  because the value comes from package metadata, not from the client.
- **FR-023**: When registration of `latest` falls back to stored versions (spec 004 FR-029), it MUST choose the
  highest stored version the caller may use, with the same warning. It answers 404 `package-not-found` only when the
  caller may use no stored version. This amends spec 004 FR-029 and aligns `latest` with the series fallback (FR-009).
  Versions the caller may not use MUST NOT be named.
- **FR-024**: Wherever a context is resolved (include, builds on every instance, on-demand contexts, and the access
  check before validation), a dependency declared as a series, `latest`, `current` or without a version MUST be chosen
  only among stored versions that are shareable or owned by the **context's owner**: the included context's owner
  (the caller, for a new include), or the IG package's owner for an on-demand context. The system organisation gets
  no exception here. The owner is shared state, so every instance still resolves the same context. A dependency
  declared with an exact version MUST be used as declared, and the caller's access to it is checked as before (424).
  When a package's owner or sharing changes, contexts whose choice changes are resolved again and rebuilt.

### Key Entities

- **Version series**: a requested version pattern made of fixed leading numeric parts and trailing wildcard parts.
  It's an input only and is never stored.
- **Resolved version**: the concrete `name#version` a series resolves to for one request. All later processing
  (storage, snapshots, ownership, responses) uses it.
- **Candidate versions**: the set a series is matched against. For registration, the versions the registries list
  (or, in fallback, the stored versions the caller may use). For include, the stored versions the caller may use.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of registrations and includes with a valid series and at least one matching candidate succeed and
  report the highest matching version, in the test scenarios above (versus 0% today, which all answer 404).
- **SC-002**: A series never resolves to a version outside its series, including versions that share a text prefix
  (such as `1.20.x` for `1.2.x`), in every test case.
- **SC-003**: Registration with a series takes no longer than registration with `latest` for the same package (one
  registry lookup, plus a download only when the version is new). Include with a series adds no noticeable time
  compared with an include of the exact version.
- **SC-004**: An include with a series makes zero calls to a package registry, in every test case.
- **SC-005**: 100% of malformed series values are rejected with a client error that explains the accepted form,
  and none reaches a registry or changes stored or included packages.
- **SC-006**: All existing tests for exact versions and `latest` on both endpoints still pass unchanged.
- **SC-007**: In multi-organisation tests, a version the caller may not use is never chosen and never named in a
  response or error.
- **SC-008**: In every test case, a dependency declared with a valid series is stored by registration (when a
  matching version exists) and appears in the resolved context of the package that declares it.
- **SC-009**: Registration of `latest` never answers 404 while a stored version the caller may use exists.
- **SC-010**: In multi-organisation tests, include and on-demand validation never fail with 424 because of another
  organisation's private version of a dependency declared without an explicit version, while a usable matching version
  is stored.

## Assumptions

- Only `x`/`X` is the wildcard. `*` isn't accepted, and a value without a wildcard such as `1.2` stays an exact
  version, so existing exact-version requests keep their meaning.
- Series have at most three parts (major, minor, patch), matching the version form FHIR packages use. A version
  with fewer parts counts its missing parts as `0`: `1.2` (that is, `1.2.0`) matches `1.2.x`, and `1` (that is,
  `1.0.0`) matches `1.x` but not `1.2.x`.
- Pre-release handling follows the existing `latest` rule (release preferred, pre-release as a last resort), so a
  series and `latest` agree when every version is in one series.
- Registration falls back to stored versions with a warning when no registry can decide, the same as `latest`
  today (spec 004 FR-029). This keeps registration usable when registries are disabled or unreachable.
- Dependencies that a package's manifest declares with a series (for example `4.0.x`) are resolved (US5, FR-019 to
  FR-022). The dependency walk during registration doesn't filter by caller: a dependency that is already stored is
  only read, never re-owned, exactly as for exact dependency versions before. A dependency newly downloaded is owned
  like the package that declares it.
- Upload and download-URL registrations are out of scope. They take the version from the package archive.
- Memory impact is negligible: a series is resolved per request from version lists the service already reads, and
  nothing new is cached or stored. No schema change is needed.
- Multi-instance consistency is unchanged: include stores only the concrete version in the shared configuration,
  so other instances follow through the existing revision and propagation mechanism.
