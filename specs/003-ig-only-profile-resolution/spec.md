# Feature Specification: IG-Only Profile Resolution (Remove Custom Profile Registration)

**Feature Branch**: `003-ig-only-profile-resolution`

**Created**: 2026-11-03

**Status**: Draft

**Input**: User description: "@var/specify_instruction.md". In summary: make profile handling behave like
the official HL7 FHIR Validator CLI used with `-ig` and `-profile`. Requested profiles are resolved only
from StructureDefinitions already available through the IG packages included for that FHIR version, and
never fetched from a canonical URL. Remove the separate custom profile registration endpoint and
everything that exists only to support it.

**Evidence**: [var/fhir_cli_report.md](../../var/fhir_cli_report.md) ("Comparison: this service versus
`validator_cli`", findings #1 and #9) and the `fhir-validator-cli` skill
(`references/validation-chain.md`, `references/hapi-comparison.md`).

## Context

The reference HL7 validator CLI keeps two concerns apart. `-ig` loads conformance definitions (a pinned
package plus its dependencies), and `-profile` only *selects* which loaded StructureDefinition an
instance is checked against. A `-profile` that can't be resolved stops the run before anything is
validated.

Today the service has two sources of profiles: IG packages included per FHIR version, and loose
StructureDefinitions registered one by one through `register-profile`. Each has its own storage,
propagation and caching path. The service also differs from the CLI in ways that matter to consumers.
When several profiles are requested and one of them is unknown, the unknown one is silently dropped and
the resource can come back valid. The `appliedProfiles` list in the response also names profiles that
were never applied.

This feature makes the included IG packages the only source of profiles, aligns the handling of
requested profiles with the CLI, and removes the custom profile registration mechanism.

## Clarifications

### Session 2026-11-03

- Q: How is an unresolved requested profile reported? → A: As a rejected request: a client error
  (HTTP 400) in the standard problem-details format that lists every unresolved URL. No validation runs
  and no validation result is returned. This is the closest match to the CLI, where an unknown `-profile`
  stops the run.
- Q: What severity does an unknown profile declared inside the resource (`meta.profile`) get? → A: It
  stays an ERROR, as today. This is deliberately stricter than the CLI's WARNING, and existing consumers
  see no change.
- Q: What happens to stored custom profiles on upgrade? → A: The upgrade to 2.0.0 permanently removes the
  stored custom profile data. Operators MUST export it before they upgrade, using the export step that
  is documented for, and works against, the previous release's database.
- Q: When an operator upgrades with stored custom profiles that were never exported, what does the
  migration do? → A: There is no safety gate: the migration always drops the data, and the only
  protection is the warning in the upgrade guide and changelog. This risk is accepted.
- Q: Is rolling back to 1.2.0 supported after the 2.0.0 migration has run? → A: No. The upgrade guide
  requires a full database backup before upgrading, and restoring that backup is the only supported
  way back to 1.2.0.
- Q: What is the most `?profile=` URLs one validation request may name? → A: A fixed limit of 10
  profiles per request, with each URL at most 1024 characters. A request over either limit is rejected
  as a client error (`invalid-request`).
- Q: What format does the unresolved-profile error use when the client asked for a FHIR
  OperationOutcome? → A: Always the standard problem-details JSON, whatever the client asked for,
  consistent with every other client error from this endpoint.
- Q: During the upgrade, may 1.x and 2.0 instances run against the same database at the same time? →
  A: No. Every 1.x instance must be stopped before the first 2.0 instance starts, and the rolling-restart
  procedure must not be used for this upgrade.

### Session 2026-11-04

- Q: When the export finds stored profiles whose FHIR version 2.0.0 doesn't support (for example a
  leftover `6.0.0` row), what does it do with them? → A: It writes them unpackaged, as raw files in a
  separate `unsupported-<version>` folder, prints a warning with the count, and still succeeds. Stored
  profiles are always valid JSON, because the column is `JSONB`.
