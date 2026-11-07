# Feature Specification: Organisation Ownership (Tenants Own API Keys, IG Packages and Validator Snapshots)

**Feature Branch**: `005-organisation-ownership`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "@var/specify_instruction_organisation_ownership.md". In summary: introduce
organisations as the owner of everything the service stores. Each API key belongs to one organisation. Each stored
IG package and each validator snapshot has an owner organisation and a shareable flag. A caller can use a package
or snapshot only when its organisation owns it or it is shareable. A pre-installed system organisation owns
everything that exists before this feature. Operators manage organisations from the command line (create, update,
deactivate), with input from a JSON file and/or one argument per field.

**Background**: `specs/001-service-hardening/spec.md` (API keys and roles, shared revisioned configuration,
propagation, auth cache), `specs/003-ig-only-profile-resolution/spec.md` (profiles only from IG packages) and
`specs/004-ig-scoped-validation/spec.md` (one validator snapshot per IG package and FHIR version, on-demand builds,
include/exclude, registration).

## Context

Today the service has no notion of a tenant. An API key carries only a role (administrator or validator). Every
stored IG package and every included IG is visible to every caller, and any administrator key can include,
exclude, upload or register anything. One deployment therefore can't serve several customers whose IGs must stay
private to them.

This feature adds the **organisation** as the unit of ownership. API keys, stored IG packages and validator
snapshots each belong to one organisation. Packages and snapshots are either **shareable** (usable by every
caller) or **private** (usable only by the owner). A built-in **system organisation** owns everything that exists
when the feature is installed, and everything stays shareable, so single-tenant deployments keep working without
changes.

## Clarifications

### Session 2026-11-06

- Q: What happens when organisation B includes an IG that organisation A has already included for the same FHIR
  version? → A: B's include succeeds without changing anything. A stays the owner, and B uses A's snapshot if B may
  use it (shareable). If B may not use it, the include is refused as `package-not-found` (HTTP 404). There is one
  snapshot per (IG, FHIR version), not one per organisation. B depends on A's choices: if A excludes it or makes
  it private, B loses it.
- Q: What is the default shareable flag for packages newly stored by register or upload? → A: Always shareable
  unless the request explicitly asks for private. The package licence isn't used.
- Q: Can ownership or sharing be changed after a package or snapshot is stored? → A: Yes. The owner and the system
  organisation can change the shareable flag of a package or snapshot. Only the system organisation can transfer
  ownership to another organisation. Both are available as administrative operations and from the command line,
  and they propagate to every instance.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Keep an organisation's private IGs private during validation (Priority: P1)

An organisation registers or uploads an IG package and marks it private. Its own consumers validate against that
IG as usual. Consumers of every other organisation, and anonymous callers, can't validate against it, can't read
its dependencies or conformance resources, and can't tell that it exists. Shareable IGs stay usable by everyone.

**Why this priority**: This is the security guarantee the feature exists for. Without it, organisations add
bookkeeping but no protection.

**Independent Test**: With two organisations A and B, store a private package owned by A and a shareable package
owned by B. Validate against each with A's key, B's key and no key. Confirm that only A (and the system
organisation) can use A's private package, that everyone can use B's shareable one, and that a refused request
looks exactly like a request for a package that isn't stored.

**Acceptance Scenarios**:

1. **Given** a private package `P#v` owned by A, **When** a consumer with A's key validates naming `ig=P#v`,
   **Then** the resource is validated against `P#v` as in spec 004.
2. **Given** the same package, **When** a consumer with B's key, or an anonymous caller, names `ig=P#v`, **Then**
   the request is rejected as `package-not-found` (HTTP 404), with the same text as for a package that isn't
   stored, and no snapshot is built.
3. **Given** a snapshot for `P#v` was already built on an instance for A, **When** B's key names `ig=P#v` on that
   instance, **Then** B is refused as in scenario 2. A snapshot that's already built never bypasses the check.
