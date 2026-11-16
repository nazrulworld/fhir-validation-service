---

description: "Task list for 010-shared-packages-request-tx (release 1.1.0)"
---

# Tasks: Shared Package Definitions and Per-Request Terminology Settings

**Input**: Design documents from `specs/010-shared-packages-request-tx/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R14), data-model.md, contracts/http-api.md, quickstart.md

**Tests**: Required. Constitution Principle V (test-first with real dependencies): each story starts with failing
tests. ITs use Testcontainers PostgreSQL through `AppHarness`/`CoreHarness`. Unit tests are for pure logic only.

**Organization**: Tasks are grouped by user story (spec.md US1–US4) so each story can be built and tested on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US4 from spec.md
- Paths are relative to the repository root. `main/` = `src/main/java/nzi/fhir/validator/`, `test/` =
  `src/test/java/nzi/fhir/validator/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Test fixtures and the 1.0.0 baseline that later phases compare against.

- [X] T001 Create `src/test/resources/ig/packages/010-packages.lock`. It lists `name#version`, download URL (packages.fhir.org) and sha256 for:
  - `hl7.fhir.dk.core#3.5.0` and `medcom.fhir.dk.core#4.0.0` (spec Clarifications: they share 10 packages; US Core 7.0.0 and DK Core share almost none);
  - the full dependency closure of both, read from each `package/package.json` (at least `hl7.terminology.r4` and `hl7.fhir.uv.extensions.r4`, and any others they declare).

  Create `scripts/fetch-test-packages.sh`. It downloads into `${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}`, checks every sha256 and fails on a mismatch. Add `Fixtures.lockedPackage(nameVersion)` in `test/testsupport/Fixtures.java`, which reads from that folder. A missing file fails the test with "run scripts/fetch-test-packages.sh", and the test is never skipped silently. Cache the folder in CI (`.github/workflows/`), keyed by the lockfile hash. Tests stay offline. Document this in `src/test/resources/ig/packages/README.md`
- [X] T002 [P] Create the differential corpus `src/test/resources/corpus/010/` with three subfolders: `examples/` (every example resource in the DK Core 3.5.0 and MedCom Core 4.0.0 packages, extracted by a script), `invalid/` (at least 50 invalid variants, spec SC-004: wrong codes, missing required elements, slicing errors, unknown extensions, profiles that don't match, value set and code system checks) and `duplicate-canonical/` (a small test package plus resource, where two packages of one context define the same canonical URL). Add `src/test/resources/corpus/010/README.md` listing which spec items each group covers
- [X] T003 Create `scripts/golden-010.sh`. It checks out tag `v1.0.0` into a temporary worktree, builds it, starts it against a Testcontainers-style local PostgreSQL, includes DK Core 3.5.0 and MedCom Core 4.0.0 on R4, validates every corpus file in both contexts with `Accept: application/fhir+json`, and writes `src/test/resources/corpus/010/golden/<context>/<file>.json`, keeping per issue only severity, code, expression/location and diagnostics/details text, in order (spec SC-004: id, meta and narrative ignored)
- [X] T004 Run `scripts/golden-010.sh` and commit the generated golden files under `src/test/resources/corpus/010/golden/`
- [X] T005 [P] Add the corpus comparison helper `test/testsupport/GoldenOutcomes.java`. It reduces an OperationOutcome to the same per-issue projection as T003 and asserts equality, issue by issue and in order, with a diff message that names the first difference

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Terminology scope, routing, the code cache and the scoped chain, which US1 and US2 both need, and the
snapshot identity that US1–US3 use.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, they must fail)

- [X] T006 [P] Unit test `test/core/terminology/EffectiveTerminologyTest.java`: every row of data-model §4 (8 rows × D ∈ {none, server A, ecosystem, ecosystem A}). It also checks that `source` is `REQUEST` whenever `tx` or `txMode` is given, and that `txMode=server` without a server anywhere throws 400 `invalid-request` with the detail "txMode=server needs a tx server; this context has none"
- [X] T007 [P] Unit test `test/core/terminology/TerminologyCodeCacheTest.java`. It covers:
  - key equality covers every key part in data-model §6;
  - different `snapshotId` or `scopeId` never hit;
  - `maximumSize` and `expireAfterWrite` are honoured (fake ticker);
  - `invalidateScope`, `invalidateSnapshot` and `invalidateEcosystem(fhirVersion)` remove only their entries;
  - a "not checked" result (`TerminologyClients.notChecked`) is never stored (spec FR-012);
  - a `lookupCode` miss that marks the request degraded stores nothing, and a later identical call reaches the module again
- [X] T008 [P] Unit test `test/core/validator/ScopedChainLoopTest.java`, research R3. For a fixed module list (stub code-system module, `InMemoryTerminologyServerValidationSupport`, `CommonCodeSystemsTerminologyService`), `ScopedValidationSupportChain`'s uncached loop must return the same result as a plain HAPI `ValidationSupportChain(CacheConfiguration.disabled(), modules)` for `validateCode` (with and without a value set URL, unknown code system), `validateCodeInValueSet` (with and without a URL) and `lookupCode`. The test's javadoc explains that it fails when a HAPI upgrade changes the copied 8.12.1 loop
- [X] T009 [P] Unit test `test/core/terminology/RequestScopedTerminologySupportTest.java`:
  - a `none` scope returns null/false for every terminology method and makes zero calls on a counting `TerminologyClients`, even after a `server` scope ran on the same instance;
  - a `server` scope routes to its server;
  - an `ecosystem` scope calls `RegistryResolver.choose` with the scope's default;
  - a `denied` scope answers the NOT_PERMITTED "not checked" warning with no call;
  - no open scope means an `IllegalStateException` (a call outside validation is a bug)