- Q: What happens if an IG package with the same name and version as a package the export would
  generate is already stored? → A: The export refuses (exit 1) before writing anything, names the clash,
  and tells the operator to choose another `--name` or `--package-version`. The upgrade guide mentions
  this, because uploading a package with an existing name and version replaces the stored one without
  warning.
- Q: When a Bundle is validated against a requested profile for its entries' type, does `appliedProfiles`
  list that profile? → A: Yes. `appliedProfiles` means "resolved and checked in this request", whether
  against the submitted resource or the Bundle entry the validator picked.
- Q: Is the order of `appliedProfiles` fixed? → A: Yes. Requested profiles come first, in the order the
  client named them, then resolvable declared profiles in the order the resource lists them. Each
  appears once.
- Q: Must the export produce ready-to-upload IG packages, or only files the operator packages? → A:
  Ready-to-upload packages: one `.tgz` per supported FHIR version, with a valid `package.json` (the FHIR
  version, the core dependency and any extra dependencies the operator names), which `POST /igs/upload`
  accepts unchanged.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A requested profile is either applied or the request fails loudly (Priority: P1)

An API consumer validates a resource and names one or more profiles to check it against. Each named
profile is looked up among the definitions the service already holds for that FHIR version. If every
profile is found, the resource is checked against each of them and the response lists exactly those
profiles as applied. If any named profile isn't found, the consumer is told which ones, and the service
never reports the resource as valid without checking it against everything that was asked for.

**Why this priority**: This fixes a correctness defect. Today a consumer can ask for a profile, get
"valid" back, and never have been checked against it. Fixing it is valuable even if nothing else in this
feature ships.

**Independent Test**: With an IG included for R4, validate a fixture that violates one of that IG's
profiles, three ways: naming only that profile, naming that profile plus a made-up URL, and naming only
a made-up URL. Confirm that the first reports the violation, that the second and third never report the
resource as valid, and that both name the made-up URL.

**Acceptance Scenarios**:

1. **Given** an IG included for a FHIR version that defines profile P, **When** a consumer validates a
   resource naming P, **Then** the resource is checked against P, P's issues are reported, and P is
   listed as applied.
2. **Given** profiles P and Q both defined by included IGs, **When** a consumer names both, **Then** the
   resource is checked against each, issues from both checks are reported together, and both are listed
   as applied.
3. **Given** profile P is known and URL U is not, **When** a consumer names P and U, **Then** the request
   is rejected as a client error that names U as unresolved, no validation is performed, and no
   validation result (valid or not) is returned.
4. **Given** URL U is not known, **When** a consumer names only U, **Then** the outcome is the same as
   scenario 3.
5. **Given** a resource whose own profile declarations include an unknown profile, **When** it is
   validated, **Then** the unknown declaration is reported as an error issue in the validation result, as it is
   today, and the profile is not listed as applied.
6. **Given** any validation request, **When** the response lists the applied profiles, **Then** every
   listed profile was actually resolved and applied, and no unresolved URL appears in that list.

---

### User Story 2 - Profiles come only from included IGs, never from the network (Priority: P1)

A conformance administrator controls exactly which definitions a FHIR version validates against by
choosing which IG packages are included. Consumers name profiles by canonical URL, and the service
resolves them only from the included packages, their dependencies and the core specification. A
canonical URL is an identifier, not an address. The service never downloads a profile while it
validates, so results are the same online and offline and don't depend on what a website serves that
day.

**Why this priority**: This is the CLI's `-ig` / `-profile` model and the core of the request. It makes
results reproducible and keeps validation free of outbound traffic.

**Independent Test**: Upload two packages, A and B. Include only A for R4. Then validate naming a
profile from A, then naming a profile from B, then naming a canonical URL that is publicly reachable
on the web but in no stored package. Observe network traffic throughout.

**Acceptance Scenarios**:

