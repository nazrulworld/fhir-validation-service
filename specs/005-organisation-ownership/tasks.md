---

description: "Task list for organisation ownership (tenants own API keys, IG packages and validator snapshots)"
---

# Tasks: Organisation Ownership (Tenants Own API Keys, IG Packages and Validator Snapshots)

**Input**: Design documents from `specs/005-organisation-ownership/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R14), data-model.md, contracts/http-api.md, contracts/cli.md,
quickstart.md

**Tests**: These are required. Constitution Principle V requires test-first development against real PostgreSQL
through Testcontainers, and SC-008 lists the regression tests. Write each story's tests first and confirm they fail
before implementing.

**Organization**: Tasks are grouped by user story, in priority order: US1 (P1), US2 (P1), US3 (P1), US4 (P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: The user story the task belongs to (US1–US4)
- Production paths are written in full from the repository root (`src/main/java/nzi/fhir/validator/...`). Test paths
  are under `src/test/java/nzi/fhir/validator/`.
- `SYSTEM_ID` means `00000000-0000-0000-0000-000000000001` everywhere.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Branch, version bump, shared test fixtures.

- [X] T001 Create and switch to git branch `005-organisation-ownership` from `main` (repository root)
- [X] T002 Bump the project version from `3.0.0` to `3.1.0-SNAPSHOT` in `pom.xml` (additive release, research R11)
- [X] T003 [P] Add test helpers to `src/test/java/nzi/fhir/validator/testsupport/TestPackages.java`:
  - `createOrganisation(Pool pool, String nameShort) → Future<UUID>`, which inserts a `COMMERCIAL` organisation by
    SQL;
  - `createKey(Pool pool, UUID owner, String name, String role) → Future<String>`, which inserts a random
    43-character key's SHA-256 hash with that owner and returns the key;
  - `setPackageAccess(Pool pool, String nameAndVersion, UUID owner, boolean shareable) → Future<Void>`, which does a
    plain SQL `UPDATE` of `fhir_implementation_guides`, bumps `fhir_package_catalog.revision` and runs
    `pg_notify('fv_config_changed', {"kind":"PACKAGE","key":…})` in one transaction.
- [X] T004 [P] Add `AppHarness.as(HttpRequest<T> request, String key)` (sets `Authorization: Bearer <key>`) next to
  `asAdmin` in `src/test/java/nzi/fhir/validator/testsupport/AppHarness.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The contract, the schema, the organisation domain, caller identity and the in-memory access model.
Behaviour is unchanged for existing (system-owned, shareable) data. Every user story depends on this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Contract and schema

- [X] T005 Update `src/main/resources/openapi.yaml` exactly as `contracts/http-api.md` §0–§8 describes:
  - the components `OwnershipFields` and `AccessChangeRequest` (`minProperties: 1`, `additionalProperties: false`,
    `owner` pattern `'^[A-Za-z0-9_-]{2,32}$'`);
  - `igOwner`/`igShareable` on `ValidationResponse`, and the `X-Validation-Context-Owner` and
    `X-Validation-Context-Shareable` headers on validate 200;
  - `shareable` (boolean, default `true`) on the include body, the register body and the upload multipart form;
  - `owner`/`shareable` on `IncludeResponse` and `IgRegistrationResponse`;
  - the new operations `validationApiChangeIncludedIgAccess` (`PATCH /{version}/include-ig`, required query `ig`)
    and `igPackageApiChangeAccess` (`PATCH /igs/{name}/{version}`), with `IncludeAccessResponse` and
    `IgAccessResponse`;
  - `409` on upload and register;
  - the 403, 404 and 424 description texts;
  - `privateContexts` in the `/health` detail shape.
- [X] T006 Create `src/main/resources/db/migration/V8__organisations.sql` exactly as data-model §1 describes:
  - **`organisations`**:
    - `id UUID PK DEFAULT gen_random_uuid()`;
    - `name_short VARCHAR(32) NOT NULL`, with CHECK `name_short ~ '^[A-Za-z0-9_-]{2,32}$'`;
    - `name_long VARCHAR(256) NOT NULL`;
    - `type VARCHAR(16) NOT NULL CHECK (type IN ('SYSTEM','COMMERCIAL','NON_COMMERCIAL'))`;
    - `is_active BOOLEAN NOT NULL DEFAULT true`;
    - `address_lines TEXT[] NOT NULL DEFAULT '{}'`;
    - `post_code VARCHAR(16)`;
    - `country CHAR(2)`, with CHECK `country ~ '^[A-Z]{2}$'`;
    - `email VARCHAR(254)`, `phone VARCHAR(32)`, `website VARCHAR(256)`;
    - `contact_person_name VARCHAR(128)`, `contact_person_mobile VARCHAR(32)`, `contact_person_email VARCHAR(254)`;
    - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NULL`.
  - **Constraints on `organisations`**:
    - `organisations_system_id_chk`: `(type = 'SYSTEM') = (id = '00000000-0000-0000-0000-000000000001')`;
    - `organisations_system_active_chk`: `type <> 'SYSTEM' OR is_active`;
    - unique partial index `organisations_one_system_uq ON organisations ((true)) WHERE type = 'SYSTEM'`;
    - unique index `organisations_name_short_uq ON organisations (lower(name_short))`.
  - **Seed**: `(SYSTEM_ID, 'system', 'FHIR Validation Service', 'SYSTEM', true)`, with `ON CONFLICT DO NOTHING`.
  - **`api_keys`** and **`fhir_implementation_guides`**: `owner UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001' REFERENCES fhir_validator_schema.organisations(id) ON DELETE RESTRICT`.
  - **`fhir_implementation_guides`** also gets `is_shareable BOOLEAN NOT NULL DEFAULT true`.
  - **`fhir_validator_snapshots`**:
    - `fhir_version VARCHAR(16) NOT NULL`, `ig_package VARCHAR(192) NOT NULL`;
    - `owner UUID NOT NULL DEFAULT SYSTEM_ID REFERENCES organisations(id) ON DELETE RESTRICT`;
    - `is_shareable BOOLEAN NOT NULL DEFAULT true`;
    - `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `updated_at TIMESTAMPTZ NULL`;
    - `PRIMARY KEY (fhir_version, ig_package)`.
  - **Backfill**: `INSERT INTO fhir_validator_snapshots (fhir_version, ig_package) SELECT fhir_version, unnest(included_ig_packages) FROM fhir_validator_logs ON CONFLICT DO NOTHING`.
  - **Indexes**: `api_keys_owner_idx`, `fhir_implementation_guides_owner_idx`,
    `fhir_implementation_guides_private_idx (owner) WHERE NOT is_shareable`, `fhir_validator_snapshots_owner_idx`.
  - Add a header comment stating 3.1.0, research R1, and the rolling-upgrade defaults (R11).
- [X] T007 Write `MigrationUpgradeIT` cases first, in `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java`:
  - (a) V8 on an empty database: one `SYSTEM` row with id `SYSTEM_ID`;
  - (b) 3.0.0 → 3.1.0 with two keys, three packages and two included IGs on R4: every key and package has
    `owner = SYSTEM_ID`, every package `is_shareable = true`, and two `fhir_validator_snapshots` rows owned by system
    and shareable;
  - (c) inserting a second `SYSTEM` organisation fails, and so does deactivating the system organisation, both by
    SQL constraint;
  - (d) a duplicate `name_short` differing only in case fails.

### Organisation domain (research R2)

- [X] T008 [P] Create `src/main/java/nzi/fhir/validator/core/org/Organisations.java`:
  - `public static final UUID SYSTEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")`;
  - `enum Type { SYSTEM, COMMERCIAL, NON_COMMERCIAL }`.
