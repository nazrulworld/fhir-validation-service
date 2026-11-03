---

description: "Task list for IG-only profile resolution (remove custom profile registration)"
---

# Tasks: IG-Only Profile Resolution (Remove Custom Profile Registration)

**Input**: Design documents from `specs/003-ig-only-profile-resolution/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-api.md, contracts/export-script.md, quickstart.md

**Tests**: These are required. FR-018 lists the regression tests, and constitution Principle V requires
tests first, run against real PostgreSQL through Testcontainers. Write each story's tests first and
confirm they fail before implementing.

**Organization**: Tasks are grouped by user story, in priority order: US1 (P1), US2 (P1), US3 (P2), US4 (P2).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: The user story the task belongs to (US1–US4)
- Production paths are relative to `src/main/java/nzi/fhir/validator/`, written in full below. Test paths are under `src/test/java/nzi/fhir/validator/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The branch, the version bump and the shared test fixtures.

- [X] T001 Create and switch to git branch `003-ig-only-profile-resolution` from `main` (repository root)
- [X] T002 Bump the project version from `1.2.0` to `2.0.0-SNAPSHOT` in `pom.xml` (2.0.0 is a breaking release; constitution Principle IV)
- [X] T003 [P] Add a test helper `TestPackages.profilePackage(String name, String version, JsonObject... structureDefinitions)` that wraps `requiredPatientElements(...)` profiles in an R4 NPM `.tgz` (via the existing `npmPackage(...)`), in `src/test/java/nzi/fhir/validator/testsupport/TestPackages.java`
- [X] T004 [P] Add a test helper `TestPackages.includePackage(ServiceRegistry services, byte[] tgz, String name, String version)` that uploads through `IgPackageService` and includes it for R4, returning a `Future<Void>` that completes once the change is applied locally, in `src/test/java/nzi/fhir/validator/testsupport/TestPackages.java`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared request and response building blocks that US1 and US2 both use.

**⚠️ CRITICAL**: Finish this phase before starting any user story.

- [X] T005 Add the static factories `ProblemException.unknownProfiles(SupportedFhirVersion version, List<String> unresolved)` and `ProblemException.profileLimits()` in `src/main/java/nzi/fhir/validator/web/ProblemException.java`:
  - `unknownProfiles` returns 400, type `unknown-profile`, detail `"One or more requested profiles are not available for FHIR <version>; include the IG that defines them"`, and extension `unresolvedProfiles`.
  - `profileLimits` returns 400, type `invalid-request`, detail `"At most 10 profiles may be requested, each at most 1024 characters"`, with no URLs echoed.
- [X] T006 Add the outcome constant `OUTCOME_UNKNOWN_PROFILE = "unknown_profile"` in `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java`

**Checkpoint**: Foundation ready.

---

## Phase 3: User Story 1 - A requested profile is either applied or the request fails loudly (Priority: P1) 🎯 MVP

**Goal**: Requested profiles are applied, or the request gets 400 `unknown-profile` naming every unresolved URL. A resource is never reported as valid when a requested profile was dropped. `appliedProfiles` is truthful, and the profile list is bounded to 10 entries of at most 1024 characters.

**Independent Test**: With an IG included for R4, validate a violating fixture three ways: `?profile=P`, `?profile=P,typo` and `?profile=typo`. The first reports the violation. The second and third return 400 naming `typo` and never return `valid: true`.

### Tests for User Story 1 (write first; they must fail on 1.2.0 behaviour)

- [X] T007 [P] [US1] Create `src/test/java/nzi/fhir/validator/core/model/ValidationRequestOptionsTest.java`. It covers:
  - trimming, dropping empty entries and de-duplicating in first-occurrence order (`" a,,b ,a"` gives `[a, b]`);
  - exactly 10 distinct entries accepted;
  - the same URL repeated 20 times accepted (the limit applies after de-duplication);
  - 11 distinct entries rejected with `invalid-request`;
  - one entry of 1025 characters rejected with `invalid-request`;
  - one entry of 1024 characters accepted.