1. **Given** package A is included for R4, **When** a consumer names a profile defined in A or in one of
   A's dependencies, **Then** it is resolved and applied.
2. **Given** package B is stored but not included for R4, **When** a consumer names a profile that only
   B defines, **Then** it is reported as unresolved (User Story 1, scenario 3 or 4).
3. **Given** a canonical URL that is reachable on the internet but not defined by any included package,
   **When** a consumer names it, **Then** it is reported as unresolved, and the service makes no
   outbound request for it.
4. **Given** a newly included package defines profile P, **When** the inclusion has taken effect,
   **Then** P can be named on every instance of the service, within the propagation window already
   guaranteed for IG changes.
5. **Given** a package is replaced by another version of itself, **When** the change has taken effect,
   **Then** profiles resolve from the new version only.

---

### User Story 3 - One way to manage conformance: custom profiles ship as IG packages (Priority: P2)

A team that used to register loose profiles now packages them as an IG, uploads the package and
includes it, exactly as they would pass `-ig my-package.tgz` to the CLI. The old registration operation
is gone, so there's a single place where conformance content is managed, versioned and propagated.

**Why this priority**: It simplifies administration and operation and removes a whole propagation path,
but consumers get correct results from User Stories 1 and 2 without it.

**Independent Test**: Call the former registration operation and confirm it no longer exists, both in
the live service and in the published API description. Then package a custom profile as an IG, upload it,
include it, and validate against the profile.

**Acceptance Scenarios**:

1. **Given** the upgraded service, **When** anyone calls the former profile registration operation for
   any FHIR version, **Then** it answers as an unknown route, and no profile is stored.
2. **Given** the published API description, **When** an integrator reads it, **Then** it has no profile
   registration operation, and the profile parameter on validation says profiles must come from
   included IGs.
3. **Given** a custom StructureDefinition packaged as an IG, **When** it is uploaded and included,
   **Then** consumers can name it like any other included profile.
4. **Given** the service's metrics, health report and documentation, **When** an operator reviews them,
   **Then** nothing refers to custom profile registration, counts of registered profiles, or profile
   changes as a separate kind of configuration change.

---

### User Story 4 - Operators upgrade without losing registered profiles unknowingly (Priority: P2)

An operator running an earlier release with registered custom profiles reads the upgrade notes
*before* upgrading. The notes say the registration mechanism is gone, that the upgrade permanently
removes stored custom profiles, and how to export them from the running previous release as
ready-to-upload IG packages. The operator exports first, then upgrades, then uploads and includes the
exported packages.

**Why this priority**: The change breaks existing workflows. Operators need a safe path, but the path is
a one-time step around the feature, not its core.

**Independent Test**: Start from a database of the previous release that contains registered profiles.
Run the documented export step against it, upgrade, confirm the stored profile data is gone, upload and
include the exported packages unchanged, and confirm that a resource validates against those
profiles exactly as it did before the upgrade.

**Acceptance Scenarios**:

1. **Given** a previous-release database with registered profiles, **When** the operator runs the
   documented export step *before* upgrading, **Then** every stored profile for every FHIR version is
   written out in a ready-to-upload IG package for its FHIR version.
2. **Given** a previous-release database with registered profiles, **When** the service is upgraded,
   **Then** the stored custom profile data is removed, the service starts and serves validation
   normally, and nothing else (IG packages, included-IG configuration, API keys) is lost.
3. **Given** the changelog and upgrade guide, **When** an operator reads them before upgrading, **Then**
   the export-before-upgrade step is stated prominently as mandatory for anyone with registered
   profiles, together with how to check whether any exist.

---

### Edge Cases

- **Duplicate names in one request**: The same profile is named twice in one request. It's applied
  once and listed once.
- **Empty items in the list**: The profile list contains empty items or surrounding whitespace, for
  example `a,,b ` . Empty items are ignored, and the rest are trimmed and treated normally.