### Implementation

- [X] T010 [P] Create `main/core/terminology/TerminologyOverride.java` (record `mode`, `server`, both nullable; `ABSENT`), data-model §3: "`txMode`: `none`, `server` or `ecosystem`; at most once"; "`tx`: ≤ 512 chars; http(s); no query, fragment or user info; at most once"; invalid when `mode == NONE && server != null`
- [X] T011 [P] Create `main/core/terminology/EffectiveTerminology.java` (record `settings`, `source` enum `REQUEST|CONTEXT`, `denied`) with a static, pure `resolve(TerminologySettings d, TerminologyOverride o)` implementing the data-model §4 table exactly. It builds results with the existing `TerminologySettings` constructor. Add `scopeId()`: `none`, `denied`, `server:<url>`, `ecosystem:<url or ->`
- [X] T012 [P] Create `main/core/terminology/TerminologyRouter.java`, a sealed interface `Optional<String> route(String system)` with:
  - `NONE` and `DENIED`;
  - `server(url)`, which reuses the logic of `ServerTerminologySupport.route`;
  - `ecosystem(version, defaultServer, resolver, clients)`, which reuses `EcosystemRoutingTerminologySupport.route`
- [X] T013 Create `main/core/terminology/TerminologyScope.java` (data-model §5). It is a `ThreadLocal` holding `effective`, `router` and `scopeId`, with:
  - `open(EffectiveTerminology, TerminologyRouter)`, which throws `IllegalStateException` if one is already open (the same rule as `TerminologyBudget.open`);
  - `close()`, `current()` and `isOpen()`;
  - `TerminologyScope.none()` for warm-up and tests (depends on T011, T012)
- [X] T014 Create `main/core/terminology/RequestScopedTerminologySupport.java`, extending `RoutedTerminologySupport`. `route()` delegates to `TerminologyScope.current().router()`. For a `none` scope (research R2), `isCodeSystemSupported`, `isValueSetSupported`, `validateCode`, `validateCodeInValueSet` and `lookupCode` return false/null before any other work, so the result is exactly that of 1.0.0's chain without a module. `getName()` returns `"TerminologyRequestScoped"` (depends on T013)
- [X] T015 [P] Create `main/core/terminology/TerminologyCodeCache.java`, research R4 and data-model §6:
  - Caffeine with `maximumSize(FV_TX_CACHE_SIZE)` and `expireAfterWrite(FV_TX_CACHE_TTL_MS)`;
  - record key `(long snapshotId, String scopeId, Op op, SupportedFhirVersion fhirVersion, String system, String code, String display, String valueSetKey, String optionsKey)`;
  - an explicit EMPTY marker;
  - `get`/`put`, where `put` refuses results whose `getSourceDetails()` is the "fhir-validation-service terminology" not-checked marker;
  - `invalidateScope(String)`, `invalidateSnapshot(long)`, `invalidateEcosystem(SupportedFhirVersion)` and `estimatedSize()`;
  - a `Listener` for hit, miss and invalidation (reason `degraded|snapshot|allowlist`)
- [X] T016 Create `main/core/validator/ScopedValidationSupportChain.java`, extending `ValidationSupportChain` with the snapshot's `CacheConfiguration`, so definition caching is unchanged (spec FR-014). It overrides `validateCode`, `validateCodeInValueSet` and `lookupCode`:
  - key from `snapshotId` + `TerminologyScope.current().scopeId()` + arguments;
  - on a miss, run a private copy of HAPI 8.12.1's module loop (`ValidationSupportChain` lines 811–1000, including `generateResultForUnknownCodeSystem`), with a comment citing the version and `ScopedChainLoopTest`;
  - `validateCodeInValueSet` without a value set URL is not cached;
  - store a result only when the call didn't degrade the request: read `TerminologyBudget.degraded()` before and after the module loop, and skip `put` when it went from false to true. This covers a `lookupCode` that returned `null` because a server was unavailable, which carries no "not checked" marker;
  - report hit and miss to the cache listener per FHIR version.

  Delete `SnapshotBuilder.CacheCountingChain` (depends on T015)
- [X] T017 Give `main/core/validator/ValidatorSnapshot.java` a unique `long snapshotId` (a static `AtomicLong`, assigned in the constructor, with an accessor). Add it to the debug `toString` only
- [X] T018 Extend `main/core/terminology/TerminologyServices.java` with a `TerminologyCodeCache codeCache` component, keeping `chainCache` for definitions only. `OFFLINE` gets a small cache (`maximumSize 100`). Update every constructor call (`ServiceRegistry`, test harnesses)
- [X] T019 Change the default of `tx.cacheSize` from 5000 to 50000 in `src/main/resources/config.json` (bounds stay 100–1,000,000 in `main/core/config/ConfigLoader.java`). Update the javadoc in `main/core/config/ApplicationConfig.java:105` to "Code results cached per instance (`FV_TX_CACHE_SIZE`, spec 010 FR-013)". Build the `TerminologyCodeCache` in `main/ServiceRegistry.java` from `tx.cacheSize` and `tx.cacheTtlMs`

