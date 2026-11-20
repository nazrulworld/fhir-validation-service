---

description: "Task list for feature 012: multi-IG validator contexts"
---

# Tasks: Multi-IG Validator Contexts (Up to Two IGs per Context, with Aliases)

**Input**: Design documents from `/specs/012-multi-ig-validator-contexts/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-api.md, quickstart.md

**Tests**: Required. Constitution Principle V: new behaviour starts with a failing test (JUnit 5 + vertx-junit5),
persistence and IG loading run against Testcontainers PostgreSQL (`BaseTestContainer`), every endpoint has a contract
test. Write each test task first and see it fail before the implementation tasks of the same phase.

**Organization**: Tasks are grouped by user story (spec.md US1–US4) so each can be implemented and tested on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: The user story (US1–US4)

## Path Conventions

Single Maven project. `main/` = `src/main/java/nzi/fhir/validator/`, `test/` = `src/test/java/nzi/fhir/validator/`,
`resources/` = `src/main/resources/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Contract first (Principle IV), migration, test fixtures.

- [X] T001 Update `resources/openapi.yaml` per contracts/http-api.md §1–§4: on `POST /{version}/validator` add the optional `igPackages` array (`minItems: 1`, `maxItems: 2`, items `{igPackageId (required), igPackageVersion (default latest)}`), remove `required: [igPackageId]` and describe the rule "exactly one of `igPackageId` or a non-empty `igPackages`"; add `igPackages` and `aliases` to `IncludeResponse`; add the 409 response and the `dependency-conflict` / `alias-conflict` descriptions; extend the `ig` parameter descriptions of validate, PATCH and DELETE with the pair form `name#version|name#version` (URL-encode `|` as `%7C`), the lookup order and FR-012a; add `igPackages` and `aliases` to the `/health` context entries. Keep `ig` `maxLength: 256`
- [X] T002 [P] Create `resources/db/migration/V17__multi_ig_contexts.sql` (data-model §3): `ALTER TABLE fhir_validator_schema.fhir_validator_snapshots ALTER COLUMN ig_package TYPE VARCHAR(256)`; `ALTER TABLE fhir_validator_schema.validation_usage ALTER COLUMN ig TYPE VARCHAR(256)`; unique index `fhir_validator_snapshots_first_member_uq` on `(fhir_version, split_part(ig_package,'|',1)) WHERE strpos(ig_package,'|') > 0`; unique index `fhir_validator_snapshots_pair_uq` on `(fhir_version, LEAST(split_part(ig_package,'|',1), split_part(ig_package,'|',2)), GREATEST(split_part(ig_package,'|',1), split_part(ig_package,'|',2))) WHERE strpos(ig_package,'|') > 0`. Comment each statement. Never edit V15/V16
- [X] T003 [P] Add fixture builders to `test/testsupport/TestPackages.java` (reuse `igPackage(...)`/`profilePackage(...)`): `multiA()` (`test.multi.a#1.0.0`, R4, profile `http://example.org/fhir/StructureDefinition/multi-a-patient`), `multiB()` (`test.multi.b#2.0.0`, R4, Patient profile `.../multi-b-patient` requiring gender), `multiC()` (`test.multi.c#3.0.0`, R4), `conflictX1()`/`conflictX2()` (`test.multi.x#1.0.0` and `#2.0.0`) with `multiNeedsX1()` (`test.multi.p#1.0.0` depends on `test.multi.x#1.0.0`) and `multiNeedsX2()` (`test.multi.q#1.0.0` depends on `test.multi.x#2.0.0`), and `terminologyV1()`/`terminologyV2()` (`hl7.terminology.r4#5.0.0` and `#6.0.2` stand-ins) with `multiNeedsTx1()`/`multiNeedsTx2()` depending on each. Add `includePair(services, fhirVersion, a, b)` helper calling the new include API

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Key model, alias index, resolution with several roots, access with several members. Every story needs these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, must fail)

