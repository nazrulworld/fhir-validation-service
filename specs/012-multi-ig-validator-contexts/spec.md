# Feature Specification: Multi-IG Validator Contexts (Up to Two IGs per Context, with Aliases)

**Feature Branch**: `012-multi-ig-validator-contexts`

**Created**: 2026-11-19

**Status**: Draft

**Input**: User description: "Endpoint: /{version}/validator (ValidatorApi.java). 1. The endpoint should accept an
optional list of IG packages: igPackages = list of ({'igPackageId', 'igPackageVersion'}). 2. The validator snapshot
should be built with all provided IG packages including all dependencies. 3. A user can provide at most two IGs.
4. The key (for multiple IGs) is Ig1#version|Ig2#version. 5. There should be aliases for that snapshot, e.g.
Ig1#version can be discoverable as the built validator snapshot; another alias is Ig2#version|Ig1#version.
Workflow for validation: if the user provided two IGs, first look for an exact key match; if that fails, look up by
alias."

**Background**: `specs/004-ig-scoped-validation/spec.md` (one validator context per IG version, inclusion,
exclusion, `ig` on validation), `specs/005-organisation-ownership/spec.md` (owner and sharing of contexts),
`specs/006-ig-version-series/spec.md` (version series on include), `specs/007-terminology-routing/spec.md` and
`specs/010-shared-packages-request-tx/spec.md` (terminology setting as the context's default, shared package
definitions).

**Release**: ships in the **1.x.x** line (a 1.x minor release), as the maintainer asked, because more changes are
coming. The include body gains an optional field and `ig` gains a pair form; existing requests are unchanged. If
planning finds a change that is breaking under constitution Principle IV, it MUST be recorded under Complexity
Tracking in `plan.md` with the maintainer's approval, and the release notes carry a **Breaking** section and an
upgrade guide; it still ships as 1.x.x.

## Clarifications

### Session 2026-11-19

- Q: What happens when the two IGs' dependency trees require different versions of the same package? → A: The
  include is refused with 400 and the conflicting package is named (FR-006). No version is chosen automatically
  (except for the HL7 base packages, see below).
- Q: How is `ig=A#a` resolved when a single-IG context `A#a` and/or several combined contexts starting with `A#a`
  could match? → A: Single-IG context first, first-IG alias otherwise; a second combined context with the same first
  IG is refused with 409 (rule in FR-012).
- Q: Which release line? → A: 1.x.x, whatever the changes; more changes are coming.
- Q: If the two IGs need different versions of `hl7.terminology.*` or `hl7.fhir.uv.extensions.*`, is the include
  refused too? → A: No. These two package families are exempt: the highest required version is used and the choice
  is reported as a warning in the include response. Every other conflict is still refused (FR-006).
- Q: Does `ig=A` or `ig=A#latest` (no exact version) also consider combined contexts whose first IG is A? → A: No.
  Only single-IG contexts are considered; the first-IG alias is reached only with an exact version (FR-012a).

## Context

Today a validator context contains exactly one IG package (plus its dependencies and the core specification), the
way `validator_cli.jar -ig` is used with a single IG. Real exchanges are often governed by two IGs at once, for
example a national base IG and a project IG that is not declared as its dependency, or an IG and a separately
published terminology or extension package. The reference CLI handles this by repeating `-ig`. The service cannot:
a consumer has to choose one IG per request, so profiles from the other IG are reported as unknown.

This feature lets an administrator include one validator context that contains **two** IG packages and all their
dependencies, gives that context a stable key, `A#a|B#b` (the IGs in the order given), and makes it findable under
aliases, so a validation request that names the same IGs in the other order, or (see FR-012) names only the first
IG, uses it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Include a validator context built from two IGs (Priority: P1)

An administrator includes a validator context for a FHIR version and lists two IG packages in `igPackages`, for
example `dk.fhir.ig.dk-core#3.4.0` and `dk.ehealth.sundhed.fhir.ig.core#4.0.0`. The service builds one context
containing both IGs, every dependency of each, and the core specification, and pins it on every instance. The
response reports the context's key `dk.fhir.ig.dk-core#3.4.0|dk.ehealth.sundhed.fhir.ig.core#4.0.0`, the concrete
version of each IG, and the context's aliases.