- [X] T009 [P] Write `src/test/java/nzi/fhir/validator/core/org/OrganisationTest.java` first. It covers every
  `Organisation.validate()` rule in T010, accepts each boundary value and rejects one past it, and checks that
  error messages name the field.
- [X] T010 Create the record `src/main/java/nzi/fhir/validator/core/org/Organisation.java` with the fields `id,
  nameShort, nameLong, type, active, addressLines, postCode, country, email, phone, website, contactPersonName,
  contactPersonMobile, contactPersonEmail, createdAt, updatedAt`, plus `toJson()`/`fromJson()` (camelCase as in
  contracts/cli.md) and `validate()`. `validate()` throws `IllegalArgumentException` naming the field. Rules:
  - `nameShort` `^[A-Za-z0-9_-]{2,32}$`;
  - `nameLong` "1–256 characters, trimmed";
  - `type` required;
  - `addressLines` "at most 6 lines, each at most 128 characters";
  - `postCode` at most 16;
  - `country` "ISO 3166-1 alpha-2" (`^[A-Z]{2}$` and in `Locale.getISOCountries()`);
  - `email`/`contactPersonEmail` well-formed address, at most 254;
  - `phone`/`contactPersonMobile` `^[+0-9 ()-]{3,32}$`;
  - `website` absolute `http`/`https` URL, at most 256;
  - `contactPersonName` at most 128.
- [X] T011 Create `src/main/java/nzi/fhir/validator/core/org/OrganisationRepository.java`. Every method is
  `Future`-returning and uses pg-client:
  - `create(Organisation)`: validates; refuses type `SYSTEM`; a unique violation on `organisations_name_short_uq`
    becomes `ProblemException(409, "conflict", …)`.
  - `update(UUID id, Organisation patch, Set<String> fields)`: only the listed fields change, and `updated_at =
    now()`; a type change to or from `SYSTEM` gets 409. A `nameShort` change also does `UPDATE
    fhir_package_catalog SET revision = revision + 1` and `pg_notify('fv_config_changed',
    {"kind":"PACKAGE","key":null})` in the same transaction (research R8).
  - `setActive(UUID id, boolean)`: 409 for `SYSTEM_ID` with `false`.
  - `findById`, `findByShortName` (case-insensitive), `list(Optional<Boolean> active)` ordered by `name_short`.
  - `applySystemConfig(Map<String,Object> fields)`: updates only the given fields of `SYSTEM_ID`.
- [X] T012 [P] Write `src/test/java/nzi/fhir/validator/core/org/OrganisationRepositoryIT.java` (Testcontainers):
  create, update a subset, the case-insensitive duplicate gives 409, system protections give 409, a rename bumps the
  catalog revision, and `list` filtering. It must fail before T011.

### Caller identity (research R3)

- [X] T013 [P] Create `src/main/java/nzi/fhir/validator/core/security/Caller.java`:
  - `record Caller(UUID orgId, String orgShortName, ApiKeyRepository.Role role, boolean system, boolean anonymous)`;
  - `static final Caller ANONYMOUS`;
  - `static Caller system(String shortName)` for internal and command-line use.
- [X] T014 Extend `src/main/java/nzi/fhir/validator/core/security/ApiKeyRepository.java`:
  - `KeyInfo(UUID id, Role role, UUID ownerId, String ownerShortName, boolean ownerActive)`;
  - `findActive` joins `organisations o ON o.id = k.owner`;
  - `save(String name, String key, Role role, UUID owner)` (the old overload delegates with `SYSTEM_ID`);
    `upsertBootstrap` always writes `owner = SYSTEM_ID` (FR-006) and also sets `owner` on conflict;
  - `list(Optional<UUID> owner)` returns `name, role, owner short name, created_at, last_used_at, revoked`, never
    hashes.
- [X] T015 Extend `src/test/java/nzi/fhir/validator/core/security/ApiKeyAuthHandlerTest.java` and
  `src/test/java/nzi/fhir/validator/web/ApiKeyAuthIT.java` first:
  - a key of an inactive organisation gets 403 `forbidden` on validate and on admin paths;
  - after `setActive(true)` it passes again within `FV_AUTH_CACHE_TTL_MS` (use a 200 ms TTL in the test);
  - `Caller` is present in the context with the right organisation, and anonymous requests get `Caller.ANONYMOUS`;
  - `PATCH /igs/a/1.0.0` without ADMIN gets 401/403.