- [X] T004 [P] Unit test `test/core/validator/SnapshotKeyTest.java`: `members()` is `[]` for base, `[A#a]` for single, `[A#a, B#b]` (key order) for `A#a|B#b`; `isCombined()`; constructor rejects `A#a|`, `|B#b`, `A#a|B#b|C#c`, `A#a|A#b` (same name), a member without `#version`, and a key over 256 characters; `contextLabel()` returns the key unchanged
- [X] T005 [P] Unit test `test/core/validator/ContextAliasesTest.java`: from included keys `{A#1|B#2, C#3}` the index maps `B#2|A#1 → A#1|B#2` and `A#1 → A#1|B#2`, nothing for `C#3` or `B#2`; `firstMemberTaken("A#1")` and `pairTaken("B#2","A#1")` are true; an empty set yields an empty index
- [X] T006 [P] Unit test `test/core/security/AccessPolicyTest.java` (extend): for a combined key, `canUseContext` returns `IG_NOT_VISIBLE` when either member is not usable (same decision whichever member it is), `DEPENDENCY_NOT_VISIBLE` for a dependency, `ALLOWED` otherwise; `isPublic` is false unless both members, every dependency and the context are shareable
- [X] T007 [P] Unit test new `test/core/validator/ResolvedContextTest.java`: `ResolvedContext.of("A#1|B#2", view, owner)` resolves both roots with `DependencyResolver.resolve(members, …)`; a not-stored member stays in `packages` so the build fails naming it

### Implementation

- [X] T008 Extend `main/core/validator/SnapshotKey.java`: accept one or two `|`-separated members, each a concrete `name#version` (no `latest`, no series), different package names, total ≤ 256 characters ("At most 256 characters", data-model §1); add `members()`, `isCombined()`, static `combined(fhirVersion, a, b)`. Keep `igPackage` as the stored key string and `contextLabel()` unchanged
- [X] T009 [P] Create `main/core/validator/ContextAliases.java` (data-model §2): immutable index per FHIR version built from an included key collection; `target(String alias)` → `Optional<String>` key, `firstMemberTaken(String a)`, `pairTaken(String a, String b)` (either order), `aliasesOf(String key)` → `[B#b|A#a, A#a]` for a combined key, `[]` otherwise. Never stored (research R3)
- [X] T010 Update `main/core/validator/ResolvedContext.java`: `of(...)` and `forKey(...)` use `SnapshotKey`-style member parsing of the key and pass all members as roots to `DependencyResolver.resolve`; keep each not-stored member in `packages`; `missing` excludes members
- [X] T011 Update `main/core/security/AccessPolicy.java` `canUseContext` and `isPublic`: every member of `key.members()` must be usable (`IG_NOT_VISIBLE` otherwise); a dependency check skips all members
- [X] T012 Derive a `ContextAliases` per FHIR version from the included key set each time `ConfigSync` applies a revision, published together with `PinnedAccess` (implemented as `PinnedAccess.aliases()`, computed when `ConfigSync` swaps the set, so validation always sees aliases and included contexts from the same revision) (same supplier pattern: `Function<SupportedFhirVersion, ContextAliases>` wired in `main/ServiceRegistry.java`)
- [X] T013 [P] Add `ProblemException.dependencyConflict(List<Conflict>)` (400, type `dependency-conflict`, extension `conflicts: [{package, versions: {"A#a": "x", "B#b": "y"}}]`) and `ProblemException.aliasConflict(String detail)` (409, type `alias-conflict`) with `TYPE_DEPENDENCY_CONFLICT` / `TYPE_ALIAS_CONFLICT` constants in `main/web/ProblemException.java`

- [X] T013a [P] Raise the `ig` cut in `main/core/usage/UsageRecord.java` from 192 to 256 characters ("`validation_usage.ig` `VARCHAR(192)` → `VARCHAR(256)`", data-model §3) and add a unit case to `test/core/usage/UsageRecordTest.java` (create if missing): a 256-character combined key is kept whole, 257 is cut to 256

