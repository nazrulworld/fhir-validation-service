# Implementation Plan: IG-Only Profile Resolution (Remove Custom Profile Registration)

**Branch**: `003-ig-only-profile-resolution` | **Date**: 2026-11-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/003-ig-only-profile-resolution/spec.md`

## Summary

Profiles named on `POST /{version}/validate?profile=` are resolved only from the FHIR version's active
validator snapshot: the core definitions plus the included IG packages and their dependencies. They are
matched exactly by canonical URL (`url` or `url|version`) and are never fetched from the network.

A resolution pre-check in `ValidatorSnapshot.validate` runs on the validation worker, after the parse and
before HAPI validates. If any requested profile is unresolved, the request is rejected with
**400 `unknown-profile`** and an `unresolvedProfiles` list, and nothing is validated. The error is
always problem JSON. Requests are limited to 10 profiles of at most 1024 characters each, and a request
over either limit gets 400 `invalid-request`. This fixes the
silent drop (report finding #1). `appliedProfiles` lists only the profiles that resolved (finding #9).
An unknown `meta.profile` stays an ERROR issue.

The custom profile mechanism is removed end to end: `register-profile`, `ProfileService`, `ProfileStore`,
`CustomProfileValidationSupport`, the profile refresh and reassembly path, `KIND_PROFILE`, and
`fv.profile.count`. Migration V6 drops `fhir_profiles` and the unused `included_profiles` column
unconditionally: there is no safety gate, and that risk is accepted. `scripts/export-custom-profiles.sh`
turns 1.x profiles into uploadable IG packages **before** the upgrade. The upgrade procedure is: export,
take a mandatory backup, stop every 1.x instance, start 2.0. Rollback means restoring the backup, and
there is no down-migration. The release is 2.0.0.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Vert.x 4.5.x (web, web-openapi, pg-client), HAPI FHIR 8.12.1
(`FhirInstanceValidator`, `ValidationSupportChain`, `NpmPackageValidationSupport`), Flyway, Log4j2,
Micrometer/Prometheus. No new dependencies.

**Storage**: PostgreSQL. One Flyway migration, `V6__remove_custom_profiles.sql`, which drops a table and a
column.

**Testing**: JUnit 5 with `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`, `AppHarness`,
`CoreHarness`), contract assertions against `openapi.yaml`, and the shell rehearsal
`scripts/upgrade-rehearsal-2.0.sh` in CI.

**Target Platform**: Linux container (x86_64 and arm64); the fat jar runs on JDK 17+.

**Project Type**: A single-module web service (REST), with shell scripts for operations.

**Performance Goals**: The existing p95 target (under 200 ms for a single resource against a warm
validator) is unchanged. The pre-check adds at most one cached in-memory map lookup per requested
profile, which is negligible. There is no new I/O on the request path.

**Constraints**:
- No network or database access during validation (FR-002, Principle I).
- The snapshot is immutable after it is built.
- The IG propagation window from 001 must not regress (FR-011, SC-005).
- The supported versions stay STU3, R4, R4B and R5.

**Scale/Scope**: About 9 production classes are deleted or slimmed, and about 6 test classes are deleted
or rewritten. There is 1 migration, 2 new scripts, 1 upgrade guide, and changes to the OpenAPI document,
the README and the CHANGELOG. Memory goes down slightly, because the custom profiles held in memory and
the re-assembled snapshots are gone.

## Constitution Check

*GATE: must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Principle | Assessment |
|---|---|
| I. Non-blocking event loop | **Pass.** The pre-check runs inside the existing `executors.validation().executeBlocking` call in `FhirValidationService`. Nothing new runs on the event loop. The export script is outside the JVM. |
| II. Stateless, horizontally scalable | **Pass, with a wording amendment.** There is less shared state: IG configuration rows are the only shared validator state, and cache consistency stays `LISTEN/NOTIFY` plus the poll. The principle's text names "profiles" and "the profile stores", so it gets a **PATCH amendment to 1.0.2** (research R9). A mixed 1.x/2.0 fleet after V6 is unsupported, and the upgrade guide says so. |
| III. FHIR conformance | **Pass, and improved.** Requested profiles are applied or the request is rejected, with no silent drop. Issues aren't suppressed. Q2 keeps the stricter ERROR for an unknown `meta.profile`, which differs from the CLI's WARNING. That is an explicit, documented choice, not silent filtering. |
| IV. Contract first | **Pass.** `openapi.yaml` changes land with the code (contracts/http-api.md). Removing an operation and adding a 400 case is breaking, so the **MAJOR bump to 2.0.0** and the changelog migration note are planned. |
| V. Test first with real dependencies | **Pass.** The regression tests (FR-018, quickstart table) are written before the code changes. The migration is tested on real PostgreSQL. The no-network test uses a real local HTTP server. |
| VI. Performance and capacity | **Pass.** The requested-profile list is explicitly bounded (10 entries, 1024 characters each; FR-006a). The latency and memory impact is stated above. The pre-check is on the hot path but costs O(number of profiles) map lookups. A benchmark comparison against 1.2.0 for the warm dk Patient case (using the existing `scripts/smoke.sh` timing, or the 001 load test) goes in the PR, as the principle requires. |
| VII. Observability | **Pass.** There is a new outcome label, `unknown_profile`, and `fv.profile.count` is removed. The unresolved URLs are client-supplied and appear in the response. Logs record them at DEBUG with the request id. Health readiness no longer has a "profile changes could not be applied" reason. |
| VIII. Simplicity and dependencies | **Pass.** There are no new dependencies, and the code is net negative. The schema changes only through the new V6 migration, and V1–V5 are not edited. |
| Security and compliance | **Pass.** The admin surface shrinks. The export script takes the password only from the environment or `.pgpass`. No resource bodies are logged. |

**Gate result**: PASS, with no unjustified violations. Complexity Tracking is empty.

**Re-check after Phase 1**: PASS. The design adds no components. The export script is an operational tool
outside the service, and it is justified because the data has to be exported before V6 removes it.

## Project Structure

### Documentation (this feature)

```text
specs/003-ig-only-profile-resolution/
├── plan.md               # this file
├── spec.md
├── research.md           # Phase 0: R1–R9
├── data-model.md         # Phase 1
├── quickstart.md         # Phase 1: validation guide
├── contracts/
│   ├── http-api.md       # OpenAPI delta, the new 400 case, metrics
│   └── export-script.md  # scripts/export-custom-profiles.sh contract
├── checklists/requirements.md
└── tasks.md              # Phase 2 (/speckit-tasks)
```

### Source code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── ServiceRegistry.java                       # EDIT: drop profile stores, services and API wiring
├── web/
│   ├── HttpVerticle.java                      # EDIT: drop profileApi route registration
│   ├── AdminAdmissionHandler.java             # EDIT: Javadoc only
│   └── endpoint/ProfileApi.java               # DELETE
├── core/
│   ├── security/ApiKeyAuthHandler.java        # EDIT: admin regex without register-profile
│   ├── metrics/ServiceMetrics.java            # EDIT: drop fv.profile.count, add OUTCOME_UNKNOWN_PROFILE
│   ├── model/ValidationRequestOptions.java    # EDIT: normalise, de-duplicate in order, enforce 10 / 1024 limits
│   ├── service/
│   │   ├── ProfileService.java                # DELETE
│   │   ├── CustomProfileValidationSupport.java# DELETE
│   │   ├── ConfigRevisionRepository.java      # EDIT: drop KIND_PROFILE and bump()
│   │   └── FhirValidationService.java         # EDIT: map unknown-profile to its metric outcome
│   └── validator/
│       ├── ProfileStore.java                  # DELETE
│       ├── ValidatorSnapshot.java             # EDIT: resolution pre-check, truthful appliedProfiles,
│       │                                      #       drop profileStore and invalidateCaches
│       ├── SnapshotBuilder.java               # EDIT: drop CustomProfileValidationSupport,
│       │                                      #       completeProfiles and reassemble
│       ├── ValidatorRegistry.java             # EDIT: drop profileStores, refreshProfiles and activeSupports
│       └── ConfigSync.java                    # EDIT: runSync = read revision, then apply; drop profile hooks
src/main/resources/
├── openapi.yaml                               # EDIT: per contracts/http-api.md
└── db/migration/V6__remove_custom_profiles.sql  # NEW

src/test/java/nzi/fhir/validator/
├── web/endpoint/ProfileApiContractTest.java   # DELETE
├── core/service/ProfileServiceTest.java       # DELETE
├── core/service/ProfileFirstUseIT.java        # DELETE
├── core/validator/ProfileStoreTest.java       # DELETE
├── core/validator/ProfileResolutionIT.java    # NEW: US1/US2 scenarios, no-network test (SC-002)
├── web/endpoint/ValidationApiContractTest.java# EDIT: unknown-profile 400 shape, register-profile 404
├── core/validator/ConfigPropagationIT.java    # EDIT: propagate via include-ig only
├── core/service/ConcurrencyIT.java            # EDIT: drop profile races
├── core/validator/SnapshotRaceTest.java       # EDIT: drop profile-store paths
├── web/ApiKeyAuthIT.java                      # EDIT: admin example becomes include-ig
├── core/service/MigrationUpgradeIT.java       # EDIT: V6 assertions
└── testsupport/TestPackages.java              # EDIT: helper to wrap requiredPatientElements in a package

scripts/
├── export-custom-profiles.sh                  # NEW: contracts/export-script.md
├── upgrade-rehearsal-2.0.sh                   # NEW: export -> backup -> stop 1.x -> 2.0 -> include -> rollback check
├── rolling-restart.sh                         # EDIT: refuse to cross a major version (FR-015c)
└── upgrade-rehearsal.sh                       # EDIT: header note only

docs/upgrade-2.0.md                            # NEW: export -> backup -> stop all 1.x -> start 2.0 -> include; rollback = restore
README.md, CHANGELOG.md, .env.example          # EDIT: remove register-profile, describe include-only
.specify/memory/constitution.md                # EDIT: 1.0.2 wording amendment (Principle II)
pom.xml                                        # EDIT: 2.0.0
```