**Why this priority**: Nothing else in the feature works without a combined context. It also keeps the existing
single-IG include working unchanged, so it can ship on its own.

**Independent Test**: Store two IG packages, include them together for R4, and confirm the response lists the key and
aliases, and the context appears as included with both IGs on every instance.

**Acceptance Scenarios**:

1. **Given** IGs A#1.0.0 and B#2.0.0 are stored and usable by the caller, **When** the administrator includes R4 with
   `igPackages: [{A, 1.0.0}, {B, 2.0.0}]`, **Then** one context is included with key `A#1.0.0|B#2.0.0`, containing A,
   B, all their dependencies and the core specification.
2. **Given** the same request, **When** `igPackageVersion` is omitted, `latest` or a version series for an entry,
   **Then** that entry is resolved exactly as a single-IG include resolves it (spec 004, spec 006) and the key uses the
   concrete version.
3. **Given** a request with three entries in `igPackages`, **When** it is sent, **Then** it is refused with 400
   invalid-request and nothing is stored.
4. **Given** a request with one entry in `igPackages`, **When** it is sent, **Then** it behaves exactly like today's
   single-IG include with `igPackageId`/`igPackageVersion`.
5. **Given** a request using only the existing `igPackageId`/`igPackageVersion` fields, **When** it is sent, **Then**
   it behaves exactly as today.
6. **Given** a context `A#1.0.0|B#2.0.0` is already included, **When** an administrator includes `B#2.0.0|A#1.0.0`,
   **Then** nothing new is built and the response reports the existing context (its key, owner and sharing).

---

### User Story 2 - Validate against two IGs (Priority: P1)

An API consumer validates a resource and names both IGs in `ig`, for example
`ig=A%231.0.0|B%232.0.0`. The resource is validated against the combined context; profiles from either IG (and their
dependencies) are resolvable. The response names the context used by its key.

**Why this priority**: This is the value the consumer sees; it brings the CLI's repeated `-ig` to the service.

**Independent Test**: With `A#1.0.0|B#2.0.0` included, validate a resource that claims a profile of A and one of B;
confirm both are applied and `X-Validation-Context` reports `A#1.0.0|B#2.0.0`.

**Acceptance Scenarios**:

1. **Given** `A#1.0.0|B#2.0.0` is included, **When** a request names `ig=A#1.0.0|B#2.0.0`, **Then** the exact key
   matches and that context is used.
2. **Given** `A#1.0.0|B#2.0.0` is included, **When** a request names `ig=B#2.0.0|A#1.0.0`, **Then** the exact-key
   lookup fails, the alias lookup finds the context, and it is used; the response reports the context's key
   `A#1.0.0|B#2.0.0`.
3. **Given** no combined context with A#1.0.0 and B#2.0.0 is included, **When** a request names both, **Then** it is
   refused with 400 invalid-request ("not included; include it first"), the same way a single stored but not
   included IG is refused; no context is built at request time.
4. **Given** the request names `ig=A#1.0.0|A#2.0.0` or `ig=A#1.0.0|A#1.0.0` or more than two IGs, **Then** it is
   refused with 400 invalid-request.

---

### User Story 3 - Find a combined context by its first IG alone (Priority: P2)

A consumer who names only `ig=A#1.0.0` is served by the combined context `A#1.0.0|B#2.0.0`, because the
administrator made the first IG an alias of the combined context.

**Why this priority**: Convenient for consumers who only know their primary IG, but it changes what a single-IG
request validates against, so it comes after the core flows.

**Independent Test**: Include only `A#1.0.0|B#2.0.0`; validate with `ig=A#1.0.0`; confirm the combined context is used
and reported.

**Acceptance Scenarios**:

1. **Given** `A#1.0.0|B#2.0.0` is included and no single-IG context `A#1.0.0` is included, **When** a request names
   `ig=A#1.0.0`, **Then** the combined context is used and `X-Validation-Context` reports `A#1.0.0|B#2.0.0`.
2. **Given** a single-IG context `A#1.0.0` is also included, **When** a request names `ig=A#1.0.0`, **Then**
   the single-IG context is used and `X-Validation-Context` reports `A#1.0.0`.