4. **Given** a shareable package `S#v` owned by A that depends on a private package `Q#w` owned by C, **When** B
   names `ig=S#v`, **Then** the request is rejected as `dependency-not-found` (HTTP 424) without naming `Q#w`, and
   nothing is built. **When** C names it, **Then** it validates.
5. **Given** a shareable package owned by any organisation, **When** any caller, including an anonymous one if
   anonymous validation is enabled, names it, **Then** it validates.
6. **Given** no `ig` is named, **When** any caller validates, **Then** the base context (core only) is used. It's
   owned by the system organisation, always shareable and open to everyone.
7. **Given** a private package owned by A, **When** B requests its dependencies or conformance resources, **Then**
   the answer is `package-not-found` (HTTP 404).
8. **Given** a validation response, **When** the request used an IG context, **Then** the response identifies the
   context's owner organisation (short name) and whether it is shareable.

---

### User Story 2 - Organisation administrators manage only their own content (Priority: P1)

An organisation's administrator registers, uploads, includes and excludes IGs for their organisation. What they
store or include is owned by their organisation and is shareable unless they say otherwise. They can't exclude,
replace or include other organisations' private content. Administrators of the system organisation can act on
everything (operator role).

**Why this priority**: Without ownership on writes, any administrator could replace or remove another
organisation's packages, and privacy would mean nothing.

**Independent Test**: With administrator keys for A, B and the system organisation, register, upload, include and
exclude packages across organisations. Confirm the owner recorded on each, the default and explicit shareable
flags, and which cross-organisation actions are refused.

**Acceptance Scenarios**:

1. **Given** A's administrator key, **When** a package is registered by name (and its dependencies are
   downloaded), **Then** every package newly stored by that call is owned by A and is shareable unless the request
   asks for private (FR-016).
2. **Given** a package already stored and owned by B, **When** A registers the same `name#version`, **Then** its
   owner and shareable flag don't change. It's returned to A if A may use it, and otherwise the answer is
   `package-not-found` (HTTP 404).
3. **Given** a package stored and owned by B, **When** A uploads an archive with the same `name#version`, **Then**
   the upload is refused as `conflict` (HTTP 409), and B's package and the snapshots built from it are untouched.
   **When** B (or the system organisation) uploads it, **Then** it replaces the stored package as in spec 004.
4. **Given** A's administrator key, **When** A includes `P#v` for a FHIR version, **Then** `P#v` and every resolved
   dependency must be usable by A. The pinned snapshot is owned by A and is shareable unless the request asks for
   private. The include response reports the owner and the shareable flag.
5. **Given** a snapshot included by A, **When** B's administrator excludes it, **Then** the answer is
   `package-not-found` (HTTP 404) and nothing changes. **When** A or the system organisation excludes it, **Then**
   it's excluded as in spec 004.
6. **Given** a system organisation administrator key, **When** it registers, uploads, includes or excludes any
   organisation's content, **Then** the action is allowed, and existing owners are kept unless the action creates a
   new package or snapshot.
7. **Given** `P#v` is already included for a FHIR version by A and is shareable, **When** B's administrator
   includes `P#v` for that version, **Then** the include succeeds, nothing is rebuilt, and the response reports A as
   the owner. **When** the snapshot is private to A, **Then** B gets `package-not-found` (HTTP 404) and nothing
   changes.
8. **Given** a package or snapshot owned by A, **When** A's administrator (or the system organisation) changes its
   shareable flag, **Then** the new flag applies on every instance within the propagation window. **When** B's
   administrator tries it, **Then** the answer is `package-not-found` (HTTP 404).
9. **Given** a package or snapshot owned by A, **When** the system organisation transfers its ownership to B,
   **Then** B is the owner from then on. **When** A's administrator tries to transfer ownership, **Then** it's
   refused with HTTP 403.