- [X] T016 Update `src/main/java/nzi/fhir/validator/core/security/ApiKeyAuthHandler.java`:
  - add `public static final String CALLER_KEY = "caller"`;
  - put `Caller` in the context for authenticated and anonymous requests;
  - refuse `ownerActive == false` with `ProblemException(403, "forbidden", "The organisation of this API key is
    deactivated")`;
  - add a static `caller(RoutingContext)` helper that defaults to `Caller.ANONYMOUS`;
  - extend `ADMIN_PATHS` to `^/(igs/(upload|register)|igs/[^/]+/[^/]+|[^/]+/include-ig)/?$`, and keep the
    validate-path regex for `/igs/{n}/{v}/(dependencies|conformance)`.

### In-memory access model (research R4)

- [X] T017 [P] Create `src/main/java/nzi/fhir/validator/core/security/Access.java` (`record Access(UUID owner,
  String ownerShortName, boolean shareable)`, with `static Access systemShared(String shortName)`).
- [X] T018 [P] Write `src/test/java/nzi/fhir/validator/core/security/AccessPolicyTest.java` first: the full matrix of
  {owner, other organisation, system, anonymous} × {shareable, private} for `canUse`, plus `canUseContext` for:
  - a base key;
  - a pinned key whose `PinnedAccess` differs from its package;
  - an on-demand key;
  - a shareable IG with one private dependency (refused for others, allowed for the dependency owner and for system).
- [X] T019 Create `src/main/java/nzi/fhir/validator/core/security/AccessPolicy.java`:
  - `static boolean canUse(Caller, Access)` = `a.shareable() || c.system() || (!c.anonymous() &&
    c.orgId().equals(a.owner()))`;
  - `static Decision canUseContext(Caller, SnapshotKey, Collection<String> resolvedPackages, PackageCatalogView,
    PinnedAccess)`, returning `ALLOWED`, `IG_NOT_VISIBLE` or `DEPENDENCY_NOT_VISIBLE`, as data-model §2 describes
    ("snapshotAccess(k) = PinnedAccess[k] if k is pinned, = catalog.entry(k.igPackage).access otherwise"). It is
    allocation-free apart from iteration.
- [X] T020 Extend `src/main/java/nzi/fhir/validator/core/npm/PackageCatalogView.java`:
  - `Entry` gains `Access access`;
  - `load` selects `g.owner, o.name_short, g.is_shareable` with `JOIN organisations o ON o.id = g.owner`;
  - add `Access access(String idAndVersion)` (null when not stored);
  - add `Optional<String> highestVisible(String name, Caller caller)`.
  
  Update `src/test/java/nzi/fhir/validator/core/npm/PackageCatalogViewTest.java` accordingly.
- [X] T021 [P] Create `src/main/java/nzi/fhir/validator/core/validator/PinnedAccess.java`: an immutable `Map<String,
  Access>` per FHIR version, with `Access get(String igKey)` that falls back to `Access.systemShared(...)` for
  entries without a row (data-model §1.4).
- [X] T022 Extend `src/main/java/nzi/fhir/validator/core/service/ConfigRevisionRepository.java`:
  - `ConfigRow` gains `PinnedAccess access`, loaded in `current` and `lockForUpdate` by `LEFT JOIN`ing
    `fhir_validator_snapshots` (and `organisations` for the short name) per included key;
  - add `KIND_IG_ACCESS = "IG_ACCESS"`;
  - `updateIgSet` also writes the child rows in the same transaction: `INSERT … ON CONFLICT DO NOTHING` for an added
    key with the given `(owner, shareable)`, `DELETE` for a removed key, and `DELETE` of rows whose key isn't in the
    new array (cleanup);
  - add `changeSnapshotAccess(tx, version, key, Optional<UUID> owner, Optional<Boolean> shareable)`, which updates
    the row, bumps `revision` and notifies `IG_ACCESS`.
- [X] T023 Update `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java`:
  - keep the latest `PinnedAccess` per version in a volatile map and expose `PinnedAccess pinnedAccess(SupportedFhirVersion)`;
  - on `IG_ACCESS` notifications (and on poll when the revision moved but the included set didn't), swap
    `PinnedAccess` **without** calling `registry.applyPinned`;
  - on `IG`/`IG_EXCLUDE`, swap it together with the pinned set.
  
  Extend `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java`: a snapshot access change on
  instance A reaches instance B within 10 s, and the context's `appliedRevision` build count doesn't change (no
  rebuild).
- [X] T024 Add to `src/main/java/nzi/fhir/validator/web/ProblemException.java`:
  - `dependencyUnavailable()`: 424 `dependency-not-found`, "A dependency of the requested IG package is not
    available. Register or upload it via /igs/register or /igs/upload";
  - `conflictOwnedElsewhere(String key)`: 409 `conflict`, "IG package <key> is already stored by another
    organisation";
  - a `TYPE_CONFLICT` constant.
- [X] T025 Wire `OrganisationRepository`, the `ConfigSync::pinnedAccess` supplier and the system organisation short
  name into `src/main/java/nzi/fhir/validator/ServiceRegistry.java`. Expose `organisations()`.

**Checkpoint**: Migration applied. Callers are identified and access data is in memory everywhere. Existing tests
pass unchanged, because all data is system-owned and shareable.

---

## Phase 3: User Story 1 - Keep an organisation's private IGs private during validation (Priority: P1) 🎯 MVP

**Goal**: Validation and the package read endpoints use only content the caller's organisation owns, shareable
content, or (for the system organisation) everything. Refusals are indistinguishable from "not stored".

**Independent Test**: With organisations A and B, a private package owned by A and a shareable one owned by B,
validate each with A's key, B's key and no key. Only A and system can use A's package, everyone can use B's, and a
refused request looks exactly like a request for a package that isn't stored (quickstart §2 steps 2–4).

### Tests for User Story 1 (write first, confirm they fail)

- [X] T026 [P] [US1] Write `src/test/java/nzi/fhir/validator/web/OwnershipValidationIT.java` covering US1 scenarios
  1, 2 and 4–8:
  - the owner validates with `?ig=` and gets `X-Validation-Context-Owner`/`-Shareable` headers and `igOwner`/`igShareable`;
  - B and anonymous get 404 with a body byte-identical (except `instance`/request id) to `?ig=does.not.exist%231.0.0`;
  - a versionless `ig=P`, where `P#2` is private to A and `P#1` is shareable, resolves to `P#1` for B and to `P#2`
    for A;
  - an invisible package declared for R5 requested on R4 gives 404, not 400;
  - a shareable IG with a private dependency gives 424 for B, with the text not containing the dependency name, and
    200 for the dependency's owner;
  - no `ig` gives the base context with `igOwner = system`;
  - `GET /igs/{n}/{v}/dependencies` and `/conformance` give 404 for B;
  - `package_not_found` is recorded for refusals, and no other metric label appears (scrape `/metrics`).
- [X] T027 [P] [US1] Write `src/test/java/nzi/fhir/validator/core/validator/OwnershipOnDemandIT.java`:
  - (a) B naming A's private package triggers 0 builds (`fv.validator.context.builds` unchanged, registry has no
    slot);
  - (b) A builds an on-demand snapshot of its private package, then B names it on the same instance and gets 404
    (SC-004);
  - (c) A pinned private snapshot is refused for B;
  - (d) after `setPackageAccess(shareable=true)`, B succeeds within 10 s with no rebuild.