3. **Given** `A#1.0.0|B#2.0.0` is included, **When** an administrator includes `A#1.0.0|C#3.0.0` for the same FHIR
   version, **Then** it is refused with 409 and nothing is stored.
4. **Given** `A#1.0.0|B#2.0.0` is included, **When** an administrator includes `B#2.0.0|C#3.0.0`, **Then** it is
   accepted: its first-IG alias `B#2.0.0` doesn't collide (only first IGs are aliases).
5. **Given** `A#1.0.0|B#2.0.0` is included and serving `ig=A#1.0.0`, **When** a single-IG context `A#1.0.0` is
   included, **Then** from then on `ig=A#1.0.0` uses the single-IG context; excluding it again makes the alias serve
   the combined context.
6. **Given** a single-IG context `A#1.0.0` and a combined context `A#2.0.0|B#1.0.0` are included, **When** a request
   names `ig=A` or `ig=A#latest`, **Then** `A#1.0.0` is used (FR-012a); `ig=A#2.0.0` uses the combined context.

---

### User Story 4 - Manage a combined context (Priority: P2)

An administrator changes sharing, owner or terminology of a combined context, or excludes it, naming it in `ig` by its
key or by its reversed-order alias.

**Why this priority**: Required for operating the feature, but the existing operations already define the behaviour;
only the addressing is new.

**Independent Test**: PATCH and DELETE `ig=B#2.0.0|A#1.0.0` and confirm the change applies to `A#1.0.0|B#2.0.0`.

**Acceptance Scenarios**:

1. **Given** `A#1.0.0|B#2.0.0` is included, **When** an administrator sends PATCH or DELETE with `ig` set to the key
   or to `B#2.0.0|A#1.0.0`, **Then** the operation applies to that context with today's rules for ownership, sharing,
   terminology and propagation.
2. **Given** the same context, **When** PATCH or DELETE names `ig=A#1.0.0` alone, **Then** it addresses only a
   single-IG context `A#1.0.0` (404 if none is included); a first-IG alias never addresses a combined context for
   changes.

---

### Edge Cases

- **Dependency version conflict**: A depends on `X#1.0.0` and B on `X#2.0.0` (or B's dependencies pull another
  version of A). The include is refused with 400 naming the conflicting package (FR-006).
- **Base package version difference**: A needs `hl7.terminology.r4#5.0.0` and B needs `hl7.terminology.r4#6.0.2`.
  The include is accepted with `6.0.2` and a warning naming the package and both versions (FR-006a).
- **One IG is a dependency of the other**: B already depends on A#1.0.0. The include is accepted; the context is the
  same set of packages as B alone, but it is still a distinct context with its own key.
- **Different FHIR versions**: either IG doesn't declare this FHIR version → 400 invalid-request, as for a single IG.
- **Access**: either IG, or any dependency of either, is not usable by the caller → 404 / 424 exactly as for a single
  IG, without saying which of the two IGs failed when the caller may not see it.
- **Memory**: the combined context doesn't fit the instance's budget → 413 package-too-large before it is committed.
- **Package replaced or deleted**: replacing or deleting a stored package that a combined context contains affects it
  the same way it affects a single-IG context that contains it (spec 004, spec 010).
- **Excluding a single-IG context** leaves combined contexts that contain the same IG untouched, and vice versa.
- **`ig` without a version inside a pair** (`ig=A|B#2.0.0` or `ig=A#latest|B`): refused with 400 invalid-request;
  pairs must name exact versions.
- **Encoding**: `|` in `ig` must be URL-encoded as `%7C` by strict clients; the service accepts both encoded and
  unencoded forms.
- **Mixed body**: both `igPackages` and `igPackageId` in one include body → 400 invalid-request.
- **Empty list**: `igPackages: []` → 400 invalid-request.

## Requirements *(mandatory)*

### Functional Requirements

**Include**

- **FR-001**: The include operation (`POST /{version}/validator`) MUST accept an optional `igPackages` list whose
  entries each have `igPackageId` (required) and `igPackageVersion` (optional; same values and resolution as today's
  single-IG field: exact, `latest`/omitted, or a version series).
- **FR-002**: `igPackages` MUST contain one or two entries. Zero or more than two, two entries with the same
  `igPackageId`, or a body that has both `igPackages` and `igPackageId`, MUST be refused with 400 invalid-request
  before anything is stored.