10. **Given** a validator key (not an administrator) of any organisation, **When** it calls an administrative
   operation, **Then** it's refused as today (HTTP 403).

---

### User Story 3 - Operators manage organisations and their API keys from the command line (Priority: P1)

An operator creates an organisation from a JSON file, from command-line arguments or both, updates its details,
deactivates or reactivates it, lists organisations, and creates or revokes API keys for an organisation. The
command line works against the shared database without a running HTTP server and reports results in a form
scripts can use.

**Why this priority**: Organisations and their keys can't be created any other way, so the other stories depend
on this one. Today keys are inserted with raw database statements.

**Independent Test**: Create an organisation from a file with one field overridden by an argument, update one
field, create a validator key for it and use that key, deactivate the organisation and confirm the key stops
working, reactivate it and confirm the key works again.

**Acceptance Scenarios**:

1. **Given** a JSON file with the organisation fields, **When** the operator creates an organisation from the file
   and also passes some field arguments, **Then** the organisation is created with argument values taking
   precedence over file values, and the command prints the new organisation (including its id and short name) as
   JSON and exits with status 0.
2. **Given** input missing a required field (short name, long name or type), or with a malformed email, website or
   country code, **When** the operator creates or updates an organisation, **Then** nothing is changed, a one-line
   error names the field, and the exit status is non-zero.
3. **Given** an existing short name (compared case-insensitively), **When** another organisation is created with
   it, **Then** creation is refused with a non-zero exit status.
4. **Given** an existing organisation, **When** the operator updates it by id or short name with some fields,
   **Then** only those fields change and the "updated" time is set.
5. **Given** an active organisation with keys, **When** the operator deactivates it, **Then** its keys are refused
   (HTTP 403, "organisation is deactivated") on every instance within the existing auth-cache window, and its
   stored packages and snapshots are kept. **When** it is reactivated, **Then** its keys work again within the
   same window.
6. **Given** any input, **When** the operator tries to create a second system organisation, change the system
   organisation's type or deactivate it, **Then** the command is refused with a non-zero exit status.
7. **Given** an organisation, **When** the operator creates an API key for it with a name and a role, **Then** the
   key is printed once and stored only as a hash, owned by that organisation. **When** the operator revokes it,
   **Then** it's refused within the auth-cache window.
8. **Given** an operator lists or shows organisations, **When** the command runs, **Then** it prints JSON and
   exits with status 0.

---

### User Story 4 - Existing deployments upgrade without behaviour changes (Priority: P2)

An operator upgrades a single-tenant deployment. A system organisation is created, populated from environment
settings or defaults. Every existing API key, stored package and included snapshot becomes owned by it and
shareable. Existing clients keep working with no change to their requests or keys.

**Why this priority**: Most current deployments are single-tenant. The feature must not break them.

**Independent Test**: Upgrade a database populated by the previous release, then run the existing regression
suite and the previous release's client calls unchanged.

**Acceptance Scenarios**:

1. **Given** a database from the previous release, **When** the new release starts, **Then** exactly one system
   organisation exists, and every existing API key, stored package and included snapshot is owned by it and (for
   packages and snapshots) shareable.
2. **Given** environment settings for system organisation fields, **When** the service starts, **Then** the set
   values are applied to the system organisation, and fields with no setting keep their stored value (or the
   default on first creation).
3. **Given** the bootstrap administrator key from configuration, **When** the service starts, **Then** that key
   belongs to the system organisation.
4. **Given** the upgraded deployment, **When** previous-release clients call every endpoint with the same keys and
   requests, **Then** they get the same results as before the upgrade.

---

### Edge Cases

- **Inactive owner of a shareable package**: An organisation is deactivated. Its shareable packages and snapshots
  stay usable by others. Its private ones can be used only by the system organisation until it's reactivated.