- [X] T028 [P] [US1] Extend `src/test/java/nzi/fhir/validator/core/validator/IgReferenceTest.java`: `resolveIn` with a
  `Caller` chooses the highest visible version; an invisible exact version is a miss (empty), not 400; the
  FHIR-version check only runs for visible packages.
- [X] T029 [P] [US1] Extend `src/test/java/nzi/fhir/validator/web/endpoint/HealthApiTest.java`: with one private and one
  shareable pinned context, `contexts[]` lists only the shareable key, `privateContexts.count = 1` with its state
  counted, and no field contains an owner short name or the private key (FR-021).

### Implementation for User Story 1

- [X] T030 [US1] Update `src/main/java/nzi/fhir/validator/core/validator/IgReference.java`:
  - `resolve(SupportedFhirVersion, PackageCatalog, Caller)` and `resolveIn(..., Caller)` use
    `view.highestVisible(name, caller)` for versionless references;
  - an exact version that isn't visible (`!AccessPolicy.canUse(caller, view.access(key))`) is treated as missing, so
    it goes through `refreshOnMiss` and then `igNotStored(label)`;
  - the FHIR-version check runs only after visibility (research R5).
- [X] T031 [US1] Update `src/main/java/nzi/fhir/validator/core/service/FhirValidationService.java`:
  - change `validate(...)` to take a **required** `Caller caller` as its first parameter. Remove the overload without
    a caller rather than defaulting it: there must be no path that silently runs as `Caller.system(...)` (fail
    closed). Update every existing caller (`ValidationApi` and the tests listed by
    `grep -rl "\.validate(" src/test/java`) to pass a caller explicitly; tests use `Caller.system("system")`;
  - after `resolve`, and **before** `registry.snapshot(key)`, compute the resolved set **without taking the registry
    lock** (the spec 004 SC-007 hot path stays lock-free): add
    `ValidatorRegistry.peekResolvedPackages(SnapshotKey) → Optional<List<String>>`, which reads only the volatile
    base or pinned slot snapshot and never touches the access-ordered `onDemand` map (a `get` on it mutates order and
    needs the lock). If that is empty (on-demand or not yet built), use
    `ResolvedContext.of(key.igPackage(), view).packages()` from the in-memory catalog. Never call
    `registry.current(key)` here: it takes the lock and throws 503 when the context isn't built. Then call
    `AccessPolicy.canUseContext` with `configSync.pinnedAccess(version)`;
  - map `IG_NOT_VISIBLE` → `ProblemException.igNotStored(ig.label())` and `DEPENDENCY_NOT_VISIBLE` →
    `ProblemException.dependencyUnavailable()`;
  - log the real reason at DEBUG with the organisation short name;
  - keep the metrics outcome `package_not_found` for both (research R5);
  - no database access on this path (FR-013).
