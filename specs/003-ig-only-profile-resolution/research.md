# Research: IG-Only Profile Resolution

**Feature**: [spec.md](./spec.md) | **Date**: 2026-11-03

The HAPI behaviour described here was read from the HAPI FHIR **8.12.1** source jars in `~/.m2`
(`hapi-fhir-validation`, `hapi-fhir-base`). Service paths are relative to `src/main/java/nzi/fhir/validator/`.
Nothing in this file has been confirmed by running the code. R1 and R2 are verified by tests in the tasks.

## R1. Can validation reach the network today?

**Decision**: No change to the validator wiring is needed. FR-002 is protected by a regression test, not
by new code.

**Rationale**:
- `FhirInstanceValidator` creates no `IValidatorResourceFetcher` by default (the
  `validatorResourceFetcher` field stays `null`), and `SnapshotBuilder.assembleWith` never sets one. The
  core `InstanceValidator` therefore has no fetcher to call for unknown profiles or references.
- Every support in the chain answers from memory: `DefaultProfileValidationSupport`,
  `CustomNpmPackageValidationSupport` (packages already read from PostgreSQL),
  `InMemoryTerminologyServerValidationSupport`, `CommonCodeSystemsTerminologyService` and
  `SnapshotGeneratingValidationSupport`. None of them makes HTTP calls.
- Package downloads (`SafePackageFetcher`) happen only on the admin paths (`/igs/register`, dependency
  resolution during `include-ig`), never while a request is validated.

The guard is a test that names a profile at `http://127.0.0.1:<port>/StructureDefinition/x` served by a
throwaway local HTTP server, then asserts a 400 response and **zero** hits on that server. If anyone adds
a fetcher later, the test fails.

**Alternatives considered**: installing an explicit "deny" fetcher. This was rejected because it adds code
for a path that doesn't exist; the test covers the risk.

## R2. How HAPI resolves a requested profile, and why a pre-check is needed

**Findings** (`ValidatorWrapper.validate`):
- Each `ValidationOptions.getProfiles()` URL goes through `fetchAndAddProfile`. A miss adds a
  "Failed to retrieve profile" message to a *side list*. That list is added to the results **only when no
  profile at all resolved** (`if (profiles.isEmpty() && !invalid.isEmpty())`). This explains finding #1:
  `?profile=known,typo` silently drops `typo`.
- `meta.profile` URLs (JSON) go through the same helper. The core `InstanceValidator` also reports an
  unknown `meta.profile` itself (`VALIDATION_VAL_PROFILE_UNKNOWN`), and HAPI raises that to ERROR
  because `errorForUnknownProfiles` defaults to `true`. This is the behaviour Q2 keeps.

**Decision**: add a resolution **pre-check** inside `ValidatorSnapshot.validate`, on the validation
worker. It runs after the lenient parse (so an unparseable body is still `invalid-resource`) and before
`validateWithResult`. The pre-check:
1. Normalises the requested list: trims entries, drops empty ones, removes duplicates and keeps the first
   occurrence (spec edge cases).
2. Looks each URL up with `chain.fetchStructureDefinition(url)`.
3. Accepts a hit only when it is an **exact canonical match** (R3).
4. If any URL is unresolved, throws `ProblemException(400, "unknown-profile", …)` with the
   `unresolvedProfiles` extension, and does not validate (Q1: A).

This approach keeps HAPI unchanged and keeps validation free of database and network calls, because the
lookup goes against the snapshot's in-memory chain. It also costs almost nothing: one cached map lookup
per profile.

**Alternatives considered**:
- Post-filtering HAPI's messages for "Failed to retrieve profile". Rejected: when another profile
  resolves, the message never reaches the results, so there is nothing to filter.
- A subclass of `FhirInstanceValidator` or `ValidatorWrapper`. Rejected: these are internal HAPI classes,
  so any HAPI upgrade could break the subclass.

## R3. Exact canonical matching and `url|version`

**Findings** (`PrePopulatedValidationSupport.addToMap` and `processResourceAndReturnUrls`): each
StructureDefinition is indexed under its `url`, under `url|version`, **and** under the URL's last one and
two path segments (`us-core-patient`, `StructureDefinition/us-core-patient`). A bare `?profile=us-core-patient`
would therefore resolve in HAPI.

**Decision**: the pre-check accepts a fetched definition only if the requested string equals the
definition's `url`, or equals `url + "|" + version`. Anything else counts as unresolved, which meets FR-003
("exact canonical URL"). The same rule decides which `meta.profile` entries go into `appliedProfiles`.

**Duplicate canonicals across packages**: `addToMap` uses `Map.put`, so the **last** package added wins.
Packages are added in the order of the snapshot's resolved list, which `ConfigRevisionRepository.array`
sorts. In the chain, core definitions (`DefaultProfileValidationSupport`) come before the packages. The
result is deterministic for a given resolved set, as FR-003 requires. The rule is recorded in the
contract, and it is not changed here.