**Structure decision**: the existing single-module Maven layout. No new modules or packages. The new test
class sits beside the validator tests it exercises.

## Implementation phases (input for /speckit-tasks)

1. **Tests first (US1, US2)**: add `ProfileResolutionIT` and the contract-test cases. They must fail on
   1.2.0 behaviour: the silent drop, and an unresolved URL in `appliedProfiles`.
2. **Resolution pre-check (US1, US2)**: `ValidationRequestOptions` normalisation and limits, the
   `ValidatorSnapshot` pre-check and exact-match `appliedProfiles`, the `ProblemException` type, the
   metric outcome, and the `openapi.yaml` validate changes. This is the MVP: it ships value even before
   the removal.
3. **Removal (US3)**: delete the profile classes and wiring, then simplify `ConfigSync`,
   `ValidatorRegistry`, `SnapshotBuilder` and `ConfigRevisionRepository`. Remove the operation from
   `openapi.yaml`, and rewrite or delete the affected tests. `ConfigPropagationIT` must stay green
   (FR-011).
4. **Data and upgrade (US4)**: the V6 migration and `MigrationUpgradeIT`, the export script, the 2.0
   rehearsal (including the backup, the full stop and the rollback restore) and its CI wiring, the
   major-version guard in `rolling-restart.sh`, and `docs/upgrade-2.0.md`.
5. **Release hygiene**: the README, CHANGELOG (2.0.0), `.env.example`, constitution 1.0.2, `pom.xml`
   2.0.0, and the performance comparison for the PR.

## Complexity Tracking

No violations, so nothing to justify.
