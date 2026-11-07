# Research: Organisation Ownership

**Feature**: `005-organisation-ownership` | **Date**: 2026-11-06 | **Spec**: [spec.md](./spec.md)

Code read for these decisions: `V1`–`V7` migrations, `ApiKeyRepository`, `ApiKeyAuthHandler`,
`ConfigRevisionRepository`, `ConfigSync`, `ValidatorRegistry` (`snapshot`, `details`), `FhirValidationService`,
`IgReference`, `PackageCatalogView`, `PostgresPackageCacheManager` (`store`, `storeChecked`, `loadPackage`),
`IgPackageService` (`include`, `exclude`, `register`), `IgPackageApi`, `ValidationApi`, `Bootstrap`,
`DatabaseService`, `ConfigLoader`, `HealthService`, `openapi.yaml`.

The technical context had no open `NEEDS CLARIFICATION` items: the spec's clarification session settled scope.
The items below are the design unknowns found while reading the code.

---

## R1. Where per-snapshot ownership lives

**Decision**: add a new table, `fhir_validator_snapshots`, with one row per included snapshot: `(fhir_version,
ig_package)` as the primary key, plus `owner`, `is_shareable`, `created_at` and `updated_at`.
`fhir_validator_logs` keeps one row per FHIR version, its `revision`, and `included_ig_packages` as the membership
list. Every include, exclude and access change writes both tables in one transaction, with the version row locked
(the existing `lockForUpdate`).

**Rationale**:
- Since 3.0.0, `fhir_validator_logs` holds one row per FHIR version, and the included snapshots are entries of an
  array. Columns on that row (the literal input) would give *one* owner to every R4 snapshot, which FR-008 forbids.
- A child table keeps the per-version revision counter and the `LISTEN/NOTIFY` propagation exactly as spec 004
  built them.
- Keeping `included_ig_packages` as the membership list means 3.0 instances can still read the schema during a
  rolling upgrade (R11).
- A snapshot in `included_ig_packages` with no child row (for example one written by a 3.0 instance during the
  upgrade) is read as owned by the system organisation and shareable, which is the migration default (FR-009).
- Snapshots built on demand get no row: they take their package's owner and flag (FR-008).

**Alternatives considered**:
- *`owner`/`is_shareable` columns on `fhir_validator_logs`* (as written in the input): rejected, because the row is
  per FHIR version, not per snapshot.
- *One `fhir_validator_logs` row per snapshot*: rejected. The revision would become per snapshot, which breaks
  "include serialises per version" and means rewriting `ConfigSync` and the 004 propagation tests.
- *Replace the array with the child table*: cleaner, but 3.0 instances couldn't read it during a rolling upgrade.
  Left for a later major release (noted in the upgrade guide).

## R2. Organisation identity, the system organisation and naming

**Decision**:
- **Identity**: `organisations.id` is a `UUID`. The system organisation has the fixed id
  `00000000-0000-0000-0000-000000000001` (constant `Organisations.SYSTEM_ID`).
- **Database guarantees**:
  - a partial unique index makes `type = 'SYSTEM'` possible on only one row;
  - a check requires `type = 'SYSTEM'` ⇔ `id = SYSTEM_ID`;
  - a check requires `type <> 'SYSTEM' OR is_active`.
- **Column names**:
  - timestamps are `created_at`/`updated_at` (`TIMESTAMPTZ`), the project convention (input question 12);
  - ownership columns are named `owner`, as in the input, and are UUID foreign keys to `organisations.id`.
- **`name_short`**: 2–32 characters of `[A-Za-z0-9_-]`, unique on `lower(name_short)`.
- **Other fields**:
  - `country`: `^[A-Z]{2}$` in the database, and a member of `Locale.getISOCountries()` in the service.
  - `address_lines`: at most 6 lines of at most 128 characters each.
  - Emails and website are checked for shape in the service. The website must be `http`/`https`.

**Rationale**: a fixed id lets migrations, column defaults and code refer to the system organisation without a
lookup. Database constraints keep FR-003 true even against hand-written SQL.