- **FR-003**: A request with only `igPackageId`/`igPackageVersion`, or with one `igPackages` entry, MUST behave
  exactly as today's single-IG include (same key, response, errors and propagation).
- **FR-004**: A two-entry include MUST build one validator context containing both IGs, every dependency of each
  (resolved deterministically), and the core specification, and MUST pin it on every instance like any included
  context.
- **FR-005**: Every check a single-IG include applies (served/enabled FHIR version, FHIR version support of the
  package, ownership and sharing of packages and dependencies, memory admission, terminology setting validation)
  MUST apply to each IG and each dependency of the combined context; any failure refuses the whole include.
- **FR-006**: When the two IGs' dependency trees require different versions of the same package, the service MUST
  refuse the include with 400 (a new problem type, `dependency-conflict`) before anything is stored. The problem
  MUST name the conflicting package and the versions required, except a package or version the caller may not see
  (then it answers 424 dependency-not-found as today, without naming it). The service MUST NOT choose a version
  automatically, except as FR-006a allows. A conflict where one IG's own version differs from the version the other
  IG depends on is a conflict too.
- **FR-006a**: Packages whose name starts with `hl7.terminology` or `hl7.fhir.uv.extensions` are exempt from FR-006:
  when the two IGs need different versions, the highest required version (by version order) is used, only that
  version is in the context, and the include response's `warnings` names the package, the versions required and the
  version used. The choice MUST be derived deterministically from shared state (the stored context key and the
  stored package catalog), so every instance builds the same package set; it is not stored separately. A version difference only matters when the same package name is needed in two versions; other
  packages in these families are loaded as today.

**Key and aliases**

- **FR-007**: A combined context's key MUST be `A#a|B#b`, where A#a is the first and B#b the second `igPackages`
  entry after version resolution. A single-IG context's key stays `A#a`.
- **FR-008**: Every combined context MUST have the reversed-order alias `B#b|A#a`. A combined context with the same
  two IG versions in either order MUST NOT be included twice for a FHIR version; including it again in either order
  changes nothing and reports the existing context.
- **FR-009**: The include response, and every listing that reports included contexts, MUST report each combined
  context's key, its two IGs with concrete versions, and its aliases.

**Validation**

- **FR-010**: The validate operation's `ig` parameter MUST accept either one IG (today's forms) or two IGs separated by
  `|`, each with an exact version. The 256-character limit applies to the whole value.
- **FR-011**: For two IGs, the service MUST first look up an included context whose key equals the requested value
  exactly; if none matches, it MUST look up a context with that value as an alias; if none matches, it MUST refuse
  with 400 invalid-request ("not included; include it first"). It MUST NOT build a context at request time.
- **FR-012**: The first IG of a combined context (`A#a`) MUST be an alias of that context, used when a validation
  request names `ig=A#a` alone, with this precedence: a single-IG context `A#a` included for that FHIR version
  always wins; the alias is used only when none is included. Including a combined context whose first IG version
  `A#a` is already the first IG of another combined context for that FHIR version MUST be refused with 409 (a new
  problem type, `alias-conflict`) before anything is stored. Including or excluding a single-IG context `A#a` never
  changes or removes the alias; it only changes which context `ig=A#a` reaches. The second IG is never a single-IG
  alias.
- **FR-012a**: A validation request that names one IG without an exact version (`ig=A` or `ig=A#latest`) MUST be
  resolved among single-IG contexts only, exactly as today; first-IG aliases MUST NOT be considered. When no single-IG
  context of A is included, the answer stays as today (400 invalid-request), even if a combined context starting with
  A is included.
- **FR-013**: The response MUST report the key of the context actually used in `X-Validation-Context` (never the alias
  that was requested), together with its owner and sharing as today.
- **FR-014**: Access to a combined context MUST follow today's rules: the caller may use it only if the caller's
  organisation owns it or it is shareable; a context the caller may not use is answered as not stored (404).
- **FR-015**: Terminology settings (context default plus per-request `tx`/`txMode`) MUST apply to a combined context
  exactly as to a single-IG context. A combined context has one terminology setting.
- **FR-016**: Requested profiles (`profile`) MUST be resolvable from either IG, their dependencies or the core
  specification.