- **Versioned canonical**: A requested canonical carries a version suffix (`url|1.2.0`). It resolves only
  if an included package defines that URL at that version. Otherwise it's unresolved.
- **Same canonical in several packages**: Two included packages define the same canonical URL, and the
  request names it without a version. The service picks one deterministically, so the same included
  package set always gives the same answer on every instance.
- **Too many or too long**: A request names more than 10 distinct profiles (counted after trimming,
  dropping empty entries and removing duplicates), or a profile URL longer than 1024 characters. It's
  rejected as `invalid-request` (FR-006a), and nothing is looked up.
- **Wrong resource type**: A requested profile constrains a different resource type than the one
  submitted. It is resolved, and the type mismatch is reported as a validation issue, as the CLI does.
  It isn't treated as unresolved.
- **Requested profile that isn't a profile**: The URL names a StructureDefinition that isn't a resource
  profile (for example an extension definition or a logical model). It counts as resolved, because it is
  a StructureDefinition the included set defines. The resource is validated against it, and whatever
  issues the validator reports, such as a type mismatch, are returned in a completed validation. It's
  never silently skipped, and never reported as unresolved.
- **Validator not ready**: A FHIR version's validator is still being built or is out of date. The
  request is refused as "not ready", the same as today, before any profile lookup.
- **Inclusion in flight**: An IG inclusion is being applied while a request names one of its profiles.
  The request uses whichever configuration was active when it started, consistently. It is never
  partly old and partly new.
- **Bundle**: The resource is a Bundle and a requested profile targets the type of its entries. The
  behaviour matches the reference validator: it moves to the first matching entry. The profile is listed
  in `appliedProfiles`, because it was resolved and checked (FR-007).

## Requirements *(mandatory)*

### Functional Requirements

**Profile resolution**

- **FR-001**: The service MUST resolve every profile a consumer names on a validation request only from
  the definitions available to that FHIR version's active validator: the core specification, the
  included IG packages, and their resolved dependencies.
- **FR-002**: The service MUST NOT make any network request to resolve a profile, whether to the
  profile's canonical URL, a package registry or any other location, while serving a validation request.
- **FR-003**: Profiles MUST be matched by exact canonical URL. A `|version` suffix MUST restrict the
  match to that version. Without a suffix, the choice among several definitions of the same URL MUST be
  deterministic for a given included package set.
- **FR-004**: A package that is stored but not included for a FHIR version MUST NOT contribute profiles
  to that version's validation.

**Requested profiles**

- **FR-005**: When every requested profile resolves, the resource MUST be validated against each of them
  in addition to its base definition, and the issues from all checks MUST be returned together.
- **FR-006**: When one or more requested profiles do not resolve, the service MUST reject the request as
  a client error (HTTP 400) in the standard problem-details format. It MUST name every unresolved URL,
  MUST NOT perform any validation, and MUST NOT return a validation result. The unresolved URLs are
  data the client supplied, so they may be echoed back. Internal causes stay in the log. The response
  is always in the standard problem-details format, whatever response format the client asked for:
  an OperationOutcome is never returned for this error.
- **FR-006a**: A validation request MUST name at most **10** distinct profiles, and each profile URL MUST
  be at most **1024** characters. A request over either limit MUST be rejected as a client error
  (HTTP 400, `invalid-request`) before any lookup or validation. Both limits are fixed, not
  configurable.
- **FR-007**: The list of applied profiles in a validation response MUST contain only the profiles that
  were resolved and applied, whether they were requested or declared in the resource. Each appears once,
  in a fixed order: requested profiles in the order the client named them, then declared profiles in the
  order the resource lists them. A requested profile that the validator checks against a Bundle entry rather than the Bundle itself
  counts as applied.
- **FR-008**: An unknown profile declared inside the resource MUST keep being reported as an error issue
  in the validation result (the request still completes), and MUST NOT be listed as applied.

**Removal of custom profile registration**