- [X] T032 [US1] Extend `ValidatorSnapshot.Outcome` with `Access contextAccess` (set by `FhirValidationService` from
  the decision's snapshot access, and `Access.systemShared` for base). Make `OutcomeRenderer` write `igOwner` and
  `igShareable` into `ValidationResponse`. Files: `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java`,
  `src/main/java/nzi/fhir/validator/core/validator/OutcomeRenderer.java`, `src/main/java/nzi/fhir/validator/core/model/ValidationResponse.java`.
- [X] T033 [US1] Update `handleDoValidation` in `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`:
  pass `ApiKeyAuthHandler.caller(ctx)` to the service, and add the `X-Validation-Context-Owner` and
  `X-Validation-Context-Shareable` headers on 200 for every response format.
- [X] T034 [US1] Make `getDependencyGraph` and `listPackageFiles` in
  `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java` take a `Caller`. They return 404
  `package-not-found` (the same text as for a package that isn't stored) when the catalog access isn't usable by the
  caller. Pass the caller from `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java`.
- [X] T035 [US1] Update `details()` in `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`:
  - it takes a `BiPredicate<SnapshotKey, List<String>> isPublic` supplied by `ServiceRegistry`, true when the
    snapshot access and all resolved packages are shareable;
  - `contexts[]` holds only public contexts;
  - add `privateContexts: {count, states: {ACTIVE, BUILDING, STALE, ABSENT}}`;
  - no owners anywhere (research R12).

**Checkpoint**: US1 is fully functional. T026–T029 are green. Private content is invisible to other organisations on
every read and validation path.

---

## Phase 4: User Story 2 - Organisation administrators manage only their own content (Priority: P1)

**Goal**: Register, upload, include and exclude record and respect ownership. Owners (and the system organisation)
change sharing, and only the system organisation transfers ownership.

**Independent Test**: With ADMIN keys for A, B and the system organisation, register, upload, include, exclude and
PATCH across organisations. Confirm owners, default and explicit flags, 404/403/409 refusals and propagation to a
second instance (quickstart §2 steps 1, 5–7).

### Tests for User Story 2 (write first, confirm they fail)

- [X] T036 [P] [US2] Write `src/test/java/nzi/fhir/validator/core/npm/PackageOwnershipIT.java` (with `StubRegistry`)
  covering US2 scenarios 1–3:
  - register by A with dependencies: every newly stored package has `owner = A`, `is_shareable = true`; with
    `shareable:false`, all are private;
  - register of a version already stored and owned by B: owner and flag unchanged; 404 for A if it's private, 200
    with `owner: "b"` if it's shareable;
  - upload by A of B's `name#version`: 409 `conflict`, B's row unchanged (`content_sha256`, owner), and the catalog
    revision isn't bumped;
  - the same upload by system or by B replaces it and keeps the owner;
  - two concurrent first uploads of the same new `name#version` by A and B: exactly one 200, the other 409;
  - the bootstrap key stores with `owner = SYSTEM_ID`.
- [X] T037 [P] [US2] Write `src/test/java/nzi/fhir/validator/core/validator/IncludeOwnershipIT.java` covering US2
  scenarios 4–7:
  - include by A gives owner A, shareable by default, and a `fhir_validator_snapshots` row;
  - include by A with `shareable:false` of a package A doesn't own gives 400 `invalid-request`, while system may;
  - include by B of a key already included by A: shareable gives 200 with `owner: "a"`, no new revision and no row
    change; private gives 404;
  - re-include by A with a different `shareable` gives 200 with the warning "Already included; change sharing with
    PATCH /{version}/include-ig";
  - exclude by B gives 404 and no change; exclude by A or system succeeds and deletes the row;
  - a VALIDATE key on include gives 403.
- [X] T038 [P] [US2] Write `src/test/java/nzi/fhir/validator/web/AccessChangeIT.java` covering US2 scenarios 8–9
  and FR-019, against both PATCH operations:
  - the owner changes the flag (200); a non-owner non-system administrator gets 404; transfer by the
    owner (non-system) gets 403; transfer by a non-owner non-system administrator gets **404, not 403**, for both a
    private and a shareable target (visibility is checked before the system-only rule, so a 403 never reveals that
    a private package or context exists); transfer by system to an active organisation gives 200; transfer to an unknown or
    inactive organisation gives 400; an empty body gives 400;
  - making a snapshot private when the caller doesn't own its package gives 400;
  - on two instances (`ConfigPropagationIT` pattern), a package flag change and a snapshot flag change both take
    effect on instance B within 10 s, with no context rebuild (build counter unchanged).
- [X] T039 [P] [US2] Extend `src/test/java/nzi/fhir/validator/web/endpoint/IgPackageApiContractTest.java` and
  `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java`: response bodies for
  include, register, upload and both PATCH operations validate against the `openapi.yaml` schemas (with `owner`,
  `shareable`), and validate's 200 headers are present (FR-020).

### Implementation for User Story 2

- [X] T040 [P] [US2] Create `src/main/java/nzi/fhir/validator/core/npm/PackageOwnership.java`: `record
  PackageOwnership(UUID owner, boolean shareable, boolean callerIsSystem)`, with `static PackageOwnership of(Caller,
  boolean shareable)`; anonymous is never allowed to store.
- [X] T041 [US2] Make storing ownership-aware in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java`:
  - `store`, `storeFile`, `storeChecked`, `loadPackage` and `fetchFromRegistries` take `PackageOwnership`. Old
    overloads delegate with system and shareable, for tests and internal use.
  - The upsert inserts `owner, is_shareable`, and its `DO UPDATE` keeps them unchanged, adds `WHERE
    fhir_implementation_guides.owner = $9 OR $10` and `RETURNING owner`. Zero rows → fail with
    `ProblemException.conflictOwnedElsewhere(key)` **before** `bumpCatalog` (research R7).
  - Add `changeAccess(String name, String version, Caller caller, Optional<UUID> newOwner, Optional<Boolean>
    shareable)`. It runs in one transaction: lock the row `FOR UPDATE`; not found, or not owned by the caller and
    the caller isn't system → 404; `newOwner` from a non-system caller → 403; then `UPDATE`, `bumpCatalog(tx, key)`
    (`PACKAGE` notify). It returns `Access`.
- [X] T042 [US2] Update `register`, `registerUploadedFile` and `registerIg(bytes, …, source)` in
  `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`:
  - take `Caller` and `boolean shareable`, and pass `PackageOwnership` down;
  - register of an already stored version returns it only if `AccessPolicy.canUse(caller, view.access(key))`,
    otherwise 404;
  - `Registration` gains `Access access`.
- [X] T043 [US2] Update `handleRegisterIg`, `handleUploadAndRegisterIg` and `handleDownloadIg` in
  `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java`:
  - read `shareable` from the JSON body (default `true`) or from the multipart form attribute `shareable` (`"true"`
    or `"false"`, anything else gives 400);
  - pass `ApiKeyAuthHandler.caller(ctx)`;
  - add `owner` (short name) and `shareable` to `registered(...)`.
- [X] T044 [US2] Update `include` and `exclude` in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`
  as research R6 describes:
  - `include(Caller, version, name, requestedVersion, Optional<Boolean> shareable)`:
    - inside the include transaction, load package access together with the catalog: extend `loadCatalog(tx)` (or
      add `loadCatalogWithAccess(tx)`) to also select `owner` and `is_shareable`, returning the
      `DependencyResolver.Catalog` plus a `Map<String, Access>` keyed by `name#version`. Visibility decisions in
      include MUST use this transactional data, not the in-memory `PackageCatalogView`, which may lag behind;
    - resolve the concrete version among the versions **visible** to the caller (`latest` = highest visible);
    - check that every package in `DependencyResolver.resolve(...)` is visible (404 or 424 as in US1);
    - if the key is already included: visible gives 200 with the existing `owner`/`shareable` from
      `row.access()`, plus a warning when `shareable` differs and the caller is the owner; invisible gives 404;
    - `shareable == false` requires the caller to own the named package or be system, otherwise 400 "Only the owner
      of the IG package can make its validator context private";
    - otherwise call `updateIgSet` with `(caller.orgId(), shareable.orElse(true))`.
  - `exclude(Caller, …)`: 404 unless the caller is the snapshot owner (`row.access().get(key)`) or system.
  - `includeResult` gains `owner` and `shareable`.
- [X] T045 [US2] Add `changePackageAccess(Caller, name, version, JsonObject body)` and
  `changeSnapshotAccess(Caller, SupportedFhirVersion, String ig, JsonObject body)` to
  `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`:
  - check order for both operations: (1) not stored or included, or the caller is neither owner nor system → 404;
    (2) `owner` present and the caller isn't system → 403; (3) body validation and target organisation → 400;
  - an `owner` short name resolves through `OrganisationRepository.findByShortName`; unknown or inactive gives 400;
  - for snapshots: lock the version row; not included, or not owner and not system, gives 404; `owner` from a
    non-system caller gives 403; `shareable=false` follows the T044 package-owner rule; then
    `ConfigRevisionRepository.changeSnapshotAccess`, followed by `LocalApplyWait` as for include.
- [X] T046 [US2] Mount the routes in `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`:
  - `validationApiChangeIncludedIgAccess` (parse `ig` with `IgReference.parse`; an exact version is required);
  - pass `Caller` and the `shareable` body field to include, and `Caller` to exclude.
- [X] T047 [US2] Mount `igPackageApiChangeAccess` in `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java`.
  It returns `{status, name, version, owner, shareable}`.

**Checkpoint**: US2 is fully functional. T036–T039 are green. Every write records the owner, and every
cross-organisation write is refused correctly.

---

## Phase 5: User Story 3 - Operators manage organisations and their API keys from the command line (Priority: P1)

**Goal**: `org`, `key`, `package` and `snapshot` commands in the service jar and image, as contracts/cli.md
describes.

**Independent Test**: Create an organisation from a file with one flag override, update one field, create a
VALIDATE key and use it, deactivate it (the key fails), reactivate it (the key works) (quickstart §2 setup and steps
8–9).

### Tests for User Story 3 (write first, confirm they fail)

- [X] T048 [P] [US3] Write `src/test/java/nzi/fhir/validator/cli/CliArgsTest.java`:
  - `--flag value` parsing, repeatable `--address-line`, and `--email ""` clears;
  - an unknown flag or a missing value gives a usage error;
  - the file is read first and flags override it (FR-023);
  - an unknown JSON field gives an error, and output-only fields are ignored.
- [X] T049 [P] [US3] Write `src/test/java/nzi/fhir/validator/cli/AdminCliIT.java` (Testcontainers), calling
  `AdminCli.run(String[] args, PrintStream out, PrintStream err, Map<String,String> env)` in-process:
  - `org create --file f.json --email x@y.dk` gives exit 0, and stdout is a single JSON document with the file's
    fields and the flag's email;
  - a missing `nameLong` gives exit 2 with a one-line stderr naming the field; a duplicate short name in another
    case gives exit 4; `--type SYSTEM` gives exit 4;
  - `org update --name-short acme --phone …` changes only the phone and sets `updatedAt`; `--rename` bumps the
    catalog revision;
  - `org deactivate/activate` (exit 0); `org deactivate --name-short system` gives exit 4; `org show` of an unknown
    organisation gives exit 3;
  - `key create` prints a 43-character key once, which authenticates against a running `AppHarness` and stops
    working after `org deactivate` within the TTL; `key create --name bootstrap` gives exit 4; `key revoke`;
    `key list` contains no key or hash;
  - `package share` and `snapshot transfer` commit and notify (a running `CoreHarness` picks them up within 10 s);
  - stdout never contains log lines.
- [X] T050 [P] [US3] Extend `src/test/java/nzi/fhir/validator/core/config/NoSecretsInLogsTest.java`: logs captured
  during `key create` (generated and `--key-stdin`) never contain the key, and do contain the FR-026 INFO line
  (`cli: key create name=… org=…`) for both. Also check that `package share` and `org deactivate` each write one
  `cli:` INFO line.

### Implementation for User Story 3

- [X] T051 [P] [US3] Create `src/main/java/nzi/fhir/validator/cli/CliArgs.java`:
  - `parse(String[] args)` → group, command, `Map<String,List<String>> flags`; it knows each command's allowed
    flags and throws `CliException(2, …)` on unknown ones;
  - `organisationInput(...)`: reads `--file` JSON with `Organisation.fromJson` (an unknown field gives exit 2), then
    applies the field flags, and returns the merged `Organisation` plus the set of fields given.
- [X] T052 [US3] Create `src/main/java/nzi/fhir/validator/cli/AdminCli.java`:
  - `public static int run(String[] args, PrintStream out, PrintStream err, Map<String,String> env)`;
  - load `ApplicationConfig` through `ConfigLoader`, using `env`;
  - create a `Vertx` and a `Pool` of size 2 (`PgConfig`), then run `DatabaseService.migrate` and the command;
  - wait for the `Future` on a `CountDownLatch` in the calling thread (no `.get()`, constitution Principle I);
  - map `CliException` codes and `ProblemException` statuses (404 → 3, 409 → 4, 400 → 2) and anything else → 1,
    writing `error: <code>: <message>` to `err`;
  - close the pool and Vert.x;
  - configure Log4j to write to stderr in CLI mode (system property set before the first logger, or a
    `log4j2-cli.xml`).
- [X] T053 [US3] Create `src/main/java/nzi/fhir/validator/cli/OrgCommands.java`:
  - `create`, `update` (selector `--id` or `--name-short`, plus `--rename`), `deactivate`, `activate`, `show` (with
    `keys` from `ApiKeyRepository.list`) and `list [--active]`, on `OrganisationRepository`;
  - JSON output through `Organisation.toJson()`;
  - INFO log `cli: org <command> name_short=<…>` (FR-026).
- [X] T054 [US3] Create `src/main/java/nzi/fhir/validator/cli/KeyCommands.java`:
  - `create --org --name --role [--key-stdin]`: the generated key is 32 bytes from `SecureRandom`, base64url
    without padding, which gives 43 characters and matches `[A-Za-z0-9_-]{32,128}`; `--key-stdin` is validated
    with `ApiKeyRepository.isWellFormed`; `bootstrap` gives exit 4; a duplicate name gives exit 4;
  - `ApiKeyRepository.save(name, key, role, orgId)`; output `{name, role, org, key}`, with `key` omitted for
    `--key-stdin`;
  - `revoke --name`, and `list [--org]`;
  - INFO log `cli: key create name=<…> org=<…> role=<…>` and `cli: key revoke name=<…> org=<…>` (FR-026);
    `list` doesn't log;
  - never log the key.
- [X] T055 [US3] Create `src/main/java/nzi/fhir/validator/cli/AccessCommands.java` with `package share|transfer` and
  `snapshot share|transfer`. They act as `Caller.system(...)` and reuse `IgPackageService.changePackageAccess` and
  `changeSnapshotAccess` (T045), so revision bumps and notifications are identical to HTTP. Output as in
  contracts/cli.md. After a successful commit, INFO log `cli: package share ig=<name#version> owner=<org>
  shareable=<bool>`, `cli: package transfer ig=<…> from=<org> to=<org>`, and the same for `snapshot` with
  `fhir_version=<…>` added (FR-026).
- [X] T056 [US3] Update `main` in `src/main/java/nzi/fhir/validator/Bootstrap.java`: if `args.length > 0` and
  `args[0]` is one of `org|key|package|snapshot`, run `System.exit(AdminCli.run(args, System.out, System.err,
  System.getenv()))` before any server initialisation; otherwise start the server as today.
- [X] T057 [P] [US3] Add `/usr/local/bin/fv-admin` (`#!/bin/sh` and `exec java $JAVA_OPTS -jar /app/app.jar "$@"`,
  mode 0755) to `Dockerfile`, and document it in a comment next to `CMD`.

**Checkpoint**: US3 is fully functional. T048–T050 are green. Operators need no SQL for organisations or keys.

---

## Phase 6: User Story 4 - Existing deployments upgrade without behaviour changes (Priority: P2)

**Goal**: The system organisation is configured from the environment, existing data is system-owned and shareable,
and previous-release clients are unaffected.

**Independent Test**: Upgrade a 3.0.0 database, start with `FV_SYSTEM_ORG_*` set, and run the existing regression
suite unchanged (SC-001).

### Tests for User Story 4 (write first, confirm they fail)

- [X] T058 [P] [US4] Extend `src/test/java/nzi/fhir/validator/core/config/ConfigLoaderTest.java`: each
  `FV_SYSTEM_ORG_*` variable maps to `systemOrg.*`; `FV_SYSTEM_ORG_ADDRESS_LINES="a|b"` gives two lines; an invalid
  `FV_SYSTEM_ORG_COUNTRY=XX1` or email fails start-up with a configuration error naming the variable; unset
  variables are absent (not empty).
- [X] T059 [P] [US4] Extend `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java` (T007's
  3.0.0 → 3.1.0 case): after `Bootstrap.start` with `FV_SYSTEM_ORG_NAME_LONG=Acme Ops` and `FV_SYSTEM_ORG_EMAIL`
  set, the system row has those values and its other fields are unchanged; a second start with only `_EMAIL`
  changed updates only the email; the bootstrap key's `owner` is `SYSTEM_ID`; and the 3.0 client calls (validate
  with and without `ig`, include, register, upload) return the same status and body shape as before (except the new
  additive fields).

### Implementation for User Story 4

- [X] T060 [US4] Add the `ConfigLoader` entries `FV_SYSTEM_ORG_NAME_SHORT`, `FV_SYSTEM_ORG_NAME_LONG`,
  `FV_SYSTEM_ORG_EMAIL`, `FV_SYSTEM_ORG_PHONE`, `FV_SYSTEM_ORG_WEBSITE`, `FV_SYSTEM_ORG_COUNTRY`,
  `FV_SYSTEM_ORG_POST_CODE`, `FV_SYSTEM_ORG_ADDRESS_LINES` (`|`-separated), `FV_SYSTEM_ORG_CONTACT_PERSON_NAME`,
  `FV_SYSTEM_ORG_CONTACT_PERSON_MOBILE` and `FV_SYSTEM_ORG_CONTACT_PERSON_EMAIL` (all `Kind.STRING`, mapped to
  `systemOrg.<camelCase>`). Validate them at load with the `Organisation.validate()` field rules, and add
  `ApplicationConfig.systemOrganisation()` returning only the set fields. Files:
  `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java`,
  `src/main/java/nzi/fhir/validator/core/config/ApplicationConfig.java`, and `src/main/resources/config.json` (an
  empty `systemOrg` section).
- [X] T061 [US4] In `src/main/java/nzi/fhir/validator/Bootstrap.java`, add `installSystemOrganisation(services)`
  between `DatabaseService.migrate` and `installBootstrapKey`. It calls
  `OrganisationRepository.applySystemConfig(config.systemOrganisation())` and logs at INFO the field *names* that
  were applied.
- [X] T062 [P] [US4] Add every `FV_SYSTEM_ORG_*` variable, commented with its default, to `.env.example`, and add
  rows to the configuration table in `README.md`.

**Checkpoint**: All stories are complete. Existing deployments upgrade transparently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T063 [P] Write `docs/upgrade-3.1.md`:
  - what changes (organisations, defaults);
  - rolling upgrade: 3.0 and 3.1 can run together because of the database defaults, but create organisations and
    private content only after every instance runs 3.1 (research R11);
  - `FV_SYSTEM_ORG_*`;
  - command-line usage through `fv-admin`;
  - raw-SQL key management replaced by `key create`;
  - rollback: restore a backup (V8 adds NOT NULL columns with defaults, so 3.0 keeps working on the V8 schema, but
    ownership is ignored).
- [X] T064 [P] Add a 3.1.0 entry to `CHANGELOG.md` (Added: organisations, ownership, sharing, PATCH operations,
  command line, `fv-admin`; Changed: health details hide private contexts; Security: upload can't replace another
  organisation's package; deactivated organisations' keys get 403).
- [X] T065 [P] In `README.md`, replace the "API keys" raw-SQL section with an "Organisations and API keys" section
  that uses the command line (contracts/cli.md examples), and document the access rule, `shareable` and the two
  PATCH operations.
- [X] T066 Run `sh scripts/bench-validate.sh http://localhost:8880 '<ig>'` on `main` and on the branch with a built
  pinned context, and record p50, p95 and p99 in the PR description. p95 must stay under 200 ms with no measurable
  regression (SC-007, constitution Principle VI).
- [X] T067 Run the quickstart §2 manual end-to-end steps 1–10 against `make up`, and fix any difference.
- [X] T068 Run `./mvnw verify`: all tests green, no `BlockedThreadChecker` warnings, the routes match `openapi.yaml`,
  and V8 applies to an empty database and to a 3.0.0 database. Then bump `pom.xml` from `3.1.0-SNAPSHOT` to `3.1.0`.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001–T004)**: none.