**Checkpoint**: `SnapshotKeyTest`, `ContextAliasesTest`, `AccessPolicyTest`, `ResolvedContextTest`, `UsageRecordTest` pass; full `mvn verify` still green (no single-IG behaviour changed).

---

## Phase 3: User Story 1 - Include a validator context built from two IGs (Priority: P1) 🎯 MVP

**Goal**: `POST /{version}/validator` with `igPackages` of two entries includes one pinned context keyed `A#a|B#b`.

**Independent Test**: Store two IGs, include them together for R4; the response lists key, members and aliases; `/health` lists the context with both members on every instance.

### Tests (write first, must fail)

- [X] T014 [P] [US1] Unit test `test/core/npm/DependencyConflictTest.java` (research R4): `DependencyResolver.conflicts(p#1.0.0, q#1.0.0, …)` reports `test.multi.x` with versions `{p#1.0.0: 1.0.0, q#1.0.0: 2.0.0}` and `exempt=false`; two roots needing `hl7.terminology.r4` 5.0.0 / 6.0.2 report `exempt=true`; a name starting `hl7.fhir.uv.extensions` is exempt; a root of one tree found in the other in another version is a conflict; identical versions are no conflict; result order is deterministic (sorted by package name); one IG is a dependency of the other at the same version (`B` depends on `A#1.0.0`, roots `A#1.0.0` and `B`) → no conflict
- [X] T015 [P] [US1] Integration test `test/core/validator/MultiIgIncludeIT.java` (Testcontainers, `AppHarness`): spec US1 scenarios 1–6 and edge cases: include `multiA`+`multiB` → 200, `key.igPackage = "test.multi.a#1.0.0|test.multi.b#2.0.0"`, `igPackages` in key order, `aliases = ["test.multi.b#2.0.0|test.multi.a#1.0.0", "test.multi.a#1.0.0"]`, `resolved` contains both; `latest` and a series in an entry resolve to the concrete version in the key; three entries, zero entries, same `igPackageId` twice, both body forms, a combined key over 256 characters → 400 `invalid-request` and nothing stored; one entry behaves exactly like `igPackageId` (same response JSON); reversed pair → 200 reporting the existing key and one row in `fhir_validator_snapshots`; `multiNeedsX1`+`multiNeedsX2` → 400 `dependency-conflict` with `conflicts[0].package = "test.multi.x"`; `multiNeedsTx1`+`multiNeedsTx2` → 200 with a warning naming `hl7.terminology.r4`, `5.0.0`, `6.0.2` and "using 6.0.2", and only `6.0.2` in `resolved`; a member for another FHIR version → 400; a member the caller can't use → 404 with a detail that names the request only; a dependency the caller can't use → 424; a conflict involving a version the caller can't use → 424 without naming it; heap admission refusal → 413; `shareable:false` refused unless the caller owns both packages or is system; a pair where B already depends on A at the same version is accepted with its own key `A#a|B#b`, separate from any single-IG context of B
- [X] T016 [P] [US1] Contract cases in new `test/web/endpoint/ValidatorApiContractTest.java` (same style as `IgPackageApiContractTest.java`): request with `igPackages` validates against `openapi.yaml`; 200 body has `igPackages` and `aliases`; 400 `dependency-conflict` and 409 `alias-conflict` are problem JSON with the documented types
- [X] T016a [P] [US1] Extend `test/core/validator/PackageInvalidationIT.java`: replace a stored dependency of a combined context so the rebuilt trees need different versions of a non-exempt package; the context is rebuilt with the same key, the highest version wins with a logged warning (research R4: conflicts are refused at include time only), and validation keeps being served

### Implementation