**Alternatives considered**: rejecting `|version` requests. Rejected because HAPI already indexes by
`url|version`, and the spec expects version selection.

## R4. Status code and problem type for unresolved profiles

**Decision**: HTTP **400** with problem `type` set to `unknown-profile`, the curated `detail`
"One or more requested profiles are not available for FHIR <version>; include the IG that defines them",
and the extension `unresolvedProfiles: [ ... ]`. The response is always problem JSON
(`application/problem+json`), whatever `Accept` asks for, the same as every other 4xx from this endpoint.

**Response format**: always problem JSON, never negotiated to an `OperationOutcome` (clarification Q4: A).

**Limits (FR-006a)**: `ValidationRequestOptions.fromRequest` normalises the list (trim, drop empties,
de-duplicate in order), then rejects more than 10 entries, or any entry over 1024 characters, with
`ProblemException(400, "invalid-request", …)`. It runs on the event loop, in the handler, before
admission, because it is O(n) over a bounded query string. The constants are `MAX_PROFILES = 10` and
`MAX_PROFILE_URL_LENGTH = 1024`, both fixed.

**Ordering**: the checks run in this order, and the first failure wins:
1. version, content type and profile limits, giving 400, 415 or 406;
2. readiness and admission, giving 503 (unchanged);
3. the parse, giving 400 `invalid-resource` (unchanged);
4. Bundle size, giving 413 (unchanged);
5. profile resolution, giving 400 `unknown-profile` (new);
6. validation, giving 200.

**Metrics**: a new outcome label `unknown_profile` on the validation metric, so it isn't counted as a server
`error`. `FhirValidationService` maps a `ProblemException` of type `unknown-profile` to that label.

**Alternatives considered**: 422. Rejected because the service already uses 400 for client input that is
semantically wrong (`invalid-resource`, `unsupported-fhir-version`), and Q1 chose 400.

## R5. What the removal touches

**Decision**: remove these, with no replacement:

| Area | Removed |
|---|---|
| HTTP | `ProfileApi`, the `HttpVerticle` route registration, the `profileApiRegisterProfile` operation in `openapi.yaml`, and `register-profile` in the `ApiKeyAuthHandler` admin regex and in the `AdminAdmissionHandler` Javadoc |
| Service | `ProfileService`, plus `LocalApplyWait` if nothing else uses it (check: `IgPackageService` uses it, so it stays) |
| Validator | `ProfileStore`, `CustomProfileValidationSupport`, `ValidatorSnapshot.profileStore()` and `invalidateCaches()`, `SnapshotBuilder.completeProfiles` and `reassemble`, `ValidatorRegistry.refreshProfiles` and `activeSupports`, and the `ProfileStore` constructor arguments |
| Sync | `ConfigSync`: the profile `loadAll` on start, `refreshChanged` and `refreshProfiles` in `runSync`, and the `setProfileRefreshOverride` test hook. `runSync` becomes "read revision, then `registry.apply`". |
| Revisions | `ConfigRevisionRepository.KIND_PROFILE` and `bump()`. The only remaining writer is `updateIgSet`, and the NOTIFY payload keeps `kind: "IG"` |
| Metrics | the `fv.profile.count` gauge and the `ServiceMetrics.bind(…, profileStores)` parameter |
| Wiring | the profile stores and services in `ServiceRegistry`, and `profileServices()` / `profileApi()` |

`SnapshotBuilder`'s "keep one worker free" reservation stays: upload parsing and DNS still need it. Only
the Javadoc that mentions profiles changes. `SnapshotPregenerator` stays because
`CustomNpmPackageValidationSupport` uses it.

**Rationale**: FR-010 and FR-011. With no mutable profile source, a snapshot can only change through a
rebuild, which is triggered by a change to the resolved package set. That removes the
reassembly-and-invalidation path, and the race surface `SnapshotRaceTest` and `ConcurrencyIT` partly
covered.

## R6. Schema migration (Q3: B)

**Decision**: add `V6__remove_custom_profiles.sql`:
- `DROP TABLE IF EXISTS fhir_validator_schema.fhir_profiles;`
- `ALTER TABLE fhir_validator_schema.fhir_validator_logs DROP COLUMN IF EXISTS included_profiles;`

The migration does nothing else: IG tables, configuration rows, `revision`, `included_ig_packages`,
`resolved_ig_packages` and `api_keys` are untouched (FR-015). V1–V5 are not edited (Principle VIII).

**Rationale**: the column has never been read, and the spec's assumptions put it in scope. `IF EXISTS`
makes the migration safe on databases where it was dropped by hand.

**No safety gate** (clarification: accepted risk). V6 drops the table unconditionally, even when it still
has rows. It has no acknowledgement variable and no pre-check. The only safeguard is the "Export first"
block that opens `docs/upgrade-2.0.md` and the CHANGELOG (FR-015a).

**No down-migration** (FR-015b). Rolling back is done by restoring the backup taken before the upgrade.
A 1.x instance pointed at a V6 database isn't supported.