- **Foundational (T005–T025)**: depends on Setup and blocks every story. The internal order:
  1. T005–T007;
  2. T008–T013 (T008, T009, T012, T013 in parallel; T010 → T011);
  3. T014 → T015 → T016;
  4. T017, T018 and T021 in parallel → T019 → T020;
  5. T022 → T023;
  6. T024;
  7. T025.
- **US1 (T026–T035)**: depends on Foundational only.
- **US2 (T036–T047)**: depends on Foundational. It reuses the visibility helpers from US1 (T030, and T031's mapping
  of 404/424), so implement US1 first or take T030 early. US2's tests can be written in parallel with US1.
- **US3 (T048–T057)**: depends on Foundational (T011, T014). `AccessCommands` (T055) depends on US2's T045, and
  everything else in US3 is independent of US1 and US2.
- **US4 (T058–T062)**: depends on Foundational (T006, T011). T059's 3.0-client regression part runs best after US1
  and US2.
- **Polish (T063–T068)**: after the stories it touches. T068 is last.

### Within each story

Tests first (they must fail) → value types and policy → repositories → services → endpoints or commands → contract
tests.

## Parallel Examples

```text
# Foundational, after T007:
T008 Organisations | T009 OrganisationTest | T013 Caller | T017 Access | T018 AccessPolicyTest | T021 PinnedAccess

# US1 tests together:
T026 OwnershipValidationIT | T027 OwnershipOnDemandIT | T028 IgReferenceTest | T029 HealthApiTest

# US2 tests together:
T036 PackageOwnershipIT | T037 IncludeOwnershipIT | T038 AccessChangeIT | T039 contract tests

# US3 runs alongside US1/US2 after Foundational (except T055):
T048 CliArgsTest | T049 AdminCliIT | T050 NoSecretsInLogsTest → T051 → T052 → T053 | T054 | T057

# Docs in parallel:
T062 .env.example | T063 upgrade-3.1.md | T064 CHANGELOG | T065 README
```