- **FR-009**: The profile registration operation MUST be removed from the service and from the published
  API description for every FHIR version. Calls to its former address MUST be answered as an unknown
  route.
- **FR-010**: Everything that exists only to support registered custom profiles MUST be removed: storing
  them, keeping them in memory, serving them to the validator, propagating them between instances,
  treating them as their own kind of configuration change, and counting them in metrics.
- **FR-011**: Removing profile registration MUST NOT weaken the guarantees for IG changes. Including,
  replacing or removing an IG MUST still take effect on every instance within the existing propagation
  window, and validation MUST still never wait on the database or the network.
- **FR-012**: The administrative-operation rules (administrator key, per-instance admission limits) MUST
  continue to cover every remaining administrative operation, and MUST NOT refer to the removed one.

**Upgrade, data and documentation**

- **FR-013**: Upgrading from the previous release MUST succeed on a database that contains registered
  custom profiles, and the service MUST start and validate normally afterwards.
- **FR-014**: The release MUST provide a documented export step that runs against the *previous*
  release's database (before the upgrade) and writes every stored custom profile, for every FHIR
  version, as ready-to-upload IG packages: one package per FHIR version, with a valid `package.json`
  (the FHIR version, the core dependency and any extra dependencies the operator names), which the
  upload operation accepts unchanged. Profiles stored under a FHIR version this release
  doesn't support MUST NOT be lost: the export writes them unpackaged, as raw files kept apart from
  the packages, warns with their count, and still succeeds. A stored profile can't be malformed JSON,
  because the previous release stored it as `JSONB`, so the export doesn't need to handle that case.
  If an IG package with the same name and version as a package the export would generate is already
  stored, the export MUST fail before writing anything. It names the clash and tells the operator to
  choose another package name or version, because an upload would otherwise replace the stored
  package without warning.
- **FR-015**: The upgrade to this release MUST permanently remove the stored custom profile data and the
  storage that existed only for it, through a versioned schema migration. It MUST NOT remove or alter
  IG packages, included-IG configuration, configuration revisions or API keys. The migration runs
  unconditionally, with no safety gate or acknowledgement step, even when stored custom profiles
  were never exported. This is an accepted risk: FR-015a's warning is the only safeguard.
- **FR-015a**: The changelog and upgrade guide MUST state, before any other upgrade step, that operators
  with registered custom profiles must export them before upgrading, because the upgrade deletes them.
  They MUST also give a way to check whether any such profiles exist.
- **FR-015b**: The upgrade guide MUST require a full database backup before the upgrade. It MUST state
  that rolling back to 1.x once the upgrade has run is supported only by restoring that backup, and
  that a 1.x instance pointed at an upgraded database isn't supported. No down-migration is provided.
- **FR-015c**: The upgrade guide MUST require stopping every 1.x instance before the first 2.0 instance
  starts. It MUST say that a mixed 1.x/2.0 fleet on one database isn't supported, and that the
  rolling-restart procedure must not be used for this upgrade.
- **FR-016**: The release MUST be marked as a breaking change, under a new major version, with a
  changelog entry and upgrade guidance that cover: the removed operation, the "package it as an IG,
  upload it, include it" replacement, the changed handling of unresolved requested profiles, the
  corrected applied-profiles list, and the export step.
- **FR-017**: User-facing documentation (README, upgrade guide, configuration examples) and helper
  scripts MUST no longer describe or use profile registration, and MUST describe profiles as coming from
  included IGs.

**Tests**

- **FR-018**: Automated regression tests MUST cover: a single unresolved requested profile; an unresolved
  profile among resolved ones; a profile from a stored but not included package; a canonical URL that is
  reachable on the network but not included; the contents of the applied-profiles list; the removed
  operation answering as unknown; and a newly included IG's profile becoming usable on a second instance.

### Key Entities

- **Included IG package set**: For each FHIR version, the IG packages an administrator has included plus
  their resolved dependencies, along with the core specification. This set is now the only source of
  profiles for that version.