- **Two organisations include the same IG**: Snapshot and package identities are global (one per `name#version`
  and FHIR version). B includes `P#v` that A has already included. B's include succeeds without changing the owner
  if B may use A's snapshot, and is refused with HTTP 404 otherwise (FR-018). If A later excludes the snapshot or
  makes it private, B loses it. Excluding it on B's behalf isn't possible, because only the owner and the system
  organisation can exclude.
- **Shareable snapshot over a package that later becomes private**: A package's shareable flag or owner changes
  (FR-019). Every snapshot containing it is re-checked on every instance within the existing
  propagation window. Callers who lost access are refused from then on, and requests already running finish.
- **On-demand snapshot ownership**: A snapshot built on demand (not included) takes the owner and shareable flag of
  the IG package it was built for. Access also requires that every dependency is usable by the caller (FR-010).
- **Anonymous validation disabled**: When anonymous validation is turned off, every validation call needs a key,
  as today. The ownership rules then apply to that key's organisation.
- **Revealing existence through errors**: Error responses, warnings and dependency errors never name a package the
  caller may not use. A missing dependency the caller may not see is reported generically.
- **Health details**: The unauthenticated health details don't list owners, and don't list keys of private
  snapshots. They show counts and states only for those.
- **Key of an organisation deleted from the database by hand**: Organisations are never deleted by the service.
  If one is removed by hand, references prevent it while keys, packages or snapshots still point to it.
- **Concurrent ownership races**: Two organisations register or upload the same new `name#version` at the same
  time. Exactly one stores it and becomes the owner. The other gets the outcome of scenario US2-2 or US2-3.
- **Deactivating the organisation whose key the operator is using**: The command line authenticates through
  database access, not through API keys, so it's unaffected.

## Requirements *(mandatory)*

### Functional Requirements

**Organisations**

- **FR-001**: The system MUST store organisations with: id, short name, long name, type (system, commercial,
  non-commercial), active flag, address lines (an ordered list), post code, country, email, phone, website,
  contact person name, contact person mobile, contact person email, created time and updated time.
- **FR-002**: Short name, long name and type MUST be required. Short names MUST be unique, compared
  case-insensitively. Country MUST be an ISO 3166-1 alpha-2 code. Emails and website MUST be well formed when
  given.
- **FR-003**: There MUST be exactly one system organisation, created on installation or upgrade with a stable,
  well-known identity. It MUST NOT be possible to create another organisation of type system, change the system
  organisation's type, or deactivate it.
- **FR-004**: The system organisation's fields MUST be settable from environment configuration. At each start,
  every configured field overwrites the stored value. Unconfigured fields keep their stored value, and on first
  creation they take documented defaults (short name `system`, long name `FHIR Validation Service`).
- **FR-005**: Organisations MUST NOT be deleted by the service. Deactivation is the only way to remove an
  organisation from use.

**Ownership**

- **FR-006**: Every API key MUST belong to exactly one organisation. The bootstrap administrator key from
  configuration MUST belong to the system organisation.
- **FR-007**: Every stored IG package MUST have an owner organisation and a shareable flag.
- **FR-008**: Every validator snapshot MUST have an owner organisation and a shareable flag. For an included
  (pinned) snapshot, the owner and flag are recorded with the inclusion, per snapshot (not per FHIR version), and
  are part of the shared, revisioned configuration. A snapshot built on demand takes the owner and flag of its IG
  package. The base (core-only) snapshot is owned by the system organisation and is always shareable.
- **FR-009**: Upgrading MUST assign every existing API key, package and included snapshot to the system
  organisation, and mark every existing package and snapshot shareable.

**Access rule**

- **FR-010**: A caller MUST be allowed to use a package or snapshot only when (a) the caller's organisation owns
  it, or (b) it is shareable, or (c) the caller belongs to the system organisation. Using a snapshot also requires
  that every package in its resolved set (the IG and all its dependencies) passes the same rule for the caller.