**Checkpoint**: T006–T009 pass. The service still runs with the 1.0.0 per-build modules (not yet replaced).

---

## Phase 3: User Story 1 - Change a context's terminology setting without a rebuild (Priority: P1) 🎯 MVP

**Goal**: The terminology setting is a per-request scope taken from the context default. Changing it never builds.

**Independent Test**: Context set to `server` A. Validate (reaches A). PATCH to `server` B. The next validation
reaches B, no `Built validator for` log line appears, `fv_validator_context_builds_total` is unchanged, and no result
cached from A is served (quickstart A).

### Tests for User Story 1 (write first, they must fail)

- [X] T020 [P] [US1] IT `test/core/validator/TerminologyNoRebuildIT.java`, using `AppHarness` with two instances on one database and the `StubTerminologyServer` from `test/testsupport`:
  - **US1-1:** A→B. The next request on both instances reaches B, zero builds, B's answer not A's.
  - **US1-2:** A→none. Zero outbound calls.
  - **US1-3:** none→ecosystem. Routed through `StubTxRegistry`.
  - **SC-001:** propagation within the existing window.
  - **FR-003:** a setting change during a slow stub call does not change the running request.
- [X] T021 [P] [US1] IT case in `TerminologyNoRebuildIT` for US1-4 and FR-011. Remove A from the stub registry list and refresh the allowlist: the next request reports the NOT_PERMITTED warning, answers HTTP 200, makes zero calls to A and starts zero builds. Restore A, and the next request reaches A again
- [X] T022 [P] [US1] Unit test `test/core/validator/ResolvedContextTest.java` (new): `sameAs` is true for equal packages and hashes whatever the stored terminology; false for a changed hash or package list
- [X] T023 [US1] Update the spec 007 tests whose "a setting change rebuilds" assertions no longer hold:
  - `test/web/TerminologySettingIT.java` lines 39, 240–263: expect no rebuild, and the next request uses the new setting;
  - `test/core/validator/TerminologyRoutingIT.java`, `EcosystemRoutingIT.java` and `TerminologyFailureIT.java`: replace "built with setting X" with "default X".

  Keep every other assertion unchanged (spec 007 ITs must otherwise pass as-is)

### Implementation for User Story 1

- [X] T024 [P] [US1] Create `main/core/terminology/TerminologyDefaults.java` (data-model §2): an immutable `Map<String, TerminologySettings>`; `get(key)` returns `TerminologySettings.NONE` for a missing key and for the base context; `EMPTY`
- [X] T025 [US1] Remove the `terminology` component, `withTerminology` and the terminology comparison from `main/core/validator/ResolvedContext.java` (data-model §9). Fix every caller: `ConfigSync.resolveAll`, `ValidatorRegistry`, `SnapshotBuilder`, tests
- [X] T026 [US1] In `main/core/validator/ConfigSync.java`, publish a per-version `TerminologyDefaults` (from `row.terminologyOf(key)` for every included key), swapped atomically on every sync, next to `pinnedAccess` (lines 67, 111, 132, 305). Add `terminologyDefaults(SupportedFhirVersion)`. Delete the terminology overload of `resolveAll`
- [X] T027 [US1] Rewrite `SnapshotBuilder.assemble` in `main/core/validator/SnapshotBuilder.java`:
  - every snapshot gets `new TerminologyOnlySupport(new RequestScopedTerminologySupport(ctx, version, txServices.clients()))` at the same position as today (before `InMemoryTerminologyServerValidationSupport`, spec 007 research R4);
  - the chain is `ScopedValidationSupportChain`;
  - remove `serverModule`, `ecosystemModule`, the `tx` parameter and `snapshot.setTerminology`;
  - the warm-up opens `TerminologyScope.none()` together with `TerminologyBudget.open(0)` and closes both in `finally` (spec FR-004, research R13)
- [X] T028 [US1] Delete `main/core/terminology/ServerTerminologySupport.java`, `EcosystemRoutingTerminologySupport.java` and `DeniedTerminologySupport.java` (replaced by `TerminologyRouter`, T012). Move their unit tests to router tests in `test/core/terminology/TerminologyRouterTest.java`
- [X] T029 [US1] In `main/core/validator/ValidatorSnapshot.java`, replace the `terminology` field and `setTerminology` with a `validate(..., EffectiveTerminology effective, TerminologyRouter router)` overload:
  - it opens `TerminologyScope` and `TerminologyBudget` (budget 0 for mode `none`, else `requestBudgetMs`) and closes both in `finally`;
  - on `TerminologyBudget.degraded()` it calls `codeCache.invalidateScope(scopeId)` instead of `chain.invalidateExpiringCaches()`;
  - `Outcome` gains an `EffectiveTerminology effective` component.

  The old overloads use `none`/`CONTEXT` for tests and warm-up
- [X] T030 [US1] In `main/core/service/FhirValidationService.java`:
  - add `setTerminologyDefaults(Function<SupportedFhirVersion, TerminologyDefaults>)` and `setTerminologyServices(TerminologyServices)`;
  - after `checkAccess`, compute `EffectiveTerminology.resolve(defaults.get(key.igPackage()), TerminologyOverride.ABSENT)`;
  - check the default in memory: `allowlist.current().isAllowed(server, version)`, or for `ecosystem` with a server the same; otherwise `denied = true` with router `DENIED`. Log WARN once per (context, server) change through a small `ConcurrentHashMap` of last-denied states (research R5);
  - build the router and pass both to `snapshot.validate`.

  Wire it in `main/ServiceRegistry.java` next to `setPinnedAccess` (line 153)