**Alternatives considered**: a `BIGINT` identity (rejected: API key ids are already UUIDs, and a sequence-assigned
id can't be a well-known constant); name-based references (rejected: names can be updated).

## R3. Caller identity on each request

**Decision**:
- `ApiKeyRepository.findActive` joins `organisations` and returns `KeyInfo(id, role, ownerId, ownerShortName,
  ownerActive)`.
- `ApiKeyAuthHandler`:
  - refuses a key whose organisation is inactive with 403 `forbidden` ("The organisation of this API key is
    deactivated");
  - otherwise puts a `Caller` (`orgId`, `orgShortName`, `role`, `system`, `anonymous`) into the `RoutingContext`
    under `ApiKeyAuthHandler.CALLER_KEY`;
  - uses `Caller.ANONYMOUS` when no key is sent and anonymous validation is allowed.
- The existing lookup cache (`FV_AUTH_CACHE_TTL_MS`, default 30 s, bounded to 10,000 entries) caches the whole
  `KeyInfo`. Deactivation, reactivation and revocation therefore take effect within the TTL (FR-014, SC-005), with
  no new propagation channel.

**Rationale**: one database lookup per key per TTL, as today. No hot-path change for cached keys.

**Alternatives considered**: a `NOTIFY` on organisation change to flush caches immediately. Rejected because the
spec accepts the auth-cache window, and the cache is already the mechanism for revocation.

## R4. Where and how the access rule is evaluated

**Decision**:
- **`AccessPolicy`**: a pure, allocation-free class in `core.security`.
  - `canUse(caller, owner, shareable)` = `shareable || caller.system() || (!caller.anonymous() && caller.orgId().equals(owner))`.
  - `canUseContext(caller, igKey, resolvedPackages, view, pinnedAccess)` applies it to the snapshot's own access
    (pinned row, or the package's own access for on-demand) and to every package in the resolved set (FR-010).
- **Package access in memory**: `PackageCatalogView.Entry` gains `owner`, `ownerShortName` and `shareable`.
  `PackageCatalogView.load` joins `organisations` for the short name.
- **Pinned snapshot access in memory**: `ConfigSync` holds it in a per-version, immutable `PinnedAccess` map
  (`igKey → (owner, ownerShortName, shareable)`), loaded with the configuration row.
- **Where the check runs**: in `FhirValidationService.validate`, between `resolve(version, ig)` and
  `registry.snapshot(key)`. It is therefore always **before** an on-demand build is admitted (FR-013), and it reads
  only the in-memory view and map, with no database round trip (FR-013, SC-007).
- **Already-built snapshots**: the resolved set comes from the snapshot (`ValidatorSnapshot.resolvedPackages`).
  Before a build, it comes from `ResolvedContext.of(key, view)`, the same resolution the build uses.
- **Base context**: always allowed, with no lookup.

**Rationale**:
- Access is evaluated *per request* against the current view. A sharing or ownership change therefore needs no
  rebuild or invalidation of built snapshots: refreshing the view is enough (R8).
- A snapshot built for A from private content can never serve B, because the check doesn't depend on who triggered
  the build (SC-004).

**Alternatives considered**:
- *Per-organisation snapshot keys*: rejected by clarification Q1.
- *Checking in `ValidatorRegistry.snapshot`*: rejected. The registry is about lifecycle, and spreading the policy
  across lock-held code makes it harder to test.
- *Caching decisions per (org, key)*: unnecessary. Resolved sets are small (typically under 20 packages), so the
  check is a handful of hash lookups.

## R5. Hiding existence (versionless `ig`, ordering, timing, metrics)

**Decision**:
- **Resolution**: `IgReference.resolve` takes the `Caller`. A versionless `ig` (`name`, `name#latest`) resolves to
  the highest version **the caller may use**. An invisible exact version is a miss.
- **Check order**: (1) shape → (2) existence and visibility → (3) FHIR-version support → (4) visibility of the
  dependencies. The FHIR-version error (400) can therefore never reveal an invisible package.
- **Refresh on miss**: an invisible package takes the same path as a stored-but-unknown one, a single rate-limited
  `refreshOnMiss`, so timing doesn't differ much from "not stored".
- **Responses**:
  - an invisible IG gets `ProblemException.igNotStored(label)`, the same text as for a package that isn't stored;
  - an invisible dependency gets a new `ProblemException.dependencyUnavailable()`: 424 `dependency-not-found` with
    the text "A dependency of the requested IG package is not available. Register or upload it via /igs/register
    or /igs/upload". The text names no package (FR-012). A dependency that is missing but would be visible keeps
    today's message, which names it.
- **Metrics**: a refusal records the existing `package_not_found` validation outcome. There is **no** new
  `access_denied` label, because `/metrics` is unauthenticated and a separate counter would reveal existence.
- **Logs**: the real reason ("not visible to organisation X") is logged at DEBUG with the request id.

**Rationale**: FR-012, SC-002 ("0 refusals distinguishable from not stored").

## R6. Include, exclude and private snapshots of other organisations' packages

**Decision**:
- **Include of a key not yet included**:
  - the caller must be able to use the named package and every resolved dependency, otherwise 404 or 424 as in R5;
  - the snapshot is owned by the caller's organisation, with `shareable` from the request (default `true`).
- **Private snapshot of someone else's package**: a caller may include a snapshot as private (`shareable=false`),
  or later make it private (R8), only if the caller owns the named IG package or is the system organisation.
  Otherwise the answer is 400 `invalid-request`: "Only the owner of the IG package can make its validator context
  private".
- **Include of a key already included**:
  - by the same organisation: no change, as today. A different `shareable` in the body gets the warning "Already
    included; change sharing with PATCH /{version}/include-ig".
  - by another organisation: no change if the caller may use the snapshot, and the response reports the existing
    owner (clarification Q1). Otherwise 404.
- **Exclude**: allowed for the snapshot owner and the system organisation. Anyone else gets 404, the same answer as
  for "not included".
- **Reporting**: the include response gains `owner` (short name) and `shareable`.

**Rationale**: snapshot keys are global (one slot per IG and FHIR version). Without the ownership condition on
private, any administrator could include a public package such as US Core as private and so hide it from everyone,
since the private pinned slot would also block on-demand use of the same key.

**Alternatives considered**: letting B build a separate snapshot when A's is private. Rejected by clarification Q1
(one snapshot per key).

## R7. Ownership when packages are stored (register, upload, URL)

**Decision**:
- **Who owns what is stored**: `PostgresPackageCacheManager.store/storeFile/loadPackage` take a
  `PackageOwnership(owner, shareable, system)`. Every package stored in the call is owned by the caller's
  organisation, with `shareable` from the request (default `true`; licence ignored, FR-016). That includes
  dependencies downloaded from a registry. The bootstrap key belongs to the system organisation.
- **Conditional upsert**: the upsert becomes `ON CONFLICT … DO UPDATE … WHERE t.owner = $owner OR $system
  RETURNING owner`.
  - Zero rows returned (the package is owned by another organisation and the caller isn't the system organisation)
    means 409 `conflict`: "IG package name#version is already stored by another organisation". Nothing changes and
    the catalog revision isn't bumped.
  - A replacement keeps the stored `owner` and `is_shareable`. Only an explicit change (R8) alters them.
- **Register of a stored version**: nothing is stored. The result is returned if the caller may use the package,
  otherwise 404 (FR-017).
- **Races**: two organisations storing the same new `name#version` at once are serialised by the unique key.
  Exactly one insert wins, and the other caller gets 409 (upload) or the register outcome above.
- **Responses**: register, upload and URL download responses gain `owner` and `shareable`.

**Rationale**: the condition sits in the upsert itself, so there is no check-then-write race. It also keeps the
spec 004 transaction (upsert, catalog revision bump, `PACKAGE` notify) intact.

**Alternatives considered**: `SELECT … FOR UPDATE` before the upsert. Rejected as an extra round trip with the same
effect.

## R8. Changing sharing and transferring ownership (FR-019)

**Decision**:
- **HTTP**: two new administrative operations, `PATCH /igs/{name}/{version}` (package) and
  `PATCH /{version}/include-ig?ig=name#version` (included snapshot). Each takes a body
  `{ "shareable"?: boolean, "owner"?: "<name_short>" }` with at least one field.
- **Command line**: the same operations as `package share|transfer` and `snapshot share|transfer` (R9).
- **Authorisation**:
  - changing the flag needs the owner or the system organisation; anyone else gets 404;
  - transfer needs the system organisation; the owner's administrator gets 403 `forbidden`, and a non-owner gets
    404, because the visibility check comes first (404 → 403 → 400), so a 403 never reveals that something private
    exists;
  - the target organisation must exist and be active, otherwise 400 `invalid-request`;
  - making a snapshot private follows the R6 rule.
- **Propagation**:
  - *Package*: `UPDATE` plus `bumpCatalog` plus a `PACKAGE` notify in one transaction. Every instance refreshes its
    catalog view. No snapshot is invalidated, because the content hash is unchanged (004 invalidation is
    hash-based) and access is evaluated per request (R4).
  - *Snapshot*: update `fhir_validator_snapshots`, bump the per-version revision, and notify with the new kind
    `IG_ACCESS`. `ConfigSync` reloads the row and swaps `PinnedAccess` **without rebuilding**, because the
    included set is unchanged.
  - Both reach every instance within the spec 001 window (10 s), and requests already in flight finish.
- **Organisation renames**: an `org update` that changes `name_short` also bumps the catalog revision and notifies
  `PACKAGE` (with no key), so the in-memory short names refresh.

**Rationale**: reuses the two existing propagation channels, needs no new infrastructure (Principle II), and never
rebuilds a validator for a metadata change.

## R9. Command line

**Decision**:
- **Entry point**: the same fat jar. `Bootstrap.main(args)` passes control to `nzi.fhir.validator.cli.AdminCli`
  when `args[0]` is `org`, `key`, `package` or `snapshot`. With no arguments it starts the server as today, so the
  container's default command is unchanged.
- **Container**: the image's `CMD` is `sh -c "exec java $JAVA_OPTS -jar /app/app.jar"`, which takes no arguments.
  A small wrapper script, `/usr/local/bin/fv-admin` (`exec java $JAVA_OPTS -jar /app/app.jar "$@"`), is added to the
  `Dockerfile`, so operators run `docker compose run --rm validator fv-admin org create …`.
- **Parsing**: a small parser is written for it (`CliArgs`: `--flag value`, repeatable flags, `--file`). There is
  **no new dependency** (Principle VIII; picocli was considered and rejected for about 10 flags).
- **Runtime**: the command line creates a `Vertx`, the same `Pool` from `ConfigLoader`/`PgConfig`, and runs
  `DatabaseService.migrate`. It then uses the same repositories as the service (`OrganisationRepository`,
  `ApiKeyRepository`, the package and snapshot access repositories), so every change commits with the same
  revision bumps and notifications as the HTTP path.
  - The `main` thread waits on a `CountDownLatch` (the pattern `Bootstrap.shutdown` already uses). It never calls
    `.get()`/`.join()` (Principle I) and never runs on an event loop.
- **Input merge**: the JSON file is read first, then each flag overrides its field (FR-023). `--address-line`
  repeated replaces the file's list. JSON field names are camelCase (`nameShort`, `addressLines`, …).
- **Output and exit codes**: results go to stdout as JSON. Errors go to stderr as one line and set the exit code:
  - `0` success; `2` usage or validation error; `3` not found; `4` conflict (duplicate name, system-organisation
    protection); `1` any other failure.
  - Log4j writes to stderr in CLI mode, so stdout stays pure JSON.
- **Keys**: `key create` generates 32 random bytes (`SecureRandom`, base64url, 43 characters, which matches
  `KEY_FORMAT`), prints the key once in the JSON result, and stores only the hash (FR-024). `--key-stdin` accepts an
  operator-supplied key instead. `key revoke --name` reuses `ApiKeyRepository.revoke`. Key names stay globally
  unique (the existing constraint), and `bootstrap` stays reserved.
- **Audit**: each change is logged at INFO (`cli: org create name_short=…`). Key values are never logged
  (`NoSecretsInLogsTest` is extended).

**Rationale**: one artifact and one configuration surface, and the operator needs nothing but database access. No
HTTP API for organisations (out of scope).

**Alternatives considered**: a separate CLI module or jar (rejected as a second artifact to build and publish); an
HTTP admin API (out of scope in the spec).

## R10. System organisation configuration

**Decision**:
- **Settings**: new `ConfigLoader` entries `FV_SYSTEM_ORG_NAME_SHORT`, `_NAME_LONG`, `_EMAIL`, `_PHONE`, `_WEBSITE`,
  `_COUNTRY`, `_POST_CODE`, `_ADDRESS_LINES` (lines separated by `|`), `_CONTACT_PERSON_NAME`,
  `_CONTACT_PERSON_MOBILE` and `_CONTACT_PERSON_EMAIL`, mapped to `systemOrg.*` in `config.json`.
- **Validation**: values are validated at load, and an invalid value fails start-up with a configuration error, like
  `FV_ADMIN_API_KEY`.
- **Applying them**: V8 inserts the row with defaults (`system`, `FHIR Validation Service`). After migration and
  before the bootstrap key is installed, `Bootstrap` runs `installSystemOrganisation`, which updates only the
  configured fields (FR-004) and logs which fields it set.
- **Not configurable**: type and active state.
- **Bootstrap key**: `upsertBootstrap` always writes `owner = SYSTEM_ID` (FR-006).

## R11. Migration V8 and rolling upgrade

**Decision**: `V8__organisations.sql` (data-model §1) does the following:
- creates `organisations` and inserts the system organisation;
- adds `owner UUID NOT NULL DEFAULT '…0001' REFERENCES organisations(id)` to `api_keys` and
  `fhir_implementation_guides`, and `is_shareable BOOLEAN NOT NULL DEFAULT true` to `fhir_implementation_guides`;
- creates `fhir_validator_snapshots` and backfills one row per existing `included_ig_packages` entry (owner system,
  shareable);
- creates indexes on every `owner` column.

`fhir_validator_logs` is unchanged (R1).

**Rationale**:
- Database-level defaults mean 3.0 instances, which don't know the columns, still insert valid rows during a rolling
  upgrade. Their writes are owned by the system organisation and shareable, which matches the default behaviour.
- 3.0 instances don't enforce access. The upgrade guide therefore says to create non-system organisations and private
  content only after every instance runs 3.1.

**Version**: **3.1.0** (minor). The change is additive: new optional request fields, new response fields, new
operations, and a new 403 that only applies to keys of deactivated organisations. With the defaults, previous
clients see identical results (US4, SC-001). `MigrationUpgradeIT` covers an empty database and 3.0.0 → 3.1.0.

## R12. Unauthenticated health details

**Decision**: `ValidatorRegistry.details()` lists, per FHIR version, only contexts whose snapshot access is
shareable *and* whose resolved packages are all shareable. The other contexts are summarised as
`privateContexts: <count>` with their states aggregated (`{ACTIVE: n, BUILDING: m, …}`). Owners are never listed.
Readiness still counts every pinned context (FR-021).

**Rationale**: `/health` is unauthenticated, and listing keys or owners would disclose private content.

## R13. Responses that report ownership (FR-020)

**Decision**:
- **Validation**:
  - The JSON `ValidationResponse` gains `igOwner` (short name) and `igShareable` next to the existing
    `igPackage`. For the base context, the values are the system organisation's short name and `true`.
  - For the OperationOutcome forms, the body stays unchanged (spec 004 R9) and two headers are added:
    `X-Validation-Context-Owner` and `X-Validation-Context-Shareable`.
- **Include, register, upload, PATCH**: each response gains `owner` and `shareable`.
- **Organisation reference**: always the short name, never the UUID.

## R14. Performance and memory

- **Hot path (validation against a built snapshot)**: adds one in-memory check over the resolved set (a few hash
  lookups and no allocation beyond the iterator). There is no database round trip. The target stays p95 under
  200 ms (SC-007), and `scripts/bench-validate.sh` before and after goes in the PR.
- **Authentication**: still one query per key per TTL, now with a join to `organisations` (primary-key lookup).
- **Memory**:
  - two fields per catalog entry and per pinned snapshot, plus three per cached key, so a few kilobytes even with
    thousands of packages;
  - no new caches;
  - existing bounds are unchanged (auth cache 10,000 entries, on-demand contexts `FV_IG_MAX_ON_DEMAND`).
- **Command line**: a short-lived process with a pool of 1–2 connections (`FV_PG_POOL_SIZE` is ignored in CLI mode).