- **FR-011**: An anonymous caller (when anonymous validation is enabled) MUST be allowed to use only shareable
  packages and snapshots whose whole resolved set is shareable, plus the base snapshot.
- **FR-012**: A refusal under the access rule MUST be indistinguishable from the resource not existing: the named
  IG gets `package-not-found` (HTTP 404), and a dependency the caller may not use gets `dependency-not-found` (HTTP
  424) without naming that dependency.
- **FR-013**: The access check MUST run before an on-demand build is admitted, so a caller can't trigger a build
  it may not use. For a snapshot that's already built, the check MUST use ownership data held in memory and MUST
  NOT add a database round trip to validation.
- **FR-014**: API keys of inactive organisations MUST be refused with HTTP 403 ("organisation is deactivated") on
  every endpoint that accepts keys. Activation changes MUST take effect on every instance within the existing
  auth-cache window.

**Applying the rule**

- **FR-015**: Validation with `ig`, on-demand builds, include, exclude, register, upload and the package read
  endpoints (dependencies, conformance) MUST apply the access rule to the caller.
- **FR-016**: Packages newly stored by register or upload MUST be owned by the caller's organisation (the system
  organisation for the bootstrap key). Their shareable flag is the request's explicit value when given, and
  shareable otherwise. The package's declared licence MUST NOT affect the flag.
- **FR-017**: Register MUST NOT change the owner or shareable flag of a package already stored. Upload of a
  `name#version` already stored and owned by another organisation MUST be refused as `conflict` (HTTP 409), except
  for the system organisation.
- **FR-018**: Including a snapshot that isn't included yet MUST record the caller's organisation as its owner and
  the request's shareable value (default shareable). Including a snapshot already included by another
  organisation MUST succeed without changing its owner or flag when the caller may use it, and MUST be refused as
  `package-not-found` (HTTP 404) otherwise. There is one snapshot per (IG package, FHIR version). Exclude MUST be
  allowed only for the snapshot's owner and the system organisation; anyone else gets HTTP 404.
- **FR-019**: The owner of a package or included snapshot, and the system organisation, MUST be able to change its
  shareable flag. Only the system organisation MUST be able to transfer ownership to another active organisation,
  and the owner's administrator who tries gets HTTP 403. Both MUST be available as administrative operations and
  from the command line. Changes MUST take effect on every instance within the existing propagation window, and
  every built snapshot containing the changed package MUST be re-checked against the new ownership. A
  non-owner, non-system caller trying to change the flag **or transfer ownership** gets HTTP 404; the 404 check
  comes before the 403 check, so a refusal never reveals that a private package or snapshot exists. HTTP 403 for a
  transfer is returned only to the owner.
- **FR-020**: Responses for include, register and upload, and validation responses that used an IG context, MUST
  report the owner organisation's short name and the shareable flag.
- **FR-021**: Unauthenticated health details MUST NOT disclose owners or the identities of private snapshots.

**Command line**

- **FR-022**: The service distribution MUST provide a command line to create, update, deactivate, activate, list
  and show organisations. It works against the shared database with the service's existing database configuration
  and doesn't need a running server. Without a command, the distribution starts the service as today.
- **FR-023**: Create and update MUST accept input from a JSON file, from one argument per field, or both, with
  arguments taking precedence. Address lines MUST be given as a list in the file and as a repeatable argument.
  Update MUST identify the organisation by id or short name and change only the fields given.
- **FR-024**: The command line MUST create and revoke API keys for an organisation, with a name and a role. A new
  key is printed once and stored only as a hash.
- **FR-025**: Command results MUST be printed as JSON. Failures (invalid input, duplicate short name, unknown
  organisation, refused system-organisation change) MUST print a one-line error and exit with a non-zero status.
- **FR-026**: Organisation, key, sharing and ownership changes made from the command line MUST be recorded in the
  service log with the organisation short name and the change, never the key value.

### Key Entities