- [X] T017 [US1] Add `DependencyResolver.conflicts(String rootA, String rootB, Catalog catalog, Predicate<String> selectable)` returning `List<Conflict(package, Map<String,String> versionsByRoot, boolean exempt)>` in `main/core/npm/DependencyResolver.java`: resolve each root alone with today's rules, compare package names present in both; exempt prefixes `hl7.terminology` and `hl7.fhir.uv.extensions` as a constant; sorted by package name
- [X] T018 [US1] Parse `igPackages` in `main/web/endpoint/ValidatorApi.java` `handleCreateValidator`: refuse with 400 `invalid-request` when both `igPackageId` and `igPackages` are present, when `igPackages` is not an array of 1–2 objects each with a string `igPackageId`, or when the same `igPackageId` appears twice; map a one-entry list to today's single path; pass a two-entry list to the new service method; for a two-entry include, echo each entry's `requestedVersion` inside its `igPackages` entry and do **not** set the top-level `requestedVersion` (contracts §1); the single-IG forms keep the top-level `requestedVersion` as today
- [X] T019 [US1] Add `IgPackageService.includePair(Caller, SupportedFhirVersion, Entry first, Entry second, Optional<Boolean> shareable, Optional<TerminologySettings>)` in `main/core/service/IgPackageService.java`, reusing the single include's steps per member inside one `pgPool.withTransaction`: served-version check, version/series/latest resolution among versions the caller may use (404 otherwise), `checkFhirVersion` per member, combined key ≤ 256 characters (400), `ConfigRevisionRepository.lockForUpdate`; then under the lock: reversed or same pair already included → return existing context like today's "already included" (with `ALREADY_INCLUDED_WARNING`/`TERMINOLOGY_IGNORED_WARNING` rules); `ContextAliases` built from the locked `row.included()` (never the published in-memory index, which can lag a revision) → `firstMemberTaken(first)` → 409 `alias-conflict` (detail names the other key only when `AccessPolicy.canUse(caller, …)` for it, otherwise "another context already uses this first IG"); `DependencyResolver.conflicts(...)` → 424 when any conflicting version is not usable by the caller, else 400 `dependency-conflict` for non-exempt, warnings for exempt (format in contracts §1); dependency visibility (424); private-context rule (caller owns both packages or is system); terminology allowlist; heap admission over `resolve({A,B})`; `ConfigRevisionRepository.updateIgSet(..., key, KIND_IG, ...)`; `LocalApplyWait` and logging as in `include`. Result JSON adds `igPackages` and `aliases`
- [X] T020 [US1] Add `igPackages` (members) to the single-IG include result too (`[{"igPackageId","igPackageVersion"}]`, `aliases: []`) in `IgPackageService.includeResult` in `main/core/service/IgPackageService.java`, so both forms share one response shape (contracts §1)
- [X] T021 [US1] Add `igPackages` and `aliases` to each context entry of the `/health` listing and the inactive-context listing in `main/core/validator/ValidatorRegistry.java` (`contexts`) and `main/core/service/HealthService.java` (`inactiveContexts`); `ContextActivation.java` only logs keys and needs no change. The public-listing rule uses the updated `AccessPolicy.isPublic`
- [X] T022 [US1] Map a `23505` unique violation on `fhir_validator_snapshots_first_member_uq` / `_pair_uq` to 409 `alias-conflict` (first member) or the "already included" answer (pair) in `main/core/service/ConfigRevisionRepository.java` `updateIgSet` (defence in depth, research R3), using `main/core/PgErrors.java`

**Checkpoint**: US1 tests pass; a combined context is pinned and listed; single-IG include unchanged.

---

## Phase 4: User Story 2 - Validate against two IGs (Priority: P1)

**Goal**: `ig=A#a|B#b` (either order) validates against the combined context; exact key first, then alias.

**Independent Test**: With `A#1.0.0|B#2.0.0` included, validate a resource claiming a profile of A and one of B; both apply; `X-Validation-Context` reports the key in both orders.

### Tests (write first, must fail)