## Implementation Strategy

### MVP first (US1)

1. Phases 1 and 2 (Setup, Foundational). Ownership data exists, and everything is system-owned and shareable.
2. Phase 3 (US1): access checks on validation and read endpoints. Private content (set by SQL or the test helper) is
   enforced.
3. **Stop and validate**: T026–T029 green, and quickstart §2 steps 2–4 (private content set with
   `TestPackages.setPackageAccess` or SQL).

### Incremental delivery

1. Foundation → organisations, caller identity, in-memory access (no behaviour change).
2. US1 → enforcement on reads and validation (MVP).
3. US2 → ownership on writes, include/exclude rules, sharing changes and transfers.
4. US3 → command line for organisations, keys and access (can be built in parallel with US1 and US2).
5. US4 and Polish → system organisation configuration, upgrade proof, docs, benchmark, release 3.1.0.

### Notes

- Never edit an applied migration (V1–V7).
- Refusals for invisible content must reuse the exact "not stored" texts and the `package_not_found` metric outcome
  (research R5). Never add a metric label or a log line above DEBUG that names private content.
- Client-facing errors carry curated text only. Causes go to the log with the request id (Principle IV).
- Every new future-returning API returns `io.vertx.core.Future`: no `.get()`, no `Thread.sleep`, and no blocking on
  the event loop. The command line waits on a latch in `main` only.
- Commit after each task or logical group, with commit messages ending in the required attribution line.

---

## Phase 8: Convergence

- [X] T069 CRITICAL: Declare `'424'` (`dependency-not-found`: a dependency of the IG package is not available to the caller, not named) on `validationApiIncludeIg` in `src/main/resources/openapi.yaml`, change contracts/http-api.md §2 from "404: the package or a dependency isn't visible" to "404 for the package, 424 for a dependency", and assert the 424 problem shape for an include whose dependency is private to another organisation in `src/test/java/nzi/fhir/validator/core/validator/IncludeOwnershipIT.java` per Constitution IV (contradicts)
- [X] T070 Add the 3.0-client `POST /igs/upload` call (200, fields `status`, `name`, `version`, `downloaded`, `warnings`) to `startWithSystemOrganisationSettingsOnAnUpgradedDatabase` in `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java` per T059 / SC-001 (partial)