- [X] T031 [US1] In `main/core/validator/ValidatorRegistry.java`:
  - `details()` (around line 567) reads `"terminology"` from `ConfigSync.terminologyDefaults(version).get(key).toJson()` instead of `ResolvedContext`;
  - when a slot's snapshot is replaced or dropped, call `txServices.codeCache().invalidateSnapshot(old.snapshotId())`
- [X] T032 [US1] In the allowlist refresher (`main/core/terminology/TxServerAllowlistSource.java` / `TxRegistry.java`, wherever a new `VersionList` is installed), call `codeCache.invalidateEcosystem(version)` when that version's list changes (research R4)
- [X] T033 [US1] Remove per-build terminology heap accounting:
  - delete `HeapBudget.terminologyCacheBytes` and the `estimate/reserve/admitInclude` overloads that took a `TerminologySettings` in `main/core/validator/HeapBudget.java`;
  - update callers in `SnapshotBuilder.java:137`, `main/core/service/IgPackageService.java:248` and `main/ServiceRegistry.java:134`;
  - reserve the global code-cache bound once at startup in `ServiceRegistry`: `HeapBudget.TX_CACHE_ENTRY_BYTES × tx.cacheSize`, held for the process life, as a new `HeapBudget.reserveFixed(long)` (spec FR-024, research R9)
- [X] T034 [US1] Update the descriptions of `/{version}/validator` POST/PATCH in `src/main/resources/openapi.yaml` (contracts/http-api.md §4): "Changing `terminology` takes effect on the next validation on every instance; the context is not rebuilt (1.1.0). It is the default; a validation request can override it with `tx`/`txMode`." Make sure a terminology-only PATCH answers exactly as before (spec FR-001a), and that `IgPackageService` adds no build-related warning for it