- [X] T023 [P] [US2] Extend `test/core/validator/IgReferenceTest.java`: `parse("A#1|B#2")` → `Pair`; rejected with 400 `invalid-request`: `A|B#2`, `A#latest|B#2`, `A#1.x|B#2`, `A#1|A#2`, `A#1|A#1`, `A#1|B#2|C#3`, `A#1|`, `|B#2`, over 256 characters; resolution over included `{A#1|B#2}`: `Pair(A#1,B#2)` → key, `Pair(B#2,A#1)` → same key, `Pair(A#1,C#3)` → 400 "not included"; a member for another FHIR version → 400 before "not included"; a member the caller can't use → empty (404) whichever member
- [X] T024 [P] [US2] Integration test `test/web/MultiIgValidationIT.java`: spec US2 scenarios 1–4 over HTTP with `?ig=test.multi.a%231.0.0%7Ctest.multi.b%232.0.0` and the unencoded `|` form: a Patient claiming `multi-a-patient` and an Observation claiming `multi-b-observation` validate with no `unknown-profile` (SC-001); the `profile` parameter accepts URLs from both IGs (FR-016); `X-Validation-Context`, `-Owner`, `-Shareable` report the combined context in both orders (FR-013, SC-002); a pair not included → 400 "not included"; no context is built (registry snapshot count unchanged, SC-003); per-request `tx`/`txMode` work as for a single context (FR-015); usage row `ig` stores the key (up to 256 characters); access (FR-014): a caller who may not use member A, or member B, gets 404 with the same detail naming only the requested `ig` value, a caller who may not use a dependency gets 424 without naming it, and a private combined context is 404 for another organisation
- [X] T025 [P] [US2] Contract cases for `validationApiValidate` with the pair form in `test/web/endpoint/ValidationApiContractTest.java`

### Implementation