- [X] T008 [P] [US1] Create `src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java` (CoreHarness, real PostgreSQL), with an R4 package holding profiles P (requires `birthDate`) and Q (requires `gender`) included. It covers:
  - (a) `[P]` on a Patient without `birthDate` gives `valid=false` with a P issue and `appliedProfiles == [P]`;
  - (b) `[P, Q]` gives issues from both and `appliedProfiles == [P, Q]`;
  - (c) `[P, U]` fails with a `ProblemException` of type `unknown-profile`, `unresolvedProfiles == [U]`, and no `Outcome`;
  - (d) `[U]` behaves the same as (c);
  - (e) `[P|<P.version>]` resolves, and `[P|9.9.9]` gives `unknown-profile`;
  - (f) a bare id (P's last path segment) and `StructureDefinition/<id>` both give `unknown-profile` (exact canonical match, research R3);
  - (g) a Patient with `meta.profile=[U2]` and no requested profile gives 200-equivalent `valid=false` with an ERROR issue for U2, and `appliedProfiles` doesn't contain U2;
  - (h) a resource with `meta.profile=[P]` and `[P]` requested lists P once;
  - (i) wrong resource type: an included Observation profile requested for a Patient resolves, and the request completes with `valid=false` and a type-mismatch issue (not `unknown-profile`);
  - (j) non-resource profile: an included extension StructureDefinition URL requested as a profile resolves and completes with the validator's issues (not `unknown-profile`; spec edge case);
  - (k) Bundle: a Bundle whose first entry is a Patient, with Patient profile P requested, is validated against P on that entry, as the reference validator does, and `appliedProfiles == [P]`.
- [X] T009 [P] [US1] Add contract cases to `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java`:
  - `?profile=<known>,http://example.org/StructureDefinition/typo` gives 400, `Content-Type: application/problem+json`, `type=unknown-profile`, and `unresolvedProfiles=["http://example.org/StructureDefinition/typo"]`;
  - the same request with `Accept: application/fhir+xml` still gives problem JSON, not an OperationOutcome;
  - an unparseable body plus an unknown profile gives 400 `invalid-resource` (the parse is checked first);
  - 11 distinct profiles give 400 `invalid-request`, and the body has no `unresolvedProfiles`;
  - the existing `profileQueryParameterIsApplied` case still passes.
- [X] T010 [P] [US1] Add a regression case to `src/test/java/nzi/fhir/validator/core/service/FhirValidationServiceTest.java`: an unresolved profile records the metric outcome `unknown_profile`, not `error`.

### Implementation for User Story 1

- [X] T011 [US1] Rewrite `ValidationRequestOptions.fromRequest` in `src/main/java/nzi/fhir/validator/core/model/ValidationRequestOptions.java`:
  - split on `,`, trim each entry, drop empty entries, de-duplicate with a `LinkedHashSet` (first occurrence kept);
  - enforce "At most 10 entries after parsing, each at most 1024 characters" with the fixed constants `MAX_PROFILES = 10` and `MAX_PROFILE_URL_LENGTH = 1024`, throwing `ProblemException.profileLimits()`;
  - return an immutable `List<String>`.
- [X] T012 [US1] Catch the `ProblemException` from `ValidationRequestOptions.fromRequest` in the validate handler of `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java` and answer through `ErrorResponder.fail` before admission. Keep it in the request-shape group, after the version, content-type and Accept checks and before the empty-body check (contract precedence).
- [X] T013 [US1] Add the resolution pre-check in `ValidatorSnapshot.validate(...)` in `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java`:
  - it runs after the lenient parse and the Bundle-size check, and before `validator.validateWithResult`;
  - for each requested URL, call `chain.fetchStructureDefinition(url)` and accept the hit only if `url.equals(sd.url)` or `url.equals(sd.url + "|" + sd.version)` (read through `FhirTerser` or `IBaseResource` reflection for every supported version);
  - collect the misses in request order, and throw `ProblemException.unknownProfiles(version, misses)` if any are missing;
  - pass only the resolved URLs to `options.addProfile`.
- [X] T014 [US1] Make `appliedProfiles` truthful in `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java`: the requested profiles (all resolved by now) in request order, then the `meta.profile` entries that pass the same exact-match lookup, de-duplicated in a `LinkedHashSet`. An unresolved `meta.profile` is never added, but HAPI still reports it as ERROR (Q2: A, unchanged).
- [X] T015 [US1] Map a failure that is a `ProblemException` of type `unknown-profile` to `ServiceMetrics.OUTCOME_UNKNOWN_PROFILE` in the metrics `onComplete` of `src/main/java/nzi/fhir/validator/core/service/FhirValidationService.java`. Other failures keep `error` or `unparseable`.
- [X] T016 [US1] Log the unresolved URLs at DEBUG only (never INFO or above), in the validate handler's `.recover(...)` in `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`. When the failure is a `ProblemException` of type `unknown-profile`, keep the line at DEBUG. The request id is added by the log layout (`%vcl{requestId}`, Vert.x context-local data, which also reaches worker threads), so constitution Principle VII is met without passing it explicitly. Keep `ValidatorSnapshot` free of logging, so it stays a pure validation component.
- [X] T017 [US1] Update the validate operation in `src/main/resources/openapi.yaml`:
  - the new `profile` parameter description from contracts/http-api.md (the limits are stated in the description; don't add a schema `maxLength`, because the limit applies per URL after splitting);
  - the new `ValidationResponse.appliedProfiles` description;
  - a new `components/schemas/UnknownProfileProblem` (allOf `Problem`, plus `unresolvedProfiles: string[]`);
  - the 400 response description naming `invalid-resource`, `invalid-request` and `unknown-profile`.
- [X] T018 [US1] Update `src/test/java/nzi/fhir/validator/core/validator/ValidatorSnapshotSwapIT.java`. Its `classify(...)` currently detects an unknown requested profile from a `Validation_VAL_Profile_Unknown` message, and must now treat a `ProblemException` of type `unknown-profile` as "unknown".

**Checkpoint**: US1 works on its own. T007–T010 and T018 are green, and finding #1 (the silent drop) and finding #9 (untruthful `appliedProfiles`) are fixed.

---

## Phase 4: User Story 2 - Profiles come only from included IGs, never from the network (Priority: P1)

**Goal**: Only included packages, their dependencies and core supply profiles. Uploaded packages that aren't included supply none. The service makes no outbound request while it validates, and newly included profiles propagate to every instance.

**Independent Test**: Upload packages A and B, and include only A. A profile from A resolves. A profile from B, or a URL served by a local HTTP server, gives 400, and the server records 0 hits.

### Tests for User Story 2 (write first)

- [X] T019 [P] [US2] Add a case to `src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java`: upload package B (profile only in B) without including it, then validate with B's profile. Expect `unknown-profile` naming it (FR-004).
- [X] T020 [P] [US2] Add a no-network case to `src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java` (FR-002, SC-002). Start a Vert.x `HttpServer` on `127.0.0.1:0` that counts requests and serves a valid StructureDefinition JSON for any path. Validate with `?profile=http://127.0.0.1:<port>/StructureDefinition/x`, and also with a resource whose `meta.profile` is that URL. Expect `unknown-profile` (and an ERROR issue for the `meta.profile` case) and **0** requests on the server.
- [X] T021 [P] [US2] Add a determinism case to `src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java` (FR-003, SC-004). Include two packages, `dup.a#1.0.0` and `dup.b#1.0.0`, that both define canonical X, with versions `1.0.0` and `2.0.0` and different required elements. A request for unversioned `X` resolves to the documented winner (the last package in the sorted resolved list, `dup.b`), and does so identically on two `CoreHarness` instances sharing the database. `X|1.0.0` and `X|2.0.0` each select their own definition.
- [X] T022 [P] [US2] Add a case to `src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java` (US2 scenario 5): include `p.test#1.0.0` with profile P version 1.0.0, then include `p.test#2.0.0` (the same canonical, version 2.0.0). Expect `P|2.0.0` to resolve and `P|1.0.0` to give `unknown-profile`.
- [X] T023 [US2] Rewrite `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java` so that every test propagates through `include-ig` of a `TestPackages.profilePackage(...)` instead of `profileServices().registerProfile(...)`:
  - keep `profileAndIgChangesReachTheOtherInstance` (rename it to `includedProfileReachesTheOtherInstance`), the polling-only variant and `slowRebuildsOfOtherVersionsDoNotDelayProfilePropagation`, all on the IG path, so FR-011 and SC-005 are guarded;
  - change `bEnforces` so it treats a `ProblemException` of type `unknown-profile` as "not yet";
  - delete `failedProfileRefreshMakesVersionUnavailableUntilItRecovers`, `overlappingProfileRegistrationsBothReachTheOtherInstance` and the `setProfileRefreshOverride` local-apply test, because their mechanism is removed.

### Implementation for User Story 2

- [X] T024 [US2] Confirm that no `IValidatorResourceFetcher` is configured and none can be added implicitly. In `SnapshotBuilder.assembleWith`, add a comment citing FR-002 and research R1 next to `new FhirInstanceValidator(chain)`, and don't call `setValidatorResourceFetcher`. This is a comment and code-review task only; T020 is the guard. File: `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java`.

**Checkpoint**: US1 and US2 are both green. Profile resolution matches the CLI's `-ig`/`-profile` model.

---

## Phase 5: User Story 3 - One way to manage conformance: custom profiles ship as IG packages (Priority: P2)

**Goal**: `register-profile` and everything that exists only for it are gone. The snapshot is immutable, and IG propagation is unchanged.

**Independent Test**: `POST /R4/register-profile` gives 404 without an admin key. `openapi.yaml` has no `profileApiRegisterProfile`. `/metrics` has no `fv_profile_count`. A profile packaged as an IG, uploaded and included, validates.

### Tests for User Story 3 (write first)

- [X] T025 [P] [US3] Add a contract case to `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java`: `POST /R4/register-profile` with and without an admin key gives 404 (problem JSON), and the loaded `openapi.yaml` has no operation id `profileApiRegisterProfile`.
- [X] T026 [P] [US3] In `src/test/java/nzi/fhir/validator/web/ObservabilityIT.java`, replace the `fv_profile_count\{version="STU3",?\} \d` expectation with an assertion that no `fv_profile_count` series exists. After triggering one `unknown-profile` 400, assert that a validation series with `outcome="unknown_profile"` exists.
- [X] T027 [P] [US3] In `src/test/java/nzi/fhir/validator/web/ApiKeyAuthIT.java`, replace every `/R4/register-profile` admin example (lines ~67–110) with `/R4/include-ig` of an uploaded test package, keeping the same admin, validate-only and anonymous key assertions.
- [X] T028 [P] [US3] Add a case to `src/test/java/nzi/fhir/validator/web/AdminAdmissionIT.java` asserting that `register-profile` no longer takes an admin admission slot: it gives 404 even while `FV_ADMIN_MAX_PENDING` is saturated.

### Implementation for User Story 3

- [X] T029 [US3] Delete `src/main/java/nzi/fhir/validator/web/endpoint/ProfileApi.java`, and remove `services.profileApi().includeRoutes(routerBuilder)` from `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`.
- [X] T030 [US3] Remove the `/{version}/register-profile` path, its operation `profileApiRegisterProfile`, and the `register-profile` mention in the security-scheme description from `src/main/resources/openapi.yaml`.
- [X] T031 [US3] Change the admin pattern to `^/(igs/(upload|register)|[^/]+/include-ig)/?$` in `src/main/java/nzi/fhir/validator/core/security/ApiKeyAuthHandler.java`, and update the operation list in the class Javadoc of `src/main/java/nzi/fhir/validator/web/AdminAdmissionHandler.java`.
- [X] T032 [US3] Delete `src/main/java/nzi/fhir/validator/core/service/ProfileService.java` and `src/main/java/nzi/fhir/validator/core/service/CustomProfileValidationSupport.java`. Keep `LocalApplyWait.java`, which `IgPackageService` uses.
- [X] T033 [US3] Simplify `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java`:
  - remove `CustomProfileValidationSupport` from the chain in `assembleWith` (the order becomes core, packages, in-memory terminology, common code systems, snapshot generating);
  - remove the `ProfileStore` parameters from `build`, `buildNow`, `assemble` and `assembleWith`;
  - delete `completeProfiles(...)` and `reassemble(...)`;
  - update the class Javadoc so the reserved worker covers upload parsing and DNS, not profile work.
- [X] T034 [US3] In `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java`, remove the `profileStore` field, constructor parameter and accessor, and `invalidateCaches()`. Update the class Javadoc: the snapshot is immutable for its whole life.
- [X] T035 [US3] In `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`, remove the `profileStores` field and constructor parameter, `refreshProfiles(...)` and `activeSupports(...)`. Pass no store to `builder.build(...)`. Fix the Javadoc that mentions profile refreshes.
- [X] T036 [US3] Simplify `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java`:
  - replace the `profileStores` constructor parameter with the set of enabled `SupportedFhirVersion`s, used for `states`;
  - `start()` reads `ConfigRevisionRepository.current(pool)` and applies it, with no `loadAll`;
  - `runSync` reads the current revision and calls `registry.apply`, with no profile refresh and no `markStale("profile changes could not be applied")`;
  - delete `setProfileRefreshOverride` and the `profileRefreshOverride` field, and drop the now unused `executors` field if nothing else needs it.
- [X] T037 [US3] Delete `src/main/java/nzi/fhir/validator/core/validator/ProfileStore.java`.
- [X] T038 [US3] In `src/main/java/nzi/fhir/validator/core/service/ConfigRevisionRepository.java`, delete `KIND_PROFILE` and `bump(...)`, and update the class Javadoc: the revision increases on IG changes only.
- [X] T039 [US3] In `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java`, delete the `fv.profile.count` gauge and the `Map<SupportedFhirVersion, ProfileStore>` parameter of `bind(...)`.
- [X] T040 [US3] Remove the profile stores and services from the wiring in `src/main/java/nzi/fhir/validator/ServiceRegistry.java`:
  - the `ProfileStore` map, `store.setSnapshotSources`, the `ProfileService` map, the `ProfileApi` field;
  - the `profileServices()` and `profileApi()` accessors, and the related imports;
  - pass the enabled versions to `ValidatorRegistry` and `ConfigSync`, and call `metrics.bind(...)` without stores;
  - fix the class Javadoc ("loads profiles and builds the validators" becomes "builds the validators").
- [X] T041 [US3] Fix the class Javadoc of `src/test/java/nzi/fhir/validator/testsupport/CoreHarness.java` ("validators, profiles, IG inclusion…") and any helper that calls `profileServices()`, in `CoreHarness.java` or `src/test/java/nzi/fhir/validator/testsupport/AppHarness.java`.
- [X] T042 [P] [US3] Delete the obsolete tests:
  - `src/test/java/nzi/fhir/validator/web/endpoint/ProfileApiContractTest.java`
  - `src/test/java/nzi/fhir/validator/core/service/ProfileServiceTest.java`
  - `src/test/java/nzi/fhir/validator/core/service/ProfileFirstUseIT.java`
  - `src/test/java/nzi/fhir/validator/core/validator/ProfileStoreTest.java`
- [X] T043 [US3] In `src/test/java/nzi/fhir/validator/core/validator/SnapshotRaceTest.java`:
  - delete `storedProfileHasASnapshotBeforeValidationCanSeeIt` and `firstUseOfNewProfilesDoesNotBreakConcurrentValidations`;
  - keep `packageProfileHasASnapshotOnceThePackageIsLoaded` and `packageProfilesBuiltConcurrentlyDoNotBreakValidations`, changing them to the new `SnapshotBuilder.assemble(...)` signature with no `ProfileStore`.
- [X] T044 [US3] In `src/test/java/nzi/fhir/validator/core/service/ConcurrencyIT.java` (line ~85), build the `ValidatorSnapshot` with the new constructor (no `ProfileStore`), and remove the `ProfileStore` import.
- [X] T045 [US3] Run `grep -rn -i 'ProfileStore\|ProfileService\|ProfileApi\|CustomProfileValidationSupport\|register-profile\|KIND_PROFILE\|profile.count\|refreshProfiles' src/` and clear every remaining hit, aiming for zero (SC-008, code side). Then run `mvn -q -DskipITs test-compile` to confirm the code compiles.

**Checkpoint**: The removal is complete. US1, US2 and the rewritten `ConfigPropagationIT` are green, so IG propagation is unaffected (FR-011).

---

## Phase 6: User Story 4 - Operators upgrade without losing registered profiles unknowingly (Priority: P2)

**Goal**: Operators export first, take a mandatory backup, stop every 1.x instance, then start 2.0, which applies V6 and drops the data unconditionally. They then upload and include the exported packages. Rolling back means restoring the backup.

**Independent Test**: `scripts/upgrade-rehearsal-2.0.sh` passes end to end, including the rollback restore. `MigrationUpgradeIT` proves that V6 drops only the profile objects.

### Tests for User Story 4 (write first)

- [X] T046 [P] [US4] Extend `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java`. Migrate an empty database to V5 and seed it with one IG row, one configuration row, one API key and two `fhir_profiles` rows. Migrate to V6, then assert that:
  - `fhir_validator_schema.fhir_profiles` doesn't exist;
  - the `fhir_validator_logs.included_profiles` column doesn't exist;
  - the IG, configuration and API key counts are unchanged.

  Also assert that V6 applied to a fresh empty database succeeds, and that the existing V3 assertions on `fhir_profiles` still run before V6 (migrate to target V5 first). Adjust lines ~67–111 as needed.
- [X] T047 [P] [US4] Create a shell test `scripts/test/export-custom-profiles.test.sh`. It starts `postgres:16` in Docker, applies V1–V5 from `src/main/resources/db/migration/`, and inserts two R4 profiles and one R5 profile. It asserts:
  - `--check` prints `4.0.1 2`, `5.0.0 1` and `total 3`, and exits 0;
  - an export with `--dependency hl7.fhir.dk.core#3.4.0` writes `local.custom.profiles.r4-1.0.0.tgz` and `local.custom.profiles.r5-1.0.0.tgz`, each with `package/package.json` (the right `fhirVersions` and dependencies) and N `StructureDefinition-<n>.json` files whose text equals the stored `profile_json`, plus `MANIFEST.txt`;
  - a second run into the same `--out` exits 1;
  - after applying V6, the script exits 3;
  - against an empty table, it prints the no-profiles message, writes nothing and exits 0.

### Implementation for User Story 4

- [X] T048 [US4] Create `src/main/resources/db/migration/V6__remove_custom_profiles.sql` with exactly `DROP TABLE IF EXISTS fhir_validator_schema.fhir_profiles;` and `ALTER TABLE fhir_validator_schema.fhir_validator_logs DROP COLUMN IF EXISTS included_profiles;`. It has no safety gate and no row check (an accepted risk, FR-015). Don't edit V1–V5.
- [X] T049 [US4] Create `scripts/export-custom-profiles.sh` (POSIX `sh`; needs only `psql`, `tar` and `gzip`), following `specs/003-ig-only-profile-resolution/contracts/export-script.md` exactly:
  - options `--check`, `--out DIR` (default `./custom-profiles-export`), `--name NAME` (default `local.custom.profiles`), `--package-version VER` (default `1.0.0`) and a repeatable `--dependency NAME#VERSION`;
  - `PG*` variables, falling back to `FV_PG_*`, and never a password argument;
  - map `3.0.2`, `4.0.1`, `4.3.0` and `5.0.0` to `stu3`, `r4`, `r4b` and `r5`, and to the core dependencies `hl7.fhir.r3.core`, `hl7.fhir.r4.core`, `hl7.fhir.r4b.core` and `hl7.fhir.r5.core`;
  - number the files `StructureDefinition-<n>.json` in `url` order, writing `profile_json::text` verbatim;
  - write `MANIFEST.txt`;
  - exit codes 0, 1, 2 (removing partial output) and 3 (table missing).

  Make it executable.
- [X] T050 [US4] Create `scripts/upgrade-rehearsal-2.0.sh`, modelled on `scripts/upgrade-rehearsal.sh`:
  1. Start `ghcr.io/nazrulworld/fhir-validation-service:1.2.0` with Postgres.
  2. Upload and include `src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz` for R4.
  3. Register `src/test/resources/fhir/profiles/dk/StructureDefinition-dk-core-patient.json` through `/R4/register-profile`.
  4. Record the error-level issues and `appliedProfiles` for the dk fixtures with `?profile=$DK_PROFILE`.
  5. Run `export-custom-profiles.sh --check`, expecting `total 1`, then `--dependency hl7.fhir.dk.core#3.4.0`.
  6. `pg_dump` to `$WORK/backup.sql`.
  7. **Stop the 1.2.0 container**.
  8. Start the image built from this branch on the same database.
  9. Assert that `fhir_profiles` is absent and the IG and configuration counts are unchanged.
  10. Upload and include `local.custom.profiles.r4-1.0.0.tgz`, and assert the same issues and `appliedProfiles`.
  11. Assert that the export script now exits 3.
  12. Restore `backup.sql` into a fresh Postgres, start 1.2.0 on it, and assert that the dk profile is in effect (the rollback works).

  Make it executable.
- [X] T051 [US4] Add a guard to `scripts/rolling-restart.sh`: read the major version of the running image and of the target image, and exit non-zero with "Rolling restart across major versions is not supported; see docs/upgrade-2.0.md" when they differ (FR-015c).
- [X] T052 [US4] Add a header note to `scripts/upgrade-rehearsal.sh` saying that this rehearsal covers 0.1.x → 1.0.0 only, and that 2.0.0 upgrades follow `docs/upgrade-2.0.md` and `scripts/upgrade-rehearsal-2.0.sh`.
- [X] T053 [US4] Create `docs/upgrade-2.0.md`, opening with a boxed **"Export first, this upgrade deletes custom profiles"** warning (FR-015a). Then, in order:
  1. Check whether you have profiles (`export-custom-profiles.sh --check`).
  2. Export, with `--dependency` advice for profiles based on IG profiles.
  3. **Mandatory full database backup** (`pg_dump`).
  4. **Stop every 1.x instance**: a mixed fleet isn't supported, and `rolling-restart.sh` must not be used (FR-015c).
  5. Start 2.0.0, which applies V6 and drops `fhir_profiles` unconditionally.
  6. Upload and include the exported packages.
  7. Verify with `?profile=`.
  8. **Rollback**: restore the backup and start 1.2.0. There's no down-migration (FR-015b).

  Also add a "What else changed" section covering: the 400 `unknown-profile`, the 400 `invalid-request` profile limits, the truthful `appliedProfiles`, the removed `fv.profile.count`, the new `unknown_profile` metric outcome, and the removed "profile changes could not be applied" health reason.
- [X] T054 [US4] Add a CI step that runs `scripts/upgrade-rehearsal-2.0.sh` and `scripts/test/export-custom-profiles.test.sh`, next to the existing 1.0 rehearsal step, in `.github/workflows/ci.yml`.

**Checkpoint**: All four stories are complete and independently verifiable.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T055 [P] Update `README.md`:
  - remove the "Register a custom profile" example, and rename the section to "Manage IGs (administrator key required)";
  - add one paragraph saying that profiles come only from included IGs, are never downloaded, and that custom profiles are packaged as an IG;
  - document the `?profile=` limits (10 profiles of at most 1024 characters) and the 400 `unknown-profile` response;
  - update the intro line "changes to IGs and profiles reach every instance" to say IGs.
- [X] T056 [P] Update `CHANGELOG.md` with a `## 2.0.0` entry that opens with **Upgrade, do this first** (export, back up, stop all 1.x instances; link to `docs/upgrade-2.0.md`), followed by:
  - **Breaking**: `register-profile` removed (404), the new 400 `unknown-profile`, the new 400 `invalid-request` profile limits, `fhir_profiles` dropped by V6 with no safety gate, and no rolling or mixed-fleet upgrade;
  - **Fixed**: an unknown requested profile was silently dropped when another resolved (finding #1), and `appliedProfiles` listed unresolved profiles (finding #9);
  - **Removed**: `fv.profile.count` and the PROFILE change kind.
- [X] T057 [P] Update the comments in `.env.example` that list admin operations ("upload, register, include-ig, register-profile" becomes "upload, register, include-ig"), at the `FV_ADMIN_MAX_PENDING` comment and the `FV_PACKAGE_WORKERS` "profile and DNS work" comment.
- [X] T058 [P] Amend `.specify/memory/constitution.md` to version **1.0.2** (PATCH, wording only). In Principle II, change "registered IG packages, profiles and the active validator configuration/state" to "registered IG packages and the active validator configuration/state", and drop "the profile stores" from the derived caches. Set "Last Amended: 2026-11-03".
- [X] T059 [P] Add a one-line note at the top of `docs/upgrade-1.0.md` saying that custom profiles were removed in 2.0.0, with a link to `docs/upgrade-2.0.md`.
- [X] T060 Run `grep -rn -i 'register-profile\|custom profile\|fhir_profiles\|fv.profile.count' README.md CHANGELOG.md docs/ .env.example src/main/resources/openapi.yaml` and confirm that every hit describes the removal or the upgrade, not current behaviour (SC-008).
- [ ] T061 Run a performance comparison for the PR (Principle VI, SC-006): p95 latency and steady-state heap for warm dk Patient validation with one `?profile=`, comparing 1.2.0 and this branch on the same host, using `scripts/smoke.sh` timing or the existing load-test procedure from feature 001. **Pass** if p95 is within +5% of 1.2.0 **and** under 200 ms, **and** heap is no higher than 1.2.0's. Record the numbers and the verdict in the PR description. A failure blocks merge.
- [ ] T062 Set the version to `2.0.0` (drop `-SNAPSHOT`) in `pom.xml` at release time.
- [ ] T063 Run `mvn verify` and the quickstart checks in `specs/003-ig-only-profile-resolution/quickstart.md` (sections 1–3). Confirm there are no new blocked-thread warnings, that RouterBuilder mounts every operation in `openapi.yaml`, and that every operation has a handler.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)** comes first. T003 and T004 can run in parallel after T001.
- **Foundational (Phase 2)** depends on Setup, and blocks all stories.
- **US1 (Phase 3)** depends on Foundational. It is the MVP and can ship on its own.
- **US2 (Phase 4)** depends on US1 T013 (the pre-check), because its tests assert `unknown-profile`. T019–T022 can be written alongside US1's tests.
- **US3 (Phase 5)** depends on US2 T023: `ConfigPropagationIT` must move to the IG path before the profile classes are deleted (T029–T040). It also depends on US1 T018.
- **US4 (Phase 6)**: T046 and T048 depend on nothing but Setup. T050 needs the US3 image built, so it comes after US3. T049 and T047 are independent and can start any time after Setup.
- **Polish (Phase 7)** comes after US3 and US4.

### Within each story

The tests come first and must fail. Then the request parsing, then the snapshot logic, then the wiring and contract, then the docs.

### Parallel opportunities

- Phase 1: T003 ∥ T004.
- US1 tests: T007 ∥ T008 ∥ T009 ∥ T010. After that, T011 → T012, alongside T013 → T014 → T016, then T015 and T017.
- US2 tests: T019 ∥ T020 ∥ T021 ∥ T022 (the same file, so write them in sequence or coordinate). T023 is separate.
- US3 tests: T025 ∥ T026 ∥ T027 ∥ T028. The deletions T042 can run in parallel with T029–T032. The production edits T033–T040 should run in order, because they share constructor signatures.
- US4: T046 ∥ T047 ∥ T048 ∥ T049, then T050 → T054. T051, T052 and T053 can run in parallel.
- Polish: T055 ∥ T056 ∥ T057 ∥ T058 ∥ T059.

## Parallel Example: User Story 1

```text
Task: "T007 [US1] ValidationRequestOptionsTest in src/test/java/nzi/fhir/validator/core/model/ValidationRequestOptionsTest.java"
Task: "T008 [US1] ProfileResolutionIT in src/test/java/nzi/fhir/validator/core/validator/ProfileResolutionIT.java"
Task: "T009 [US1] contract cases in src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java"
Task: "T010 [US1] metric outcome case in src/test/java/nzi/fhir/validator/core/service/FhirValidationServiceTest.java"
```

## Parallel Example: User Story 4

```text
Task: "T046 [US4] V6 assertions in MigrationUpgradeIT.java"
Task: "T047 [US4] scripts/test/export-custom-profiles.test.sh"
Task: "T048 [US4] V6__remove_custom_profiles.sql"
Task: "T049 [US4] scripts/export-custom-profiles.sh"
```

## Implementation Strategy

### MVP first (US1 only)

1. Phase 1 and Phase 2, then Phase 3 (US1).
2. **Stop and validate**: T007–T010 are green. Clients can no longer get `valid: true` for a profile that was never checked.
3. US1 can be delivered and reviewed first on the branch. Any release containing it is a **MAJOR (2.0.0)** release, because it adds 400 responses to an existing operation (constitution Principle IV). It must not ship as a 1.x minor release.

### Incremental delivery

1. US1: correct handling of requested profiles (MVP).
2. US2: the IG-only source and the no-network guard, plus `ConfigPropagationIT` on the IG path.
3. US3: removal of `register-profile` and its machinery. This is the breaking API change.
4. US4: the V6 migration, the export script, the rehearsal and the upgrade guide. US3 and US4 must ship together in 2.0.0, because V6 without the export path would lose data.
5. Polish: docs, the constitution amendment, the performance comparison and the version bump.

## Notes

- [P] tasks touch different files and don't depend on incomplete tasks.
- Added during implementation: `src/test/java/nzi/fhir/validator/core/validator/ValidatorSnapshotProfileResolutionTest.java` checks the resolution rules (exact match, `|version`, duplicate canonicals, unknown `meta.profile`) on an in-memory snapshot, with no database, so they're verified even where Docker isn't available.
- Commit after each task or logical group, and keep commits per story so they can be reviewed.
- `checklists/api-and-upgrade.md` items stay reviewer-owned. `/speckit-implement` reads them but doesn't modify them.

## Phase 8: Convergence

- [X] T064 Pin the "new" side of `scripts/upgrade-rehearsal.sh` to a published 1.x image instead of building the current checkout: start it with `FV_TAG=${NEW_TAG:-1.2.0}` and `--no-build` (pull first) rather than `$DC up -d --build`, so the 0.1.2 → 1.x rehearsal no longer runs this branch. Today it builds 2.0, whose V6 migration drops `fhir_profiles`, so after the upgrade its `COUNT_SQL` query fails and `?profile=dk-core-patient` (registered on 0.1.2, not in the `example.rehearsal` IG) answers 400 `unknown-profile`. That breaks the first step of the CI `upgrade-rehearsal` job before the 2.0 export test and rehearsal can run. Update the header comment and the "1.0.0" log lines to say 1.x per SC-007, plan: CI rehearsal job (T054) (contradicts)