- **Requested profile**: A canonical URL, with an optional version, that a consumer names on a
  validation request. It is resolved against the included package set and is either applied or reported
  as unresolved.
- **Declared profile**: A profile that the submitted resource names inside its own metadata. It is
  resolved the same way. When it's unknown, it's reported at the agreed severity.
- **Applied profiles**: The list in a validation response of the profiles that were actually resolved and
  checked.
- **Stored custom profile (legacy)**: A StructureDefinition registered through the removed operation in
  an earlier release. It can be exported from the previous release, and the upgrade to this release
  permanently deletes it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Across the regression suite, 0 validation requests that name an unresolvable profile are
  reported as valid. Today at least one such case returns valid.
- **SC-002**: 0 outbound network requests are made while serving validation requests, including requests
  that name canonical URLs that are reachable on the internet. This is verified with outbound traffic
  blocked or monitored during the test run.
- **SC-003**: 100% of validation responses list only profiles that were actually applied.
- **SC-004**: Validating the same resource with the same requested profiles against the same included
  package set gives identical results online and offline, and on every instance.
- **SC-005**: A profile from a newly included IG is usable on every instance within the propagation
  window already guaranteed for IG changes. This feature causes no regression.
- **SC-006**: For warm single-resource validation of the dk Patient fixture with one requested profile,
  p95 latency on this release is within **+5%** of 1.2.0 on the same host and workload, and stays
  **under 200 ms**. Steady-state heap after warm-up is no higher than 1.2.0's. Removing the custom
  profile store is expected to lower it slightly.
- **SC-007**: An operator with registered profiles from the previous release can follow the upgrade
  guide to export them before upgrading, upgrade, upload and include the exported packages, and get the same
  validation results as before the upgrade, in under 30 minutes for a typical set of up to 50 profiles.
  The upgrade rehearsal covers this path.
- **SC-008**: The published API description, documentation, metrics and health report contain 0
  references to custom profile registration.

## Assumptions

- **Scope is included IGs only.** Profiles come only from IG packages included for a FHIR version
  (CLI `-ig` semantics). Merely uploading or registering a package does not make its profiles
  available. Scope question 4 from the input was settled by the feature description itself, which
  states this model explicitly.
- **Out of scope:** the treatment of unknown extensions, implied profiles (such as vital signs),
  always loading the terminology and core-extension packages, terminology routing, and cross-version IG
  conversion. These are report findings #4 to #8. Existing behaviour is kept, and any change belongs to
  a separate feature.
- **Versioned canonicals:** a request's `|version` selects that version, and without a version the
  choice is deterministic (FR-003). This is a reasonable default, not a clarification point. The exact
  selection rule is a planning decision.
- **Status codes from `001-service-hardening` stay as they are:** a completed validation still answers
  "success" whether the resource is valid or not, an unparseable body is still a client error, and a
  validator that isn't ready is still refused with a retry hint. The only new client errors are a profile
  list over the FR-006a limits (`invalid-request`, checked with the other request-shape checks) and an
  unresolved requested profile (`unknown-profile`, checked after readiness and after the body has
  parsed as FHIR).
- **Unused legacy column:** the never-read list of included profiles in the validator configuration
  table is removed in the same migration as the custom profile storage, because it existed only for
  this feature.
- **Version bump:** the project constitution (Principle IV) requires a MAJOR version bump for removing an
  operation. The next release is therefore 2.0.0, not 1.3.0.
- **Constitution wording:** Principle II of the constitution names "profiles" and "the profile stores" as
  shared state and derived caches. After this feature those words refer to IG packages only, so the
  constitution needs a wording amendment (PATCH), recorded in the plan.
- **Unchanged guarantees:** cross-instance propagation, administrator authentication, admission limits,
  error format and the supported FHIR versions (STU3, R4, R4B, R5) are unchanged.
- **The audit trail stays deferred**, as decided in `001-service-hardening`.