- [X] T026 [US2] Add the sealed interface `main/core/validator/IgRequest.java` permitting `IgReference` (today's single-IG record, kept so existing callers compile; behaviour unchanged) and the new `main/core/validator/IgPair.java` (two `IgReference`s) — implemented this way instead of turning `IgReference` itself into the interface; `parse` splits on `|`, requires exact versions on both sides, different names, total ≤ 256 characters; `invalid()` text mentions the pair form
- [X] T027 [US2] Implement `Pair.resolve(fhirVersion, catalog, caller, included, aliases)` in `main/core/validator/IgReference.java`: per member (in order) the same visibility and FHIR-version checks as `Single.resolveIn` (empty → 404 with the request label; other FHIR version → 400); then key `a|b` if included, else `aliases.target("a|b")`, else 400 `ProblemException.igNotIncluded` with the pair label "IG packages A#a|B#b are not included for R4; include them first"; one rate-limited catalog refresh on a miss as today
- [X] T028 [US2] Update `main/core/service/FhirValidationService.java` `resolve(...)` to pass the published `ContextAliases` for the version; `checkAccess` uses the requested label in 404s and the stored key for the pinned access lookup
- [X] T029 [US2] Update `main/web/endpoint/ValidationApi.java` `igParameter` to return the sealed `IgReference` (one `ig` value still required; the pair is one value)
- [X] T030 [US2] Check `main/core/validator/ValidatorSnapshot.java` unknown-profile message and `main/core/validator/OutcomeRenderer.java` with a combined key (text "defined by A#a|B#b, its dependencies or the core specification"); adjust wording only if it reads wrong

**Checkpoint**: US1 + US2 deliver the MVP: include and validate against two IGs.

---

## Phase 5: User Story 3 - Find a combined context by its first IG alone (Priority: P2)

**Goal**: `ig=A#a` (exact) reaches the combined context when no single-IG `A#a` is included; versionless `ig=A` never does.

**Independent Test**: Include only `A#1.0.0|B#2.0.0`; validate with `ig=A#1.0.0`; the combined key is reported.

### Tests (write first, must fail)

- [X] T031 [P] [US3] Extend `test/core/validator/IgReferenceTest.java`: `Single(A,1)` over included `{A#1|B#2}` → combined key; over `{A#1, A#1|B#2}` → `A#1`; **regression for research R5**: `Single(A, null)` over `{A#1, A#2|B#1}` → `A#1` (the combined key is never chosen by `highestIncluded`), and over `{A#2|B#1}` alone → today's answer (400 "not included" for the highest stored version, or 404)
- [X] T032 [P] [US3] Integration test `test/core/validator/MultiIgAliasIT.java`: spec US3 scenarios 1–6: alias serves `ig=test.multi.a%231.0.0`; including single `test.multi.a#1.0.0` takes over, excluding it hands back to the combined context, the alias itself unchanged; including `multiA`+`multiC` while `multiA`+`multiB` is included → 409 `alias-conflict` and nothing stored; `multiB`+`multiC` is accepted; `ig=test.multi.a` and `ig=test.multi.a%23latest` use the single-IG context only (FR-012a)

### Implementation

- [X] T033 [US3] In `main/core/validator/IgReference.java` `Single.resolveIn`: with an exact version, after the existing checks, use the included single key if present, else `aliases.target(key)` (a first-IG alias), else today's "not included" 400; in `highestIncluded` skip keys containing `|` (FR-012a)
- [X] T034 [US3] Confirm `IgPackageService.include` (single) doesn't treat an `A#a` first-IG alias as "already included" in `main/core/service/IgPackageService.java` (`row.included().contains(key)` compares whole keys; add a comment referencing FR-012)

**Checkpoint**: US3 passes; single-IG results unchanged when no combined context exists (SC-004).

---

## Phase 6: User Story 4 - Manage a combined context (Priority: P2)

**Goal**: PATCH and DELETE (and the admin CLI) address a combined context by key or reversed pair.

**Independent Test**: PATCH and DELETE `ig=B#2.0.0|A#1.0.0`; the change applies to `A#1.0.0|B#2.0.0`.

### Tests (write first, must fail)

- [X] T035 [P] [US4] Integration test `test/web/MultiIgManageIT.java`: spec US4 scenarios 1–2: PATCH `shareable`, `owner` (system caller) and `terminology` via the key and via the reversed pair apply to the stored key with today's check order (404 → 403 → 400), no rebuild for terminology; PATCH `shareable:false` refused unless the caller owns both member packages or is system; DELETE via reversed pair removes the context and its aliases (subsequent `ig=` pair → 400, `ig=A#a` → today's single-IG answer); PATCH/DELETE `ig=A#a` with only a combined context → 404; DELETE of a single-IG `A#a` leaves the combined context (FR-018); works for an inactive context (FHIR version disabled)
- [X] T036 [P] [US4] Extend `test/cli/AdminCliIT.java`: the access command with `--ig 'A#1|B#2'` and the reversed pair changes the combined context
- [X] T036a [P] [US4] Contract cases: PATCH and DELETE `/{version}/validator` with `ig` as the key and as the reversed pair validate against `openapi.yaml` (request and 200 body, 404 problem JSON for a single `A#a` naming only a combined context) in `test/web/endpoint/ValidatorApiContractTest.java`; `/health` `contexts` and `inactiveContexts` entries with `igPackages` and `aliases` validate against the schema in `test/web/endpoint/HealthApiTest.java`

### Implementation

- [X] T037 [US4] Add a shared `canonicalKey(ConfigRow row, IgReference ref)` in `main/core/service/IgPackageService.java`: `Pair` → stored key if `a|b` or `b|a` is included, else not found; `Single` with exact version → that key only if it is an included single key; versionless → 400 as today. Use it in `exclude` and `changeSnapshotAccess`; responses report the stored key (`excluded`, `igPackage`)
- [X] T038 [US4] Private-context rule for a combined key in `changeSnapshotAccess` in `main/core/service/IgPackageService.java`: `shareable:false` requires the caller to own both member packages (or be system)
- [X] T039 [US4] Update `main/cli/AccessCommands.java` to parse `--ig` with `IgReference.parse` (pair form allowed) and pass the value through