- **Organisation**: A tenant that owns content. Attributes: id, short name, long name, type, active flag, address
  lines, post code, country, email, phone, website, contact person (name, mobile, email), created and updated
  times.
- **System organisation**: The single built-in organisation of type system. Owns pre-existing content, the base
  snapshots and the bootstrap key. Can act on every organisation's content. Can't be deactivated.
- **API key**: Credential with a role (administrator or validator), owned by one organisation.
- **Stored IG package**: A `name#version` with an owner organisation and a shareable flag, in addition to the
  attributes from spec 004.
- **Validator snapshot**: A prepared validation context for one (IG package, FHIR version), with an owner
  organisation and a shareable flag (recorded for included snapshots, inherited from the package for on-demand
  ones).
- **Caller identity**: For each request, the organisation of the API key (or none for anonymous), its active
  state, and the key's role.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After upgrading a database from the previous release, 100% of existing API keys, packages and
  included snapshots are owned by the system organisation, every package and snapshot is shareable, and the
  previous release's regression suite passes unchanged.
- **SC-002**: Across a test matrix of owner, non-owner, system and anonymous callers × private and shareable
  content × validate, include, exclude, register, upload and read, 100% of outcomes match the access rule, and 0
  refusals are distinguishable from "not stored".
- **SC-003**: A caller that may not use a package triggers 0 snapshot builds and 0 downloads for it.
- **SC-004**: A snapshot built in memory for one organisation from private content is served to 0 requests from
  other organisations.
- **SC-005**: Deactivating an organisation stops 100% of its keys on every instance within the auth-cache window
  (default 30 seconds), and reactivating restores them within the same window.
- **SC-006**: An operator can create an organisation and issue its first key with two commands, in under one
  minute, without writing database statements.
- **SC-007**: Validation against an already built snapshot keeps the existing target of p95 under 200 ms for a
  single resource, and the ownership check adds no measurable latency (benchmark in the PR).
- **SC-008**: Regression tests cover: anonymous access to shareable and private content; owner, non-owner and
  system administrator on include, exclude, upload and register; dependency ownership on on-demand builds; the
  shareable default on register and upload; an upload conflicting with another organisation's package;
  deactivation and reactivation; shareable-flag changes and ownership transfer; including an IG already included by another organisation; system organisation protections; and file plus argument merging on the command
  line.

## Assumptions

- **Anonymous callers** (input question 2): when anonymous validation is enabled, they get shareable content and
  the base snapshot (FR-011). Turning anonymous validation off stays available, as today.
- **System organisation privileges** (input question 3): its administrator keys act on every organisation's
  content.
- **Refusal status** (input question 4): 404 (and 424 for dependencies) to hide existence, not 403.
- **System organisation environment settings** (input question 7): applied at every start, like the bootstrap key.
- **Deactivated organisations** (input question 10): their shareable content stays usable by others, and their
  private content can be used only by the system organisation.
- **API keys on the command line** (input question 11): in scope (FR-024), since otherwise organisations couldn't
  get keys without database statements.
- **Field and column naming** (input question 12): a planning decision. The project's existing naming conventions
  apply.
- **Organisation type** is descriptive. It doesn't change access or enforce the licence terms from spec 002.
- **Compatibility**: with the defaults (system owner, shareable), existing clients see no change, so the release
  is a minor version (3.1.0) unless planning finds a break. Rolling upgrades from 3.0 follow the upgrade notes that
  planning produces. Defaulting ownership to the system organisation at the storage level is preferred, so 3.0
  instances keep working during the upgrade.
- **Dependencies**: relies on spec 001 (keys, roles, auth cache, propagation), spec 003 (profile resolution) and
  spec 004 (snapshots, on-demand builds, include/exclude, registration).
- **Out of scope**: an HTTP API for managing organisations or keys, per-organisation quotas or memory budgets,
  sharing with selected organisations only (sharing is all or nothing), and audit trails beyond the service log.