**No mixed fleet** (FR-015c). Every 1.x instance stops before the first 2.0 instance starts.
`scripts/rolling-restart.sh` gets a guard: it refuses to run when the target image's major version
differs from the running one's. The upgrade guide states the rule too.

**Alternatives considered**: keep the table for one release (Q3: A), rejected by the user. A migration
that writes a package (Q3: C) was rejected as too complex: it builds an archive in SQL, and the operator
would still have to include the result.

## R7. Export step (FR-014, FR-015a)

**Decision**: add `scripts/export-custom-profiles.sh`, a POSIX shell script that needs only `psql`, `tar` and
`gzip`, and runs against a **1.x** database before the upgrade:
- It reads `fhir_profiles` grouped by `fhir_version` (`3.0.2`, `4.0.1`, `4.3.0`, `5.0.0`).
- For each FHIR version it writes a ready-to-upload IG package (`<name>.<rN>-<version>.tgz`). The package
  contains `package/package.json`, which declares `name`, `version`, `fhirVersions`, `type: fhir.ig`, the
  core dependency and any `--dependency name#version` options, plus one
  `package/StructureDefinition-<n>.json` per profile, with `profile_json::text` written verbatim.
- `--check` only prints the profile count per FHIR version and exits 0. This answers "do I have any?"
  (FR-015a).
- Connection settings come from the standard `PG*` environment variables (or `FV_PG_*`, mapped to them).
  No secrets go on the command line.
- If no profiles exist, it prints that, writes nothing and exits 0.

**Why a package and not loose files**: the result is uploaded with `/igs/upload` and included with
`include-ig`, the only route left. SC-007 (30 minutes for 50 profiles) becomes "run the script, upload,
include".

**Dependencies**: custom profiles often derive from an IG profile (dk-core in the fixtures). The base
must be in the included set. `CustomNpmPackageValidationSupport` builds snapshots across the whole
included set, so either declaring `--dependency hl7.fhir.dk.core#3.4.0` or including dk-core separately
works. The upgrade guide recommends declaring it, so the dependency resolver includes it.

**Alternatives considered**: a Java subcommand in the 2.0.0 jar. Rejected because by then the table is
gone; the export has to run against the old database before the upgrade.

## R8. Upgrade rehearsal and existing tests

**Decision**:
- Add `scripts/upgrade-rehearsal-2.0.sh`. It starts **1.2.0**, uploads dk-core and includes it,
  registers the dk Patient profile, and records validation results with `?profile=`. Then it runs
  `export-custom-profiles.sh --dependency hl7.fhir.dk.core#3.4.0`, takes a `pg_dump` backup, **stops the
  1.2.0 container**, starts the new image on the same database (Flyway applies V6), and asserts that `fhir_profiles` is gone and the IG and configuration
  counts are unchanged. Finally it uploads and includes the exported package and asserts the same
  error-level issues and `appliedProfiles` as before. As a last step it restores the backup into a fresh
  database and starts 1.2.0 on it, which proves that the documented rollback works. CI runs it like the
  1.0 rehearsal.
- `scripts/upgrade-rehearsal.sh` (0.1.2 → 1.0.0) stays as a historical record and still targets
  the 1.0.0 image. Its header notes that 2.0.0 operators follow `docs/upgrade-2.0.md`.
- Tests:
  - Delete: `ProfileApiContractTest`, `ProfileServiceTest`, `ProfileStoreTest`, `ProfileFirstUseIT`.
  - Rewrite on top of `include-ig`: the profile parts of `ConfigPropagationIT`, `ConcurrencyIT`,
    `SnapshotRaceTest` and `ApiKeyAuthIT`. `ApiKeyAuthIT` replaces `register-profile` with `include-ig`
    as the admin example.
  - Extend `MigrationUpgradeIT` with V6: after the migration, the table and column are gone and IG rows
    survive.
  - New regression tests: listed in [quickstart.md](./quickstart.md) and in FR-018.

## R9. Versioning and constitution

**Decision**:
- Bump `pom.xml` to **2.0.0**, as Principle IV requires for a removed operation and a changed status
  code for an existing input.
- Amend the constitution to **1.0.2** (PATCH, wording only). Principle II: "registered IG packages,
  profiles and the active validator configuration" becomes "registered IG packages and the active
  validator configuration", and "the profile stores" is dropped from the list of derived caches.
- Add `docs/upgrade-2.0.md`, ordered: (1) **Export first** (`--check`, then export), (2) **Back up**
  (`pg_dump`, mandatory), (3) **Stop every 1.x instance** (no rolling restart), (4) start 2.0, which
  applies V6, (5) upload and include the exported packages, (6) **Rollback** means restoring the backup
  and starting 1.2.0. There is no down-migration.
- In the CHANGELOG, open `2.0.0` with **Breaking**, **Upgrade (do this first)**, **Fixed** (#1, #9) and
  **Removed**.