**Checkpoint**: All four stories pass independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T040 [P] `test/core/validator/MultiIgPropagationIT.java`: two `AppHarness` instances on one database; include a pair on instance 1, validate with the reversed pair on instance 2 within the propagation window (SC-005); exclude on instance 2, instance 1 stops serving it; a missed notification is caught by the poll
- [X] T041 [P] Add a V17 case to `test/core/service/MigrationUpgradeIT.java`: on a populated V16 (1.1.0) database V17 applies; existing single-key rows untouched; inserting two combined rows with the same first member or the same pair in reverse order fails with `23505`
- [X] T042 [P] Verify only: `test/core/ConstantMigrationConsistencyTest.java` reads `V15__baseline_1_0.sql` and checks neither column widths nor the latest migration number, so V17 needs no change there; confirm it still passes
- [X] T043 Run the golden-outcome comparison (`test/testsupport/GoldenOutcomes.java`, spec 010 corpus) to confirm single-IG validation results are unchanged (SC-004)
- [ ] T044 [P] SC-006 check: validate the US2 fixture resources with `validator_cli.jar -version 4.0.1 -ig <A.tgz> -ig <B.tgz>` and compare issue projections with the service's combined context; record the command and result in `perf/results-1.2.0.md`
- [X] T045 [P] k6 comparison of single-IG validation against 1.1.0 with `perf/compare-run.sh` (hot path changed in `IgReference`); record p95 and the build time and heap of one combined context in `perf/results-1.2.0.md`
- [X] T046 [P] `CHANGELOG.md` `[Unreleased]` → 1.2.0 entry: Added (multi-IG contexts, aliases, `igPackages`, pair `ig`, new problem types), Changed (column widths, V17); bump `pom.xml` `<version>` to `1.2.0`
- [X] T047 [P] README section "Validating against two IGs" (include body, `ig` pair form with `%7C`, alias rules, conflicts) in `README.md`
- [X] T048 [P] Add `dependency-conflict` (400) and `alias-conflict` (409) rows, in the `<a id="<type>"></a>` form, to the API Documentation wiki problem-type table so `scripts/build-docs-site.py` publishes `/problems/<type>` (spec 011); run `scripts/tests/docs-site/test_problem_redirects.py`
- [X] T049 Run every scenario in `specs/012-multi-ig-validator-contexts/quickstart.md` and `mvn verify`; fix any blocked-thread warning — done as automated equivalents: every quickstart scenario is covered by `MultiIg*IT`/contract tests; final `mvn verify` 936 tests, 0 failures, no blocked-thread warnings (2026-11-20)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none. T001 first (contract first); T002, T003 in parallel.
- **Foundational (Phase 2)**: after Setup; blocks every story.
- **US1 (Phase 3)**: after Foundational.
- **US2 (Phase 4)**: after Foundational; its ITs need a combined context, so in practice after T019 (or seed rows directly).
- **US3 (Phase 5)**: after US2 (extends `IgReference` from T026–T027).
- **US4 (Phase 6)**: after US1 (needs included combined contexts); independent of US2/US3.
- **Polish (Phase 7)**: after the stories it covers.

### Within each phase

Tests first (must fail) → model/parsing → service → endpoint → listing/CLI.

### Parallel Opportunities

- Phase 1: T002 ∥ T003.
- Phase 2: T004–T007 together; T009 ∥ T013 ∥ T013a alongside T008.
- US1: T014 ∥ T015 ∥ T016 ∥ T016a; T020 ∥ T021 after T019.
- US2: T023 ∥ T024 ∥ T025.
- US3 and US4 can be worked on by two people once US2 (for US3) and US1 (for US4) are done.
- Polish: T040–T042, T044–T048 in parallel.

### Parallel example: User Story 1

```text
T014 DependencyConflictTest      (test/core/npm/)
T015 MultiIgIncludeIT            (test/core/validator/)
T016 include contract cases      (test/web/endpoint/)
```

## Implementation Strategy

### MVP (US1 + US2)

1. Phase 1 → Phase 2.
2. US1: combined contexts can be included and are listed.
3. US2: they can be used for validation in either order.
4. Stop and validate with quickstart scenarios 1–4, 7–8: this is the first useful slice (SC-001, SC-002, SC-003).

### Incremental delivery

5. US3: first-IG alias (quickstart 5–6).
6. US4: manage by key or reversed pair (quickstart 9).
7. Polish: propagation, migration upgrade, CLI parity, docs, release 1.2.0.