**Change and exclude**

- **FR-017**: PATCH and DELETE on `/{version}/validator` MUST accept a combined context's key or its reversed-order
  alias in `ig`, and apply today's rules (ownership, sharing, terminology, propagation, inactive FHIR versions). A
  single-IG value MUST only address a single-IG context.
- **FR-018**: Excluding a combined context MUST remove the context and all its aliases on every instance within the
  propagation window. It MUST NOT affect single-IG or other combined contexts that contain the same IGs.

**Consistency**

- **FR-019**: Combined contexts MUST be stored as shared state and propagated to every instance within the existing
  propagation window. Aliases are not stored: every instance MUST derive them from the included contexts, so every
  instance resolves the same key or alias to the same context.
- **FR-020**: The API contract MUST document `igPackages`, the key and alias forms, the `ig` pair syntax and the lookup
  order before or with the implementation.

### Key Entities

- **Validator context**: a pinned, included set of definitions for one FHIR version. Now contains one or two IGs
  (plus dependencies and the core specification). Attributes: FHIR version, key, member IGs (ordered), aliases,
  owner, sharing, terminology setting.
- **Context key**: the canonical identifier: `A#a` for one IG, `A#a|B#b` for two (include order).
- **Context alias**: another value that resolves to a context: the reversed pair `B#b|A#a`, and the first-IG alias
  `A#a`. Uniqueness and precedence are defined in FR-008 and FR-012.
- **IG package entry**: `igPackageId` plus `igPackageVersion` as requested, and the concrete version it resolved to.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A resource that claims profiles from two different IGs validates with both profiles applied in a single
  request, with no `unknown-profile` problem, where today one of the two is always unknown.
- **SC-002**: A validation naming two IGs in either order uses the same context in 100% of requests, on every
  instance, and reports the same key.
- **SC-003**: No validation request ever causes a context to be built; a combined context is built once per instance
  when it is included and reused for every request.
- **SC-004**: Every existing single-IG include, change, exclude and validation request returns the same result, status
  and headers as before the feature (no regression in the existing test suite).
- **SC-005**: A combined context is available on every instance within the same propagation window that applies to a
  single-IG context today.
- **SC-006**: Results for a combined context match the reference validator CLI run with the same FHIR version and the
  same two `-ig` packages for the project's conformance samples.

## Performance and Capacity

Constitution Principle VI.

- **Latency**: validation against a single-IG context is unchanged: p95 within 5 % of 1.1.0 for the same request
  (below 200 ms against a warm context). Validation against a combined context has the same target. Resolving a pair
  or an alias is an in-memory lookup and adds no database or network call to the request.
- **Throughput**: unchanged; combined contexts use the same validation workers and admission limits.
- **Memory**: a combined context costs its resolved package set, admitted by the existing heap budget before it is
  committed (413 otherwise). Packages already loaded for other contexts on the instance are shared (spec 010) and
  count for their indexes only, so combining two IGs that are already included costs far less than the two IGs'
  sizes. The alias index is a few entries per included context. No new memory setting.
- **Build time**: a combined context is built once per instance at include time (or startup), never per request.

## Assumptions

- "Validator snapshot" in the request means the service's validator context (spec 004); the feature uses that term.
- At most two IGs per context is a fixed limit, not a setting.
- The key keeps the order the administrator gave; the order is not sorted. The reversed order is always an alias.
- A pair in `ig` needs exact versions; `latest`, a bare name and version series are only resolved on include, not in
  a validation pair (single-IG `ig` keeps today's rules).
- `|` separates IGs in `ig` and in keys; IG package names and versions never contain `|`.
- A combined context is owned by the caller's organisation and is shareable unless `shareable: false`, like a
  single-IG context; `shareable: false` needs ownership of both IG packages (or the system organisation).
- The feature is additive (new optional field, new `ig` value form) and ships in the 1.x.x line (see **Release**).
- The two new problem types (`dependency-conflict`, `alias-conflict`) get problem type pages on the documentation
  site like the existing ones (spec 011).
- Memory use of a combined context benefits from shared package definitions (spec 010); no new memory setting is
  needed.
- Out of scope: more than two IGs, combined contexts built at validation time, and aliases chosen freely by the
  administrator.