**Checkpoint**: US1 tests pass; spec 003/004/007 ITs pass (with T023's updates). MVP is deliverable.

---

## Phase 4: User Story 2 - Choose the terminology setting for one request (Priority: P2)

**Goal**: The `tx` and `txMode` parameters, the resolution table, the allowlist check before validation, the
`X-Tx-*` headers and the usage fields.

**Independent Test**: On a context set to `server` A, requests with no parameters, `tx=B` and `txMode=none` reach A,
reach B, and make no call respectively. Same snapshot, no build (quickstart B).

### Tests for User Story 2 (write first, they must fail)

- [X] T035 [P] [US2] Unit test `test/core/model/ValidationRequestOptionsTest.java` (extend). Parsing of `tx`/`txMode` per contracts/http-api.md §2, with exact detail texts:
  - "txMode must be none, server or ecosystem";
  - "Give tx and txMode at most once each";
  - "tx and txMode must not be empty" (after trimming);
  - "tx must be an http or https base URL without query, fragment or user info";
  - "tx must be at most 512 characters";
  - "tx is not allowed with txMode=none".

  No detail contains the URL. `txMode` is case-sensitive (`Server` → 400). Surrounding whitespace in `tx` is trimmed, and the value is normalised (`HTTPS://Tx.Example.org/r4/` → `https://tx.example.org/r4`). A repeated `tx`, a `tx` of 513 characters and `txMode=Server` must produce these curated texts through the full HTTP path too (not only the parser); see the matching case in the next task
- [X] T036 [P] [US2] IT `test/web/TerminologyOverrideIT.java` (`AppHarness`, `StubTerminologyServer` A and B, `StubTxRegistry`). It covers:
  - US2-1 to US2-4 and SC-002: one snapshot, zero builds, nothing cached from A served for B;
  - US2-5: unlisted `tx` → 400 `tx-server-not-registered`, zero calls to it;
  - registry never obtained → 503 `tx-registry-unavailable` with its retry hint;
  - US2-6: 400 `invalid-request` cases, including a repeated `tx`, a 513-character `tx` and `txMode=Server` over HTTP with the curated texts;
  - `txMode=ecosystem&tx=B`, and the base context with `txMode=ecosystem`;
  - the edge case "denied default + override used";
  - the edge case "`tx` = the removed default server → 400";
  - FR-016: with `StubTerminologyServer` B answering after a delay longer than `FV_TX_REQUEST_BUDGET_MS` (set to 500 ms in the test), `tx=B` stops calling B once 500 ms of terminology time is spent and reports the remaining codes with the "time budget … used up" warning (HTTP 200). On a context set to `server` A, `txMode=none` opens a budget of 0 and makes no call, and A's stub counter stays unchanged
- [X] T037 [P] [US2] IT case in `TerminologyOverrideIT` for the check order (spec FR-008a). A caller without access to a private context, sending a bad `tx`, gets 404 `package-not-found` (not a terminology answer). A bad `ig` shape plus a bad `txMode` gives the `ig` error first. A context still building plus an unlisted `tx` gives 400 `tx-server-not-registered` before 503 `not-ready`
- [X] T038 [P] [US2] Contract test `test/web/endpoint/ValidationApiContractTest.java` (extend). Every 200 carries `X-Tx-Mode`, `X-Tx-Source` and, when there is a server, `X-Tx-Server`, for both `application/json` and `application/fhir+json`. `X-Tx-Server` follows FR-010: `server` → server used; `ecosystem` → `tx`, else the context's server, else absent; `none` → absent. 4xx and 503 answers carry none of them. The body is byte-identical with and without the headers' existence (the same request on a context with an unchanged default)
- [X] T039 [P] [US2] Test `test/web/CorsConfigTest.java` (new or extend): `Access-Control-Expose-Headers` contains `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source`
- [X] T040 [P] [US2] Extend `test/web/UsageIT.java` and `test/core/usage/LogUsageWriterTest.java`. A validated request records `tx_mode`, `tx_server` and `tx_source` (DB and log sink). A request refused before validation (400 `tx-server-not-registered`, 415, 404) records all three as null
- [X] T041 [P] [US2] Extend `test/core/terminology/TerminologyClientsIsolationTest.java` (spec FR-015, research R12):
  - open B's circuit with 5 failing calls (`CircuitBreaker.THRESHOLD`); a call to A still succeeds and B is skipped;
  - the same holds when B was named by a request override and A is a context default;
  - the connection pools of A and B are separate.
- [X] T042 [P] [US2] Extend `test/core/service/MigrationUpgradeIT.java`. V16 applies on a populated V15 (1.0.0) database. Existing `validation_usage` rows keep null in the three new columns. An insert with the new columns works

### Implementation for User Story 2

- [X] T043 [US2] In `main/core/model/ValidationRequestOptions.java`, add `TerminologyOverride terminology()` parsed in `fromRequest`:
  - read `request.params().getAll("tx")` and `getAll("txMode")`; more than one value of either → 400 "Give tx and txMode at most once each";
  - trim, then empty → 400 "tx and txMode must not be empty";
  - `TerminologyMode.fromWire` → 400 "txMode must be none, server or ecosystem";
  - length > 512 → 400 "tx must be at most 512 characters";
  - `TerminologySettings.normaliseUrl` failure → 400 "tx must be an http or https base URL without query, fragment or user info";
  - none + tx → 400 "tx is not allowed with txMode=none".

  Log the raw `tx` at DEBUG only
- [X] T044 [US2] In `main/web/endpoint/ValidationApi.java`:
  - parse the override in `doValidation` right after `igParameter(ctx)` and before the empty-body check (check order step 2, spec FR-008a: `ig` shape first, then `tx`/`txMode`, then the body), and pass it to `FhirValidationService.validate`;
  - in the 200 responder, add `X-Tx-Mode`, `X-Tx-Source` and (when not null) `X-Tx-Server` from `outcome.effective()`, carried through `Rendered`;
  - in `handleValidateFailure`, after the `ig` shape check, also run the override parse, so a request the contract layer refused for another reason (e.g. the body) still reports a bad `tx`/`txMode` in the FR-008a order;
  - add the header-name constants
- [X] T045 [US2] In `main/core/service/FhirValidationService.java`, extend `validate(...)` with the `TerminologyOverride`. After `checkAccess` and before `registry.snapshot(key)` (so before the readiness check, spec FR-008a):
  - resolve with `EffectiveTerminology.resolve(default, override)`;
  - for `source == REQUEST` with a server, call `allowlist.current().require(server, version)`, so failures are the existing 400/503 `ProblemException`s;
  - for `ecosystem` without a server, call `allowlist.current().require(settings, version)`;
  - record the effective setting on the meter
- [X] T046 [P] [US2] Add `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source` to `exposedHeaders` in `main/web/CorsConfig.java` line 29
- [X] T047 [P] [US2] Create `src/main/resources/db/migration/V16__usage_terminology.sql` (data-model §7). Three `ALTER TABLE validation_usage ADD COLUMN`s:
  - `tx_mode varchar(16)`, values `none`, `server` or `ecosystem`;
  - `tx_server varchar(512)`, the normalised URL;
  - `tx_source varchar(8)`, `request` or `context`.

  All nullable, with no default. Add a header comment naming spec 010 FR-010
- [X] T048 [US2] Extend `main/core/usage/UsageRecord.java` with `txMode`, `txServer` and `txSource`, cut to 16, 512 and 8 characters like its other strings. Add them to the insert tuple and the column list in the DB writer, and to the log writer's JSON. Extend `main/core/usage/UsageMeter.java` with `terminology(EffectiveTerminology)`, which stays null when never called (a request refused before validation)
- [X] T049 [US2] Update `src/main/resources/openapi.yaml` validate operation per contracts/http-api.md §1–§3:
  - `tx` and `txMode` query parameters as plain strings (no `maxLength`, no `enum`): length, allowed values and repeats are checked in code (T043), so every refusal carries the curated texts of FR-007. The limits are written in the descriptions;
  - the "Terminology per request" paragraph with the resolution table;
  - the three 200 response headers;
  - the new 400/503 cases in the description

**Checkpoint**: US1 and US2 pass together. Quickstart B passes manually.

---

## Phase 5: User Story 3 - Contexts share the definitions of common packages (Priority: P3)

**Goal**: One parse per package content per instance, shared by every context of the FHIR version, with results
identical to 1.0.0.

**Independent Test**: DK Core 3.5.0 + MedCom Core 4.0.0 as two R4 contexts. The corpus results equal the 1.0.0 golden files
(sequential and 8 threads), and the second context's memory is ≥ 20% lower than on 1.0.0 (quickstart C, D).

**Note**: per plan "Implementation Order" step 1, this phase may start right after Phase 2 as a spike. If T052 fails
or T062's memory gate fails, stop and report before continuing.

### Tests for User Story 3 (write first, they must fail)

- [X] T050 [P] [US3] Unit test `test/core/npm/SharedPackageDefinitionsTest.java`:
  - the same key → the same `SharedPackage` instance;
  - different sha256 → different entries;
  - 8 concurrent `getOrParse` calls for one key, on a fixed 8-thread executor standing in for the package workers → the parser runs once (counting parser);
  - a parser that throws → that caller fails and nothing is stored; the next waiter on the same lock parses again, and parses never overlap, and each waiter retries in turn (FR-018);
  - after dropping all strong references and `System.gc()` (polled with `Await`), the entry is gone (FR-019);
  - a package with a null hash is never stored (FR-017)
- [X] T051 [P] [US3] Unit test `test/core/service/CustomNpmPackageValidationSupportTest.java` (new):
  - two supports built from one `SharedPackage` hold the **same** object for a StructureDefinition that already has a snapshot, and for a ValueSet;
  - a StructureDefinition without a snapshot that `SnapshotPregenerator` completes is a different object in each support, and the shared original has no snapshot;
  - a definition that stays incomplete is a private clone (FR-020);
  - duplicate canonical in two packages: the last added wins, as in 1.0.0 (FR-021)
- [X] T052 [US3] IT `test/core/validator/SharedDefinitionsDifferentialIT.java` (SC-004, FR-023). Include DK Core 3.5.0 and MedCom Core 4.0.0 on R4 (fixtures T001), validate every corpus file (T002) in its context, and compare with `GoldenOutcomes` (T005):
  - sequentially;
  - with 8 threads × 2 contexts × 3 rounds in random order.

  Store every package of the lockfile (T001) before including the contexts. A `server`-mode variant uses `StubTerminologyServer` with fixed answers. Mark it `@Tag("release-gate")`
- [X] T053 [P] [US3] IT `test/core/npm/SharedPackageLifecycleIT.java` (store every package of the lockfile (T001) first):
  - US3-1: the second build logs "reused" for shared packages and the parse counter does not grow;
  - US3-2: replace a stored package with new content under the same `name#version`; after the rebuild, the new definitions are used and none of the old;
  - US3-3: exclude one of two contexts sharing P; the other still validates identically, and after excluding both plus GC, `fv_packages_shared_entries` no longer counts P within 60 s (SC-008);
  - the edge case of concurrent builds of two contexts sharing P: one parse

### Implementation for User Story 3

- [X] T054 [P] [US3] Create `main/core/npm/SharedPackage.java`, data-model §8. Record `Key key` (record `SupportedFhirVersion fhirVersion, String nameVersion, byte[] sha256`, with `equals`/`hashCode` on the hash bytes by value), `List<IBaseResource> resources` (unmodifiable) and `long expandedBytes`
- [X] T055 [US3] Create `main/core/npm/SharedPackageDefinitions.java`, research R7 and data-model §8:
  - Caffeine `Cache<Key, SharedPackage>` with `weakValues()`;
  - a `ConcurrentHashMap<Key, Object> locks`;
  - `SharedPackage getOrParse(Key, Supplier<SharedPackage> parser)`: hit → return. Otherwise `synchronized (locks.computeIfAbsent(key, k -> new Object()))`: look in the cache again, then run the parser, put the result, and `locks.remove(key)`. A parser exception propagates, stores nothing, and keeps the lock object (so a waiter and a newcomer never parse at once);
  - `contains(Key)`, and gauges `entries(version)` and `expandedBytes(version)`;
  - a `Listener` for hit, miss and failure.

  The javadoc states that it must only run on a package worker (constitution I). There is no `CompletableFuture`
- [X] T056 [US3] Rework `main/core/service/CustomNpmPackageValidationSupport.java` (research R7 steps 1–4):
  - the constructor takes `(FhirContext ctx, List<NpmPackage> packages, Map<String, byte[]> hashes, SharedPackageDefinitions shared, SupportedFhirVersion version)`;
  - per package: if a hash exists, use `shared.getOrParse(key, () -> parse(pkg))`, else parse privately;
  - collect StructureDefinitions in package order and call `SnapshotPregenerator.withSnapshots`;
  - replace any StructureDefinition still without a snapshot by `ctx.newTerser().clone(sd)`;
  - `addResource` everything in the 1.0.0 order;
  - keep strong references to the `SharedPackage`s in a field;
  - log per package `Loaded IG {}#{} ({} resources, reused|parsed) for FHIR {}` (FR-026).

  Keep a package-private test-only constructor with `shared = null`, meaning always parse privately (research R8 "no sharing" baseline). It is never reachable from configuration (FR-022)
- [X] T057 [US3] In `main/core/validator/SnapshotBuilder.java`, pass `resolved.hashes()`, the `SharedPackageDefinitions` instance and the version into `CustomNpmPackageValidationSupport` (via `assemble`). Add a `SharedPackageDefinitions` constructor parameter, and wire one instance per process in `main/ServiceRegistry.java`
- [X] T058 [US3] Add the shared-package metrics to `main/core/metrics/ServiceMetrics.java` (data-model §10): `fv.packages.shared.entries`, `fv.packages.shared.expanded.bytes` (gauges, tag `fhir_version`) and `fv.packages.shared.lookups` (counter, tags `fhir_version`, `result` = `hit|miss|failure`). Wire the `SharedPackageDefinitions.Listener`

**Checkpoint**: T050–T053 pass, and T052 matches the golden files exactly.

---

## Phase 6: User Story 4 - Operators see and budget the shared memory (Priority: P4)

**Goal**: The heap check reserves only what a build adds; metrics expose the code cache and sharing.

**Independent Test**: Build two contexts sharing a large package. The second is admitted with a budget for its unique
packages only, and the metrics show one shared entry with one hit.

### Tests for User Story 4 (write first, they must fail)

- [X] T059 [P] [US4] Extend `test/core/validator/HeapBudgetTest.java`:
  - `estimate(unshared, shared) == BUILD_OVERHEAD + 6×unshared + 1×shared`;
  - `reserve(unshared, shared)` holds that amount until `release`;
  - `reserveFixed` lowers free heap permanently;
  - a build that only fits once released shared entries are collected is admitted (collect runs first, edge case)
- [X] T060 [P] [US4] IT `test/core/validator/HeapBudgetSharedIT.java`, with a test `HeapBudget.setMaxHeap` sized so that two full reparses don't fit but one reparse plus a shared build does. Store every package of the lockfile (T001) first. The second context builds (US4-1). `/metrics` shows `fv_packages_shared_lookups_total{result="hit"}` ≥ 1, `fv_tx_code_cache_size` and `fv_tx_code_cache_misses_total` (US4-2)

### Implementation for User Story 4

- [X] T061 [US4] In `main/core/validator/HeapBudget.java`, add `estimate(long unsharedExpanded, long sharedExpanded)`, `reserve(long unsharedExpanded, long sharedExpanded)` and `admitInclude(long unsharedExpanded, long sharedExpanded)` with factor `SHARED_HEAP_PER_EXPANDED_BYTE = 1` (javadoc: covers indexes, chain caches and private clones; checked against the SC-005 measurement, research R9), plus `reserveFixed(long)`. In `SnapshotBuilder.buildNow`, split `expandedBytes` by `SharedPackageDefinitions.contains(key)` at build start. In `IgPackageService` (include admission), do the same with the same helper
- [X] T062 [US4] Measure SC-005 and SC-006 per quickstart D:
  - histogram on 1.0.0 vs this branch, two contexts;
  - build time of the second context, warm instance.

  Write `perf/results-1.1.0.md` with the numbers, the measured per-context factor (lower `SHARED_HEAP_PER_EXPANDED_BYTE` in T061 only if the measurement supports it, never below the measured value, spec FR-024) and the retained-memory factor used for the estimate metric. **Gate**: the second context uses ≥ 20% less memory than on 1.0.0
- [X] T063 [US4] Add the code-cache metrics to `main/core/metrics/ServiceMetrics.java`:
  - `fv.tx.code.cache.misses` (counter, tag `fhir_version`);
  - `fv.tx.code.cache.invalidations` (counter, tag `reason` = `degraded|snapshot|allowlist`);
  - `fv.tx.code.cache.size` (gauge);
  - `fv.validations.tx_source` (counter, tag `source` = `request|context`);
  - the estimated retained memory of shared entries as `fv.packages.shared.retained.estimate.bytes` (expanded bytes × the factor from T062).

  Keep `fv.tx.code.cache.hits`. No server URL in any tag (spec FR-025). Wire the `TerminologyCodeCache.Listener`, and record `tx_source` in `FhirValidationService`

**Checkpoint**: All stories pass on their own and together.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T064 [P] Set the version to `1.1.0` in `pom.xml` and `src/main/resources/openapi.yaml` (`info.version`)
- [X] T065 [P] Add a `## [1.1.0]` entry to `CHANGELOG.md`:
  - the `tx`/`txMode` parameters and `X-Tx-*` headers;
  - no rebuild on a terminology setting change (amends spec 007 FR-004, FR-010b);
  - code results cached per terminology scope and context build (amends FR-018);
  - the `FV_TX_CACHE_SIZE` default changes from 5000 per context to 50,000 per instance, and an explicitly set value now bounds the whole instance, so raise it;
  - shared package definitions and the new metrics;
  - migration V16;
  - the rolling-upgrade note (1.0.0 instances ignore `tx`/`txMode` and still rebuild; spec FR-028a)
- [X] T066 [P] Update `.env.example`: `FV_TX_CACHE_SIZE=50000`, and change its comment to "Code results cached per instance (100-1000000)". The file already has local changes; edit only these lines
- [X] T067 [P] Update `README.md`, "Terminology servers" section: per-request `tx`/`txMode` with the resolution table, the `X-Tx-*` headers, that the context setting is a default applied without rebuild, and the cache sizing. Add a short "Memory" note on shared package definitions
- [X] T068 [P] Correct `specs/010-shared-packages-request-tx/quickstart.md`, which named US Core: use the fixture pair from T001 (DK Core 3.5.0 and MedCom Core 4.0.0)
- [X] T069 Run the k6 comparison (SC-007): `perf/compare-run.sh` with the 1.0.0 image and this branch's image (`perf/validate.js`, same profile and limits). Add the p95 figures to `perf/results-1.1.0.md`. Gate: within 5% of 1.0.0
- [X] T070 Run `mvn verify` (including `@Tag("release-gate")` T052) and the full quickstart A–F by hand; confirm no `BlockedThreadChecker` warnings in the logs (constitution I)
- [X] T071 Update the checklists: re-check `specs/010-shared-packages-request-tx/checklists/requirements.md` notes (sharing default and targets are now decided), and leave `checklists/review.md` markers to the reviewer

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none. T003 → T004. T001 and T002 come before T004.
- **Foundational (Phase 2)**: needs nothing from Phase 1 except for the tests that use fixtures (none here). Blocks
  every story.
- **US1 (Phase 3)**: needs Phase 2.
- **US2 (Phase 4)**: needs US1's T029–T030 (snapshot takes an effective setting; the service resolves one). Its tests
  can be written in parallel with US1.
- **US3 (Phase 5)**: needs Phase 2 only for T017 (`snapshotId` is not used by US3) and Phase 1 (T001–T005). It is
  independent of US1/US2 code paths (`npm/`, `CustomNpmPackageValidationSupport`, one parameter in `SnapshotBuilder`).
  Recommended to start early as the spike (plan step 1).
- **US4 (Phase 6)**: needs US3 (T055 for `contains`) and US1 (T033 removed the old tx accounting).
- **Polish (Phase 7)**: after all stories. T069 and T070 last.

### Within Each Story

Tests first (they fail) → new types → wiring → endpoint and contract → story checkpoint.

### Shared-file hotspots (do not run these in parallel)

- `SnapshotBuilder.java`: T016 (delete inner class), T027, T033, T057, T061.
- `FhirValidationService.java`: T030, T045, T063.
- `ServiceRegistry.java`: T019, T030, T033, T057.
- `openapi.yaml`: T034, T049, T064.
- `HeapBudget.java`: T033, T061.

## Parallel Examples

**Phase 2 tests**: T006, T007, T008 and T009 together, then T010, T011, T012 and T015 together.

**US1**: T020, T021 and T022 together; then T024 (new file) beside T025.

**US2**: T035–T042 together (separate test files); then T046 and T047 beside T043.

**US3** (can run beside US1/US2 after Phase 2): T050, T051 and T053 together; T054 beside the tests.

**US4**: T059 and T060 together.

**Polish**: T064–T068 together.

## Implementation Strategy

### MVP (US1)

Phase 1 (T001–T005 can be deferred if US3 is not started), Phase 2, then Phase 3. Stop and check: a terminology
setting change applies with no build; spec 007 ITs green with T023's updates. This alone removes the costliest
operation of 1.0.0.

### Incremental delivery

1. Run US3's spike (Phase 1 + T050–T057, then T052 and the T062 measurement) early and in parallel. It is the
   riskiest part. If the differential or memory gate fails, report before more work goes into it.
2. US1 → US2 (the API surface) → US3 hardening → US4 accounting → Polish.
3. Each checkpoint is a mergeable state, but release 1.1.0 needs every story plus the gates in T052, T062 and T069.

## Notes

- Every new class gets javadoc naming the spec items it implements (house style).
- Problem details are curated fixed texts; `tx` never appears in a `detail` of `invalid-request` and is logged at
  DEBUG only (spec FR-007).
- Commit after each task or logical group; plans and scratch notes stay in the git-ignored `var/`.

## Phase 8: Convergence

- [X] T072 Add the `server`-mode variant to `test/core/validator/SharedDefinitionsDifferentialIT.java`: build the three contexts with a `TerminologyServices` whose clients reach a `StubTerminologyServer` with fixed answers, validate the corpus with a `server` scope (sequentially and with 8 threads) on shared and on private (`sharedDefinitions = null`) contexts, and assert identical OperationOutcomes, so sharing is shown not to change results when a server answers per FR-023 (partial)
- [X] T073 Extend `test/web/endpoint/ValidationApiContractTest.java` for the validate operation's new contract: `tx`/`txMode` accepted, the curated 400 texts, `X-Tx-Mode`/`X-Tx-Server`/`X-Tx-Source` on 200 for `application/json` and `application/fhir+json`, none on 4xx/503, per FR-027 and Constitution V (partial)
- [X] T074 Add a case to `test/web/TerminologyNoRebuildIT.java` where the context's setting changes from A to B while a request is waiting on a slow stub A: that request completes using A only (B gets no call), the next request uses B, per FR-003 and the spec edge case "a context's setting changes while a request is running" (partial)
- [X] T075 Run quickstart scenarios A–F of `specs/010-shared-packages-request-tx/quickstart.md` by hand against a local 1.1.0 stack and record the outcome in `perf/results-1.1.0.md`, per T070 (partial)
- [X] T076 Add a case to `test/web/TerminologyOverrideIT.java` where the context is still building (use `SnapshotBuilder.setBuildHook` to hold the build) and the request names an unlisted `tx`: the answer is 400 `tx-server-not-registered`, not 503 `not-ready`, per FR-008a (partial)
- [X] T077 Add a test that an allowlist change reaches the code cache: after `TxRegistry` installs a list that differs for R4 (via `TestTerminology.reloadStored`), `ecosystem:*` entries of R4 are gone and other entries stay, per research R4 and FR-011 (partial)
- [X] T078 Reconcile `TerminologyScope.current()` with data-model §5 and T009: the code treats "no scope open" as mode `none` (fail closed offline) while T009 asked for an `IllegalStateException`; record the chosen behaviour in `research.md` R1 and keep `RequestScopedTerminologySupportTest.noScopeOpenBehavesAsNone` consistent with it, per plan research R1 (contradicts)
- [X] T079 Review the test-only seams added to production classes (`ServiceRegistry.terminologyGuardForTests`, `ValidatorSnapshot.setDefaultTerminologyForTests`, `TxRegistry.reloadStored`) and record their justification in `plan.md` Complexity Tracking or replace them with test-scoped wiring, per Constitution VIII (unrequested)
