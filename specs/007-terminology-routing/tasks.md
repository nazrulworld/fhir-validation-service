---

description: "Task list for 007-terminology-routing"
---

# Tasks: Terminology Servers per IG Context, with Ecosystem Routing, and Included-Only Validation Contexts

**Input**: Design documents from `specs/007-terminology-routing/`

**Prerequisites**: plan.md, spec.md, research.md (R1–R15), data-model.md, contracts/http-api.md, quickstart.md

**Tests**: Included. Constitution V requires:
- test-first development;
- real PostgreSQL (Testcontainers) for persistence;
- real local HTTP peers (Vert.x stubs) instead of mocks for the registry and terminology servers;
- a contract test for every endpoint change.

Write each test task first and confirm it fails before the matching implementation task.

**Organization**: Tasks are grouped by the user stories in spec.md (US1–US5). Phases follow **dependency order, not
the order in spec.md**:
- US5 (P1) is independent and comes first.
- US2 (P1, allowlist), US4 (P2, settings) and US3 (P2, server mode) are prerequisites of US1 (P1, ecosystem
  routing), which comes last.

See Dependencies.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on unfinished tasks)
- **[Story]**: The user story the task belongs to (US1–US5)

## Path Conventions

- Production code: `src/main/java/nzi/fhir/validator/` (abbreviated `main/` below)
- Tests: `src/test/java/nzi/fhir/validator/` (abbreviated `test/` below)
- Test resources: `src/test/resources/`
- Contract: `src/main/resources/openapi.yaml`
- Migrations: `src/main/resources/db/migration/`
- Run one test with `./mvnw -q test -Dtest=<Class>`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Dependency, version, recorded fixtures and test servers used by later phases.

- [X] T001 Add `ca.uhn.hapi.fhir:hapi-fhir-client` with `<version>${hapi-fhir.version}</version>` (no inline version literal) next to `hapi-fhir-validation` in `pom.xml`, and bump the project version 3.2.1 → `4.0.0-SNAPSHOT`. Run `./mvnw -q -o dependency:list -DincludeArtifactIds=hapi-fhir-client` to confirm it resolves, and run the license plugin check (`ThirdPartyNoticesTest`). Update `THIRD-PARTY-NOTICES` if that test asks for it (plan Complexity Tracking).
- [X] T002 [P] Record the official registry answers as fixtures under `src/test/resources/tx-reg/`:
  - `discovery-R3.json`, `discovery-R4.json`, `discovery-R4B.json` and `discovery-R5.json`, from `GET https://ecosystem.hl7.org/tx-reg/?fhirVersion={v}` with `Accept: application/json`;
  - `resolve-R4-loinc.json` and `resolve-R4-snomed.json`, from `/tx-reg/resolve?fhirVersion=R4&url=...&usage=validation`.

  Then add hand-made variants:
  - `discovery-R4-mixed.json`: one entry with `"error": "timeout"`, one with `"security": "api-key"`, one with `"fhirVersion": "5.0.0"`, one with a missing `url`;
  - `resolve-R4-authoritative.json`: has `authoritative[]`;
  - `resolve-R4-format2.json`: `"formatVersion": "2"`;
  - `resolve-R4-unlisted.json`: a candidate URL that is not in discovery.

  Use a placeholder host `{{BASE}}` in URLs so the stubs can rewrite them (research R8, R9, R14).
- [X] T003 [P] Create `test/testsupport/StubTxRegistry.java`, a Vert.x HTTP server on a random loopback port. It serves `GET /tx-reg/` (by `fhirVersion` query) and `GET /tx-reg/resolve` from the T002 fixtures, with `{{BASE}}` replaced by a given stub server base. It must:
  - count hits per path;
  - be switchable to fail (503), hang, or return an HTML body when `Accept` isn't `application/json`.
- [X] T004 [P] Create `test/testsupport/StubTerminologyServer.java`, a Vert.x HTTP server answering `POST {base}/CodeSystem/$validate-code`, `POST {base}/ValueSet/$validate-code` and `POST {base}/CodeSystem/$lookup` with FHIR R4 `Parameters`. It must:
  - be configurable per (system, code) as valid/invalid/display;
  - count hits and record each request body (so tests can assert the `valueSet` and `tx-resource` parameters);
  - support modes: delay N ms, connection reset, 500, 302 redirect to another stub, and `/metadata` counting (it must stay 0).
- [X] T005 [P] Add fixture resources under `src/test/resources/fixtures/r4/`:
  - `observation-loinc-valid.json` (LOINC `8867-4`);
  - `observation-loinc-invalid.json` (LOINC `9999-9`);
  - `condition-snomed-valid.json` (SNOMED CT `38341003`) and `condition-snomed-invalid.json` (SNOMED CT `99999999999`), for SC-001;
  - `observation-ucum-only.json` (UCUM unit only, no external code);
  - `bundle-500-loinc.json` (500 Observations with distinct LOINC codes, for the budget).

  Add a small test IG package under `src/test/resources/packages/` (built the way `TestPackages` builds existing ones), named `test.tx.ig#1.0.0`. It contains:
  - CodeSystem `http://example.org/fhir/CodeSystem/local`, with `content: complete`;
  - ValueSet `http://example.org/fhir/ValueSet/mixed`, including that local system plus LOINC;
  - a profile on Observation binding `code` (required) to that ValueSet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Contract, schema, configuration, value types and plumbing that every story needs.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T006 Update `src/main/resources/openapi.yaml` exactly as in `contracts/http-api.md` §1–§5:
  - **validate:** 400 text for not-included IGs; the on-demand wording removed.
  - **include-ig POST:** the `terminology` request property; `IncludeResponse.terminology`; the new 400 `tx-server-not-registered` and 503 `tx-registry-unavailable` responses.
  - **include-ig PATCH:** `terminology` in the body; the new 400/503 responses; the response `terminology`.
  - **include-ig DELETE:** the description says requests are refused with 400 after exclude.
  - **health detail:** the `terminology` object.
  - **New schema `TerminologySettings`:** `type: object`, `additionalProperties: false`, `required: [mode]`, `mode: {type: string, enum: [none, server, ecosystem]}`, `server: {type: string, maxLength: 512}`.
- [X] T007 [P] Write `test/core/service/MigrationUpgradeIT.java` cases first:
  - V9 applies on an empty database and on top of the 3.2.1 schema;
  - an existing `fhir_validator_snapshots` row reads `tx_mode = 'NONE'` with `tx_server` NULL;
  - inserting `('SERVER', NULL)` and `('NONE', 'http://x')` fails the check constraint;
  - `tx_registry_snapshot` exists.
- [X] T008 Create `main/../resources/db/migration/V9__context_terminology.sql`, verbatim from data-model.md §3:
  - `tx_mode VARCHAR(16) NOT NULL DEFAULT 'NONE' CHECK (tx_mode IN ('NONE', 'SERVER', 'ECOSYSTEM'))`;
  - `tx_server VARCHAR(512) NULL`;
  - the constraint `fhir_validator_snapshots_tx_chk`: `(NONE and server NULL) or (SERVER and server NOT NULL) or ECOSYSTEM`;
  - table `tx_registry_snapshot(fhir_version VARCHAR(16) PRIMARY KEY, fetched_at TIMESTAMPTZ NOT NULL, registry_last_update TIMESTAMPTZ NULL, body JSONB NOT NULL)`.

  Include the header comment on rolling-upgrade safety (the defaults keep 3.x writers valid). Makes T007 pass.
- [X] T009 [P] Write `test/core/config/ConfigLoaderTest.java` cases first:
  - each new `FV_TX_*` entry is read, and refused outside its bounds;
  - setting `FV_IG_MAX_ON_DEMAND` or `FV_IG_BUILD_WAIT_MS` logs exactly one WARN per key naming it as deprecated and ignored, and does not fail start (use `LogCapture`) (FR-025).
- [X] T010 Extend `main/core/config/ConfigLoader.java` and `main/core/config/ApplicationConfig.java`:
  - **Add entries and getters**, verbatim from data-model.md §5:
    - `FV_TX_REGISTRY_REFRESH_MS` → `tx.registryRefreshMs` (long, default 21600000, bounds 300000–604800000);
    - `FV_TX_TIMEOUT_MS` → `tx.timeoutMs` (long, 5000, 500–60000);
    - `FV_TX_REQUEST_BUDGET_MS` → `tx.requestBudgetMs` (long, 10000, 1000–120000);
    - `FV_TX_CACHE_SIZE` → `tx.cacheSize` (int, 5000, 100–1000000);
    - `FV_TX_CACHE_TTL_MS` → `tx.cacheTtlMs` (long, 600000, 10000–86400000);
    - `FV_TX_MAX_CONNECTIONS_PER_SERVER` → `tx.maxConnectionsPerServer` (int, 10, 1–200).
  - **Defaults:** add them to the default config resource.
  - **Retire:** remove the `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS` entries, plus `igMaxOnDemand()` and `igBuildWaitMs()`. Add a `DEPRECATED` list that logs one WARN per key present in the environment.
  - **No registry-address key.**

  Makes T009 pass.
- [X] T011 [P] Write `test/core/terminology/TerminologySettingsTest.java` first. It covers:
  - JSON parse of `{mode}`/`{mode, server}` with lower-case modes;
  - rejection, each as 400 `invalid-request` with a curated text: an unknown mode, an unknown field, `server` missing for `server`, `server` present for `none`, a server longer than 512 characters, a non-http(s) scheme, an unparseable URL;
  - URL normalisation: scheme and host lower-cased, `:80` dropped for http and `:443` for https, trailing `/` dropped, query and fragment rejected;
  - value equality;
  - `TerminologySettings.NONE`.
- [X] T012 [P] Create `main/core/terminology/TerminologyMode.java` (enum `NONE`, `SERVER`, `ECOSYSTEM`, with `wire()`/`fromWire()` using lower-case names) and `main/core/terminology/TerminologySettings.java`:
  - record `(TerminologyMode mode, String server)` with `NONE`;
  - `static TerminologySettings parse(JsonObject)` throwing `ProblemException` 400;
  - `toJson()`, omitting `server` when null;
  - `static String normaliseUrl(String)`.

  Rules, verbatim from data-model.md §1: "Required for `SERVER`, optional for `ECOSYSTEM`, forbidden for `NONE`", "At most 512 characters". Makes T011 pass.
- [X] T013 [P] Add problem factories to `main/web/ProblemException.java`:
  - `igNotIncluded(String key, SupportedFhirVersion v)` → 400 `invalid-request`, detail "IG package {key} is not included for {v}; include it first (POST /{v}/include-ig)". `key` is always a concrete `name#version`: for a versionless `ig` with no included version it is the highest stored version the caller may use (spec FR-022);
  - `txServerNotRegistered(String url, SupportedFhirVersion v)` → 400, type `tx-server-not-registered`, extensions `registry: "https://ecosystem.hl7.org/tx-reg"` and `fhirVersion`;
  - `txRegistryUnavailable(SupportedFhirVersion v)` → 503, type `tx-registry-unavailable`, header `Retry-After: 60`.

  Add constants for the two new types. Unit-test them in `test/web/ErrorResponderTest.java`.
- [X] T014 Add `TerminologySettings terminology` (default `TerminologySettings.NONE`) to `main/core/validator/ResolvedContext.java`:
  - keep the existing constructors delegating with `NONE`;
  - add `withTerminology(TerminologySettings)`;
  - make `sameAs` also compare `terminology`.

  Extend the existing `ResolvedContext` unit coverage in `test/core/validator/ValidatorRegistryKeyTest.java`: same packages with different terminology are not `sameAs`.
- [X] T015 Create `main/core/terminology/TerminologyServices.java`:
  - record `(TxServerAllowlistSource allowlist, RegistryResolver resolver, TerminologyClients clients, ValidationSupportChain.CacheConfiguration chainCache, long requestBudgetMs)`;
  - `static final OFFLINE`: an empty allowlist, `RegistryResolver.DISABLED`, `TerminologyClients.NONE`, and `CacheConfiguration.defaultValues()`.

  Create minimal stubs of `TxServerAllowlist` (with `EMPTY`), `RegistryResolver` (with `DISABLED`) and `TerminologyClients` (with `NONE`) in `main/core/terminology/`, so the code compiles. They are completed in US2, US3 and US1.
- [X] T016 Change `main/core/validator/SnapshotBuilder.java`:
  - **New overload:** `assemble(SnapshotKey, long, List<String>, Map<String, byte[]>, List<NpmPackage>, TerminologySettings, TerminologyServices)`, as in `var/terminology_routing_plan.md` §5.
  - **Existing overloads:** delegate with `TerminologySettings.NONE` and `TerminologyServices.OFFLINE`.
  - **Chain:** build `new ValidationSupportChain(txServices.chainCache(), modules)`. For now only the `NONE` branch is real. Until US3 and US1 land, the `SERVER` and `ECOSYSTEM` branches build the context **offline** (no terminology module) and log one WARN "terminology mode {mode} not available yet; context {key} built offline". They never throw, so settings saved in US4 still produce an active, rebuilt context.
  - **`buildNow`:** pass `resolved.terminology()` and a new final `txServices` field set by the constructor (`ServiceRegistry` passes `TerminologyServices.OFFLINE` for now).

  `SnapshotRaceTest` and `ValidatorSnapshotProfileResolutionTest` must still pass unchanged.

**Checkpoint**: Foundation ready. `./mvnw -q verify` is green, and behaviour is unchanged.

---

## Phase 3: User Story 5 - Validation requests no longer build IG contexts on demand (Priority: P1) 🎯 MVP

**Goal**: `?ig=` is served only by included contexts. A stored, visible, not-included version gives 400 `invalid-request`. `latest` resolves among included versions. No build is ever started by a validation request (FR-021 to FR-025, research R13).

**Independent Test**: With `P#1.0.0` stored and not included, validate with `ig=P#1.0.0` → 400, and no build is started on any instance. Include it → 200. Exclude it → 400. An invisible package → 404 (US5 scenarios 1–6).

### Tests for User Story 5 ⚠️

- [X] T017 [P] [US5] Create `test/core/validator/IncludedOnlyValidationIT.java` (Testcontainers + `AppHarness`), covering US5 scenarios 1–6:
  1. stored, visible, not included → 400 `invalid-request`; the detail contains `P#1.0.0` and "include it first"; the `fv` build metrics show no build started, and `ValidatorRegistry.details()` lists no context for it;
  2. included → 200 with `X-Validation-Context: P#1.0.0`;
  3. `P#1.0.0` and `P#2.0.0` stored, only 1.0.0 included → `ig=P` and `ig=P#latest` use 1.0.0;
  4. an invisible or not-stored package → 404 `package-not-found`, identical body for both;
  5. an included context while STALE → 503 `not-ready`;
  6. after `DELETE /include-ig` → 400 (not a build).

  Also: a package for another FHIR version → 400 FHIR-version text, checked before "not included". Port every still-relevant assertion from `OnDemandContextIT`, `OwnershipOnDemandIT` and `ContextEvictionIT`.
- [X] T018 [P] [US5] Update `test/core/validator/IgReferenceTest.java`: `resolveIn` with included keys covers versionless picking the highest included visible version; versionless with none included but one stored and visible → `igNotIncluded`; exact, visible and not included → `igNotIncluded`; exact and invisible → empty (404 path).
- [X] T019 [P] [US5] Update `test/web/endpoint/ValidationApiContractTest.java`: the 400 not-included answer matches the `openapi.yaml` problem schema, and the DELETE description no longer mentions on-demand.

### Implementation for User Story 5

- [X] T020 [US5] Change `main/core/validator/IgReference.java`:
  - `resolve(...)` and `resolveIn(...)` take the version's included keys (`Set<String>`, from `PinnedAccess.asMap().keySet()`);
  - **versionless:** the highest included version the caller may use; if none is included, `ProblemException.igNotIncluded(name + "#" + highestVisibleStoredVersion, version)` when the catalog has a stored version the caller may use, otherwise empty (404);
  - **exact:** keep the not-stored/invisible → empty and FHIR-version → 400 checks, then not included → `igNotIncluded`;
  - keep the rate-limited `refreshOnMiss` only for the 404 path.

  Update the class javadoc (the on-demand wording goes). Makes T018 pass.
- [X] T021 [US5] Change `main/core/service/FhirValidationService.java`: pass `pinnedAccess.apply(version).asMap().keySet()` into `IgReference.resolve`. In `checkAccess`, drop the `ResolvedContext.forKey` on-demand fallback: `peekResolvedPackages` is now always present for a pinned key, so a missing one is a 503 not-ready.
- [X] T022 [US5] Remove the on-demand path from `main/core/validator/ValidatorRegistry.java`:
  - **Remove:** the `onDemand` map, `Kind.ON_DEMAND` (`Kind` becomes `{BASE, PINNED}`), `maxOnDemand`, `buildWaitMs`, `buildAdmission`, `waitFor`, `makeRoomLocked`, `evictOne`, `evictor()`, `EVICT_LRU`, `LOOKUP_JOINED`/`LOOKUP_BUILT` and `BUILD_TIMEOUT_WAIT`/`BUILD_REFUSED_ADMISSION`.
  - **Remove** the 5-arg constructor's on-demand parameters. Keep `(Vertx, SnapshotBuilder)` plus a constructor without those parameters.
  - **`snapshot(key)`:** serves base and pinned only; any other key fails with `ProblemException.igNotIncluded(key.igPackage(), key.fhirVersion())` (defence in depth; normally `IgReference` refuses first).
  - **`applyPinned`:** excluded contexts are dropped (log "no longer included; dropped"), not demoted.
  - **`onCatalogChanged`:** remove the on-demand iteration.
  - **`details()`:** reports `kind` `base` or `pinned` only.
  - **`contextCount(version, kind)`:** loses the `ON_DEMAND` case.
- [X] T023 [US5] Update `main/core/validator/HeapBudget.java`: remove the use of the registry `Evictor` (nothing is evictable). Keep the include-time 413 check. Update `test/core/validator/HeapBudgetTest.java` and `test/core/validator/HeapGuardIT.java`.
- [X] T024 [US5] Remove the on-demand branch of `ResolvedContext.forKey` in `main/core/validator/ResolvedContext.java`: an unknown pinned key resolves as the package owner, used only by tests. Update the javadoc of `missing`: it now means include-time only.
- [X] T025 [US5] Update `main/ServiceRegistry.java`: construct `ValidatorRegistry` without `igMaxOnDemand`, `igBuildWaitMs` or build admission, and stop wiring the evictor into `HeapBudget`. Update `main/core/metrics/ServiceMetrics.java` so the `on_demand` kind series and the removed build and lookup outcomes are no longer registered.
- [X] T026 [US5] Delete `test/core/validator/OnDemandContextIT.java`, `test/core/validator/OwnershipOnDemandIT.java` and `test/core/validator/ContextEvictionIT.java` (their assertions were ported in T017). Fix every other test that validated with `?ig=` against a not-included package so it includes the IG first, then run `./mvnw -q verify`:
  - `test/web/IgScopedValidationIT.java`
  - `test/web/OwnershipValidationIT.java`
  - `test/core/validator/ProfileResolutionIT.java`
  - `test/core/validator/PackageInvalidationIT.java`
  - `test/core/service/ValidationContextPropagationTest.java`
  - `test/web/ObservabilityIT.java`
  - `test/core/service/ConcurrencyIT.java`
  - any other test found by `grep -rln "ig=" src/test`

**Checkpoint**: US5 is complete and shippable on its own (breaking: 4.0.0). All tests are green.

---

## Phase 4: User Story 2 - Only official, open registry servers are ever contacted (Priority: P1)

**Goal**: An allowlist built only from the fixed official registry, with the last good copy in `tx_registry_snapshot`, the built-in fallback, the address and redirect guards, and health reporting (FR-006 to FR-012a, research R8, R10, R11).

**Independent Test**: With the stub registry: unlisted, secured, failing, wrong-version and private-address servers are never allowed; a restart with the registry down uses the stored list; with no list at all only the built-in servers are allowed, and health says so (US2 scenarios 1–7, SC-002).

### Tests for User Story 2 ⚠️

- [X] T027 [P] [US2] Create `test/core/terminology/TxServerAllowlistTest.java`, using the T002 fixtures. It covers:
  - kept vs. dropped entries in `discovery-R4-mixed.json` (error, api-key, 5.0.0 and missing url are all dropped);
  - R4 has 14 kept from the 2026-11-09 recording; R3 and R4B are empty;
  - major.minor version mapping (`4.0.1` → R4, `4.3.0` → R4B, `5.0.0` → R5, `3.0.2` → STU3);
  - exact normalised matching (`http://tx.fhir.org/r4/` matches; `https://tx.fhir.org/r4` does not);
  - `require` throws `txServerNotRegistered` when a list exists, and `txRegistryUnavailable` when no list exists and the server isn't built in;
  - a built-in server is allowed only while no list exists;
  - once a registry list exists, built-in entries missing from it are refused;
  - `defaultServer(R4)` is `http://tx.fhir.org/r4` only when allowlisted.
- [X] T028 [P] [US2] Create `test/core/terminology/NetworkGuardTest.java`:
  - the DNS resolver refuses `127.0.0.1`, `10.0.0.5`, `192.168.1.1`, `169.254.169.254`, `::1` and `fc00::1`, and a host that resolves to a public and a private address together;
  - the production guard has no loopback exemption;
  - `NetworkGuard.forTests()` allows loopback only.
- [X] T029 [P] [US2] Create `test/core/terminology/TxRegistryIT.java` (Testcontainers + `StubTxRegistry`):
  - start → the list is loaded for each enabled version, and a `tx_registry_snapshot` row is written;
  - a stub failure at refresh keeps the previous list;
  - a new instance with the stub down loads `source = stored-registry` from the table;
  - an empty table with the stub down gives `source = built-in`;
  - an older `fetched_at` never overwrites a newer row;
  - the HTML answer and the `Accept` header are handled;
  - the refresh timer honours `FV_TX_REGISTRY_REFRESH_MS`.
- [X] T030 [P] [US2] Extend `test/web/endpoint/HealthApiTest.java`: `/health` detail contains `terminology.registry = "https://ecosystem.hl7.org/tx-reg"` and `allowlist.{version}.{source, fetchedAt, registryLastUpdate, servers}` (plus `reason` when built-in or empty), as in `contracts/http-api.md` §5. Readiness is unaffected by the allowlist state.
- [X] T031 [P] [US2] Create `test/core/terminology/ProductionWiringTest.java`: the `TerminologyServices` built by `ServiceRegistry` uses `TxRegistry.OFFICIAL_BASE = "https://ecosystem.hl7.org/tx-reg"` and the strict `NetworkGuard`, checked by reflection. No `ConfigLoader` entry or environment variable can change the registry address.

### Implementation for User Story 2

- [X] T032 [P] [US2] Create `main/core/terminology/TxServerEntry.java`: record `(String url, SupportedFhirVersion fhirVersion, String serverCode, List<String> authoritative)`, with `static Optional<TxServerEntry> fromRegistry(JsonObject)`. It keeps an entry only when `error == null`, `security == "open"`, the FHIR version maps by major.minor, and `url` is present and is http(s). URLs are normalised with `TerminologySettings.normaliseUrl`.
- [X] T033 [P] [US2] Create `main/core/terminology/BuiltInTxServer.java`: enum `TX_FHIR_ORG_R4("http://tx.fhir.org/r4", R4)` and `TX_FHIR_ORG_R5("http://tx.fhir.org/r5", R5)`, with `forVersion(SupportedFhirVersion)`. Javadoc: fixed in the release, used only while no registry list was ever obtained (FR-012).
- [X] T034 [US2] Complete `main/core/terminology/TxServerAllowlist.java`:
  - immutable `Map<SupportedFhirVersion, VersionList>`, where `VersionList(Map<String, TxServerEntry> entries, Source source, Instant fetchedAt, Instant registryLastUpdate)` and `Source { REGISTRY, STORED_REGISTRY, BUILT_IN }`;
  - `isAllowed(url, version)`, `require(url, version)` (throwing the T013 problems as in data-model §6), `defaultServer(version)`, `withVersion(version, VersionList)`, `status()` (the JSON for health), and `EMPTY`.

  Makes T027 pass.
- [X] T035 [US2] Create `main/core/terminology/NetworkGuard.java`:
  - an Apache `DnsResolver` that resolves with `InetAddress.getAllByName` and throws `UnknownHostException("non-public address")` if any address is non-public, by calling `SafePackageFetcher.isNonPublic`;
  - make `isNonPublic` package-visible to `core.terminology` through a public static delegate in `main/core/npm/SafePackageFetcher.java`, and extend it to IPv6 unique-local (`fc00::/7`) if it isn't covered;
  - `static NetworkGuard strict()` and a package-private `forTests()` that allows loopback.

  Makes T028 pass.
- [X] T036 [US2] Create `main/core/terminology/TxRegistry.java`:
  - **Address:** `public static final String OFFICIAL_BASE = "https://ecosystem.hl7.org/tx-reg"`; a package-private constructor taking `(Vertx, WebClient, Pool, String base, NetworkGuard, long refreshMs)` for tests, and a public factory that uses `OFFICIAL_BASE` only.
  - **`start()`:** loads `tx_registry_snapshot` rows (source `STORED_REGISTRY`), sets `BUILT_IN` for versions with no row, then fetches every enabled version.
  - **Fetch:** sequential, `GET {base}/?fhirVersion={R3|R4|R4B|R5}` with `Accept: application/json`, a timeout of `FV_TX_TIMEOUT_MS`, and no redirects (`WebClientOptions.setFollowRedirects(false)`).
  - **On success:** swap the allowlist version (`REGISTRY`) and upsert the row with `ON CONFLICT (fhir_version) DO UPDATE ... WHERE tx_registry_snapshot.fetched_at < EXCLUDED.fetched_at`.
  - **On failure:** keep the previous list and log a WARN with the request-free context.
  - **Refresh:** a periodic timer every `FV_TX_REGISTRY_REFRESH_MS`; `stop()` cancels it.
  - **`current()`:** returns the allowlist.
  - Everything returns `Future`; no blocking.

  Makes T029 pass.
- [X] T037 [US2] Wire `TxRegistry` in `main/ServiceRegistry.java`: create it with `OFFICIAL_BASE` and `NetworkGuard.strict()`, start it after Flyway (not blocking readiness), stop it on shutdown, and put it into the `TerminologyServices` passed to `SnapshotBuilder`. Makes T031 pass.
- [X] T038 [US2] Extend `main/core/service/HealthService.java` with the `terminology` object (`contracts/http-api.md` §5), from `TxRegistry.current().status()`, keeping readiness unchanged. Makes T030 pass.

**Checkpoint**: The allowlist is loaded, stored, reported and enforceable. It is not yet used by contexts.

---

## Phase 5: User Story 4 - Changing terminology settings rebuilds the context (Priority: P2)

**Goal**: Set the setting on include and change it with PATCH (owner or system only). It is checked against the allowlist, stored, propagated with `IG_TERMINOLOGY`, and part of the build identity, so every instance rebuilds (FR-001 to FR-005, FR-010a, FR-012a, research R12).

**Independent Test**: Include an IG with `ecosystem`, then PATCH to `none`, and observe the stored row, the `IG_TERMINOLOGY` notification, a rebuild on two instances within the propagation window, and the setting in health. An unauthorised PATCH → 404 (US4 scenarios 1–4, SC-006).

### Tests for User Story 4 ⚠️

- [X] T039 [P] [US4] Create `test/web/TerminologySettingIT.java` (Testcontainers + two `AppHarness` instances + `StubTxRegistry`). It covers:
  - include with `{"mode":"server","server":"<listed>"}` → 200 and `terminology` echoed;
  - include with an unlisted server → 400 `tx-server-not-registered` with the `registry` extension, and nothing is committed;
  - include with no list ever obtained and a non-built-in server → 503 `tx-registry-unavailable` with `Retry-After: 60`; with `http://tx.fhir.org/r4` → 200;
  - mode `none` is always accepted;
  - include of an already-included context with `terminology` → 200, the unchanged setting, and the warning text from contracts §2;
  - PATCH `{"terminology":{"mode":"ecosystem"}}` by the owner → 200, revision +1; the second instance's context for that key is rebuilt within the propagation window (its snapshot `revision` changes) and the old one serves meanwhile;
  - PATCH with the same value → 200, no revision bump;
  - PATCH by another organisation → 404; by the system organisation → 200;
  - PATCH with an unknown field → 400;
  - PATCH check order 404 → 403 → 400 → `tx-server-not-registered` → `tx-registry-unavailable`;
  - STU3 with a list present and any server → 400 `tx-server-not-registered`;
  - the health detail shows `terminology` per context.
- [X] T040 [P] [US4] Extend `test/web/endpoint/IgPackageApiContractTest.java`: the include and PATCH requests and responses with `terminology` validate against `openapi.yaml`, and the 400 and 503 problem bodies match.
- [X] T041 [P] [US4] Extend `test/core/validator/ConfigPropagationIT.java`: an `IG_TERMINOLOGY` notification triggers `runSync` → `applyPinned`, and with notifications disabled the poll path still applies the change.

### Implementation for User Story 4

- [X] T042 [US4] Extend `main/core/service/ConfigRevisionRepository.java`:
  - `SELECT` reads `s.tx_mode, s.tx_server`;
  - `ConfigRow` gains `Map<String, TerminologySettings> terminology` (a missing row → `NONE`);
  - add `KIND_IG_TERMINOLOGY = "IG_TERMINOLOGY"`;
  - `updateIgSet(..., TerminologySettings tx)` inserts the new row with `tx_mode`/`tx_server`;
  - add `changeTerminology(SqlClient tx, SupportedFhirVersion, String key, TerminologySettings)`: an upsert like `changeSnapshotAccess`, then revision +1 and `notify(..., KIND_IG_TERMINOLOGY, key)`.
- [X] T043 [US4] Change `main/core/validator/ConfigSync.java`: `resolveAll` attaches `row.terminology().getOrDefault(key, NONE)` through `ResolvedContext.withTerminology`, and `onNotification` treats `IG_TERMINOLOGY` like `IG` (a version sync). Update the class javadoc list. Makes T041 pass.
- [X] T044 [US4] Extend `main/core/security/AccessChange.java`: the body may carry `terminology` (parsed with `TerminologySettings.parse`). The invalid-body text becomes "Give shareable, owner, terminology or any of them". The 404 → 403 → 400 order is unchanged.
- [X] T045 [US4] Extend `main/core/service/IgPackageService.java`:
  - **`include(...)`** takes `Optional<TerminologySettings>`. For a new context it checks `txAllowlist.require(server, version)` (skipped for `NONE`) after the existing include checks and before the 413 heap check and commit, then passes the setting to `updateIgSet`. For an existing context it adds the warning "terminology is ignored for an already included context; change it with PATCH /{version}/include-ig".
  - **The response** includes `terminology`.
  - **`changeSnapshotAccess(...)`** applies `change.terminology()`: allowlist check, then `changeTerminology` when it differs from the stored value, inside the same transaction and lock. The response includes `terminology`.
  - **Constructor:** `IgPackageService` receives the `TxRegistry` (or an allowlist supplier) from `ServiceRegistry`.
- [X] T046 [US4] Change `main/web/endpoint/ValidationApi.java`:
  - `handleIncludeImplementationGuide` reads an optional `terminology` object (`TerminologySettings.parse`; a 400 problem on error) and passes it to `include`;
  - `handleChangeIncludedIgAccess` already passes the body through `AccessChange.parse`, so no change is needed beyond what T044 adds.

  Makes T039 and T040 pass.
- [X] T047 [US4] Show the setting in status: `ValidatorRegistry.details()` in `main/core/validator/ValidatorRegistry.java` adds `terminology` (`{mode, server?}`) per context, from the slot's active `ResolvedContext`.

**Checkpoint**: Settings round-trip and propagate. Contexts in `server` or `ecosystem` mode build only once US3 and US1 land (until then those branches throw, so T039 asserts on settings and rebuild, not on outcomes).

---

## Phase 6: User Story 3 - One fixed terminology server per context (Priority: P2)

**Goal**: Mode `server` sends every non-local code check to the context's allowlisted server. This phase builds the shared pieces: pooled guarded clients, the terminology-only wrapper, IG-aware parameters, the request budget, degraded results and cache clearing (FR-013 to FR-015, FR-018 to FR-020, research R2 to R7, R11).

**Independent Test**: A context in server mode with `StubTerminologyServer`: every external code goes to that stub only; local and UCUM codes never do; the IG's ValueSet and CodeSystem are sent; the stub down, slow or redirecting gives warnings and a 200; the budget stops calls (US3 scenarios 1–3, SC-004, SC-005, SC-005a).

### Tests for User Story 3 ⚠️

- [X] T048 [P] [US3] Create `test/core/terminology/TerminologyOnlySupportTest.java` (pure unit; Mockito allowed): the wrapper forwards only `isCodeSystemSupported`, `isValueSetSupported`, `validateCode`, `validateCodeInValueSet` and `lookupCode`. Every `fetch*`, `expandValueSet`, `translateConcept` and `fetchAll*` returns null or empty without touching the delegate (research R2).
- [X] T049 [P] [US3] Create `test/core/terminology/CircuitBreakerTest.java`: opens after 5 consecutive failures, stays open 60 s (injectable clock), half-open lets one trial through, and a success closes it.
- [X] T050 [P] [US3] Create `test/core/terminology/TerminologyBudgetTest.java`: `open(ms)`/`close()` on a thread; `charge(elapsedMs)` reduces `remainingMs()`, while time passing without a charge does **not** (a test sleeps-free by using an injectable clock and asserts that 5 s of uncharged time leaves the budget whole); `exhausted()` once charges reach the budget; the degraded flag is set by `markDegraded()`; nested opens are not allowed; a warm-up budget of 0 means no calls.
- [X] T051 [P] [US3] Create `test/web/TerminologyRoutingIT.java`, server-mode cases (the ecosystem cases are added in US1). Use a context on `test.tx.ig#1.0.0` in mode `server` pointing to `StubTerminologyServer`, allowlisted through `StubTxRegistry`, with the test-only guard. It covers:
  - `observation-loinc-invalid.json` → 200 with an `error` issue from the stub;
  - `observation-loinc-valid.json` → no error;
  - repeating it within the TTL gives 0 extra stub hits (SC-004);
  - `observation-ucum-only.json` and a code from the IG's complete local CodeSystem → 0 stub hits (FR-013; research R4 verification);
  - a code bound to `ValueSet/mixed` → the stub request body contains a `valueSet` parameter with the IG's ValueSet and a `tx-resource` with the local CodeSystem (research R3, US1 scenario 5);
  - `/metadata` hits = 0;
  - no `fetch` of a StructureDefinition, ValueSet or CodeSystem reaches the stub (FR-014).
- [X] T052 [P] [US3] Create `test/web/TerminologyFailureIT.java`. Each case asserts severity `warning`, never `error`, for the affected code, and HTTP 200 (research R7):
  - **stub down** → message "Terminology server unavailable; code {system}#{code} was not checked", within `FV_TX_TIMEOUT_MS` plus normal time (SC-005);
  - **5 failures** open the circuit, so the 6th request makes no stub hit;
  - **stub answering 302** → the redirect is not followed (the second stub gets 0 hits) and the result is a warning (FR-011);
  - **`bundle-500-loinc.json`** with a 50 ms stub delay and `FV_TX_REQUEST_BUDGET_MS=1000` → stub hits stop once the budget is spent; the remaining codes carry "Terminology time budget for this request was used up…"; the total wait is at most budget + one timeout (SC-005a);
  - **after the outage ends**, the same request is checked again (not served the cached warning: research R6);
  - **the server removed from the allowlist** after the build → "No permitted terminology server…" and 0 hits (US4 scenario 4);
  - **a context rebuilt while its server is not allowlisted** (the `DeniedTerminologySupport` path of T058) → the exact text "No permitted terminology server for http://loinc.org; code was not checked" at severity `warning`, 0 hits, and HTTP 200.

### Implementation for User Story 3

- [X] T053 [P] [US3] Create `main/core/terminology/TerminologyBudget.java`: a thread-local holder with `open(long ms)`, `close()`, `charge(long elapsedMs)`, `remainingMs()` (= budget − sum of charges; never a wall-clock deadline: research R5), `exhausted()`, `markDegraded()`, `degraded()` and `markBudgetExhausted()`. Makes T050 pass.
- [X] T054 [P] [US3] Create `main/core/terminology/CircuitBreaker.java`: a per-server, thread-safe class with fixed threshold 5 and open time 60 s, and an injectable `LongSupplier` clock. Makes T049 pass.
- [X] T055 [P] [US3] Create `main/core/terminology/TerminologyOnlySupport.java`: `implements IValidationSupport`, delegating exactly the five operations of research R2, with `getFhirContext()` delegated. Makes T048 pass.
- [X] T056 [US3] Create `main/core/terminology/IgAwareRemoteTerminologySupport.java`, which `extends RemoteTerminologyServiceValidationSupport`:
  - the constructor is `(FhirContext, String baseUrl, IRestfulClientFactory)`;
  - it overrides the protected `buildValidateCodeInputParameters(...)`: it calls super, then, when the `valueSet` argument is a ValueSet resource, adds one `tx-resource` parameter per `compose.include.system` whose CodeSystem is defined by the context's packages (not core). The CodeSystems are looked up through a `Function<String, IBaseResource>` supplied by the snapshot's package support (research R3).
- [X] T057 [US3] Complete `main/core/terminology/TerminologyClients.java`. It is a pool keyed by `(normalised url, SupportedFhirVersion)`, plus `NONE`. Each new client gets its own `ApacheRestfulClientFactory(ctx)` with:
  - `setConnectTimeout` and `setSocketTimeout` = `FV_TX_TIMEOUT_MS`;
  - `setServerValidationMode(ServerValidationModeEnum.NEVER)`;
  - `setHttpClient(...)` built with `HttpClientBuilder.create().disableRedirectHandling().setDnsResolver(networkGuard).setMaxConnPerRoute(FV_TX_MAX_CONNECTIONS_PER_SERVER)`;
  - an interceptor that lowers the connect and socket timeouts to `min(FV_TX_TIMEOUT_MS, TerminologyBudget.remainingMs())` per request.

  `call(url, version, Supplier<T>)` does the following:
  - checks `allowlist.isAllowed` first (`refused`), then the circuit (`circuit_open`), then the budget (`budget_exhausted`);
  - runs the call and `TerminologyBudget.charge(elapsedMs)` in `finally` (success or failure); on exception it records `failure` or `timeout`, opens the circuit as needed, and returns a degraded `CodeValidationResult` with the fixed texts of research R7 and `TerminologyBudget.markDegraded()`.

  `forServer(ctx, version, url, packageCodeSystems)` returns a `TerminologyOnlySupport` over a module that routes every call through `call(...)`.
- [X] T058 [US3] Implement the `SERVER` branch in `SnapshotBuilder.assemble` (`main/core/validator/SnapshotBuilder.java`):
  - call `txServices.allowlist().current().require(tx.server(), version)`; on failure, log a WARN and add, at the same chain position, a new `main/core/terminology/DeniedTerminologySupport.java` (wrapped in `TerminologyOnlySupport`). It applies the same local decline rule (research R4) and answers every other code with the research R7 warning result "No permitted terminology server for {system}; code was not checked", marking the request degraded, and it makes no network call (US4 scenario 4: build fails closed for terminology only);
  - otherwise add `txServices.clients().forServer(...)` after `CommonCodeSystemsTerminologyService` and before `SnapshotGeneratingValidationSupport` (research R4).

  Build the chain with `CacheConfiguration.defaultValues().setCacheSize(FV_TX_CACHE_SIZE).setCacheTimeout(Duration.ofMillis(FV_TX_CACHE_TTL_MS))`.
- [X] T059 [US3] Change `main/core/validator/ValidatorSnapshot.java`:
  - hold the `TerminologySettings`;
  - `validate(...)` wraps the HAPI call in `TerminologyBudget.open(tx.mode() == NONE ? 0 : requestBudgetMs)` / `close()` in `finally`;
  - after the call, if `TerminologyBudget.degraded()`, call `chain.invalidateExpiringCaches()` (research R6);
  - the warm-up validation opens a budget of 0;
  - **conditional (research R7 fallback):** if T052 shows HAPI 8.12.1 turns a code-less warning result into an `error` for required bindings, switch the degraded results in `TerminologyClients`, `EcosystemRoutingTerminologySupport` and `DeniedTerminologySupport` to return `null`, and add a post-processing step in `ValidatorSnapshot.validate` that appends the research R7 message as an `information` issue at the same location. Record which branch was taken in research R7.

  Makes T051 and T052 pass.
- [X] T060 [US3] Complete the `TerminologyServices` construction in `main/ServiceRegistry.java`: the real `TerminologyClients` with config values and the strict `NetworkGuard`, `chainCache` from `FV_TX_CACHE_*`, and `requestBudgetMs`, passed to `SnapshotBuilder`.
- [X] T061 [US3] Add the `HeapBudget` estimate in `main/core/validator/HeapBudget.java`: a context not in mode `none` adds `FV_TX_CACHE_SIZE × 1 KB` to its estimate (research R15). Extend `test/core/validator/HeapBudgetTest.java` with the SC-009 bound: at the default `FV_TX_CACHE_SIZE` (5000), the estimate of the same context in mode `server` or `ecosystem` exceeds its mode-`none` estimate by exactly 5000 × 1 KB (about 5 MB, the SC-009 per-context bound), and mode `none` and the base context add 0.

**Checkpoint**: Server mode works end to end. The shared client, budget and degradation behaviour is in place for US1.

---

## Phase 7: User Story 1 - Validate codes from external code systems through the registry (Priority: P1)

**Goal**: Mode `ecosystem` asks the registry's `/resolve` which server handles each code system, chooses one with the deterministic order of research R9, replaces `tx.fhir.org` with the context's own server when set, and caches routing decisions (24 h success, 5 min failure) (FR-016, FR-017).

**Independent Test**: A context in ecosystem mode with the stub registry and two stub servers: LOINC is routed to the server the resolve fixture names; a second request makes no extra resolve or server hit; an authoritative entry wins; an unlisted candidate is skipped; a resolve outage falls back to the context server or the default (US1 scenarios 1–5, US3 scenario 2).

### Tests for User Story 1 ⚠️

- [X] T062 [P] [US1] Create `test/core/terminology/RegistryResolverTest.java` (with `StubTxRegistry`). It covers:
  - the choice order: authoritative first (`resolve-R4-authoritative.json`); then `complete` candidates, preferring the context server, then `tx.fhir.org`, then registry order; then `fragment`; `not-present`, `example` and `supplement` ignored; then the context server or default; then none;
  - candidates for another FHIR version or not `open` skipped;
  - `resolve-R4-unlisted.json`: a candidate missing from the allowlist is skipped;
  - `resolve-R4-format2.json`: treated as a failure;
  - caching: a success is cached 24 h and a failure 5 min (injectable `Ticker`); at most 10 000 entries;
  - the context server replaces `tx.fhir.org` in answers;
  - the resolve request uses `Accept: application/json` and `usage=validation`, and passes `system|version` when the code has a version.
- [X] T063 [P] [US1] Extend `test/web/TerminologyRoutingIT.java` with ecosystem cases:
  - LOINC routed to stub A (named in `resolve-R4-loinc.json`), stub B gets 0 hits, and the error is reported (US1 scenario 1);
  - the second validation makes 0 extra resolve hits and 0 server hits (US1 scenario 2);
  - a local or UCUM code → 0 resolve hits (US1 scenario 3);
  - resolve answers none → the context server is used; none set → `tx.fhir.org` (stub-mapped) if allowlisted; otherwise a "not checked" warning (US1 scenario 4);
  - the context server set to stub B → B is used in place of `tx.fhir.org` (US3 scenario 2);
  - `condition-snomed-invalid.json` routed per `resolve-R4-snomed.json` → an `error` issue; `condition-snomed-valid.json` → none (SC-001, stubbed);
  - registry `/resolve` down → the fallback rule, and a retry after the 5 min failure TTL (with an injected ticker).

### Implementation for User Story 1

- [X] T064 [US1] Complete `main/core/terminology/RegistryResolver.java`:
  - **Request:** `GET {TxRegistry base}/resolve?fhirVersion=..&url={system[|version]}&usage=validation` through a guarded **synchronous** Apache `HttpClient` (the `NetworkGuard` DNS resolver, `disableRedirectHandling()`, connect and read timeouts `min(FV_TX_TIMEOUT_MS, TerminologyBudget.remainingMs())`, `Accept: application/json`); each lookup is charged to the request budget. The Vert.x `WebClient` is not used here (research R9).
  - **Parsing:** require `formatVersion == "1"`; read `authoritative[]` and `candidates[]` as `{url, fhirVersion, security, content}`.
  - **Choice:** the order of research R9, checked against `allowlist.isAllowed`, with the context server replacing `TxServerAllowlist.defaultServer(version)`.
  - **Cache:** Caffeine with `Expiry` (success 24 h, failure 5 min), `maximumSize(10_000)`, and an injectable `Ticker`.
  - **Synchronous API:** the module calls it on the validation worker; a cache miss blocks on the HTTP call above, because the chain is synchronous and the constitution forbids `.get()`/`.join()` on Vert.x futures.
  - **`DISABLED`** returns none.

  Makes T062 pass.
- [X] T065 [US1] Create `main/core/terminology/EcosystemRoutingTerminologySupport.java`, which `implements IValidationSupport`. For the five operations of research R2:
  - **Decline rule (research R4):** decline (null or false) when the system is defined `complete` by a context package or is supported by `CommonCodeSystemsTerminologyService`, and for value sets whose included systems are all such.
  - **Otherwise:** choose the server through `RegistryResolver` for the code's system (for a value set with no code system, use the first non-local included system, else the context server or default), then delegate through `TerminologyClients.call(...)` to the pooled `IgAwareRemoteTerminologySupport`.
  - **No server chosen:** return the "No permitted terminology server for {system}; code was not checked" warning result and mark the request degraded.
  - **Module order (research R4 verification):** if the T051/T063 UCUM, local and LOINC case fails because `InMemoryTerminologyServerValidationSupport` answers an unknown-system error before the terminology module is asked, move the terminology module (server, ecosystem and denied alike) to just before `InMemoryTerminologyServerValidationSupport` in `SnapshotBuilder.assemble`. The decline rule keeps that order safe. Write the observed result under "Verification result" in research R4.
- [X] T066 [US1] Implement the `ECOSYSTEM` branch in `SnapshotBuilder.assemble` (`main/core/validator/SnapshotBuilder.java`): `require(tx.server())` only when it is set (same fail-closed handling as T058), then add `new TerminologyOnlySupport(new EcosystemRoutingTerminologySupport(...))` at the research R4 position. Apply the same local decline rule to the `SERVER` module from T057, so both modes share it. Makes T063 pass.
- [X] T067 [US1] Complete the wiring in `main/ServiceRegistry.java`: the real `RegistryResolver` in `TerminologyServices`.

**Checkpoint**: Every user story works. The full quickstart §1 suite is green.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T068 [P] Add the metrics of `contracts/http-api.md` §6 in `main/core/metrics/ServiceMetrics.java`:
  - `fv_tx_calls_total{server,fhir_version,outcome}`;
  - `fv_tx_call_seconds`;
  - `fv_tx_routing_lookups_total{fhir_version,result}`;
  - `fv_tx_code_cache_hits_total`;
  - `fv_tx_allowlist_age_seconds{fhir_version,source}`.

  Record them from `TerminologyClients`, `RegistryResolver` and `TxRegistry`. Labels never carry codes, value set URLs or IG names. Extend `test/web/ObservabilityIT.java` to assert they exist and that the label values are safe.
- [X] T069 [P] Add DEBUG-only logging of client-supplied systems and codes in `EcosystemRoutingTerminologySupport` and `TerminologyClients`, with the request id via contextual logging (FR-026). Extend `test/core/config/NoSecretsInLogsTest.java` or `LogCapture` assertions: no code or system appears at INFO or higher.
- [X] T070 [P] Extend the existing zero-outbound SC-002 test (in `test/web/IgScopedValidationIT.java`, or wherever the spec 003 SC-002 assertion lives; `grep -rn "SC-002" src/test`) to cover a context in mode `none` and the base context while a terminology context exists: 0 connections to any stub (SC-003).
- [ ] T071 [P] Create `perf/validate-terminology.js` (k6): validate `observation-loinc-valid.json` against an ecosystem-mode context backed by a stub with 100 ms latency, reporting p95 cached vs. uncached. Document running it in `perf/README.md`. Run `perf/validate.js` before and after for mode `none`, and record both results for the PR (constitution VI).
  - **Status (2026-11-09):** `perf/validate-terminology.js` and the README section are written. **Open:** the k6 runs (k6 isn't installed in the implementation environment); run `perf/validate.js` on 3.2.1 and 4.0.0 and `perf/validate-terminology.js`, and attach the numbers to the PR.
- [X] T072 [P] Amend `.specify/memory/constitution.md`, Security & Compliance: add "Outbound terminology calls MUST be restricted to open servers listed by the official HL7 terminology registry (or the built-in fallback list while no registry list exists), on public addresses, without redirects; validated resources are never sent." Bump the version 1.1.1 → 1.2.0 and set Last Amended to the merge date. As the Governance section requires, add a Sync Impact note at the top of the file (HTML comment, as in earlier amendments if present) listing the version change, the changed section, and the dependent docs:
  - `README.md` (T074);
  - `specs/003-ig-only-profile-resolution/spec.md` and `specs/004-ig-scoped-validation/spec.md` (T073);
  - `.specify/templates/plan-template.md` (Constitution Check: no change needed, gates are read from the constitution);
  - `CHANGELOG.md` (T075).
- [X] T073 [P] Add amendment notes:
  - in `specs/003-ig-only-profile-resolution/spec.md`: SC-002 holds for mode `none` and the base context; FR-002 is unchanged (profiles are still never fetched); see spec 007 FR-020;
  - in `specs/004-ig-scoped-validation/spec.md`: FR-010 to FR-012 and FR-014 to FR-016 are superseded by spec 007 FR-021; the on-demand parts of FR-017 (424 and 503 for a failed on-demand preparation) and FR-018 ("being prepared" state) are superseded by spec 007 FR-021, while their included-context parts still apply; FR-013 is amended by spec 007 FR-020;
  - in `specs/005-organisation-ownership/spec.md`: FR-013 (access check before an on-demand build is admitted) is superseded by spec 007 FR-021, since there are no on-demand builds; the access check before serving still applies. In FR-015, the words "on-demand builds" no longer apply.
- [X] T074 [P] Update `README.md`:
  - **New "Terminology servers" section:** the modes, the strict registry rule, the built-in fallback, plain-HTTP registry entries (research R8), STU3 and R4B having no servers, the `FV_TX_*` table, health, and metrics.
  - **Validation section:** remove the on-demand text; `?ig=` needs an included IG.
  - **Configuration table:** remove `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS`.
- [X] T075 Update `CHANGELOG.md` with a 4.0.0 entry and an upgrade note:
  - **breaking:** `?ig=` for a not-included IG answers 400; `latest` resolves among included versions; the `FV_IG_*` on-demand settings are ignored;
  - **new:** terminology per context, `PATCH` terminology, health and metrics;
  - **operational:** outbound access to `ecosystem.hl7.org` and the listed servers is needed for terminology; validation workers may need raising.

  Set the `pom.xml` version to `4.0.0` at release.
- [X] T076 Fix the stale note in `specs/007-terminology-routing/checklists/requirements.md`: the not-included answer is 400 `invalid-request`, not `ig-not-included`/404.
- [ ] T077 Run every quickstart.md §1 test and `./mvnw verify`. Confirm there are no `BlockedThreadChecker` warnings in the test logs (constitution I). Then do the manual §2 run against the real registry and `tx.fhir.org/r4`, and compare the LOINC (`observation-loinc-invalid.json`) and SNOMED CT (`condition-snomed-invalid.json`) results with `validator_cli.jar` (SC-001). Record the k6 numbers against SC-008 and the heap delta of one ecosystem-mode context against SC-009.
  - **Status (2026-11-09):** `./mvnw verify` passes (610 tests, 0 failures, no BlockedThreadChecker warnings, no test contacted the real registry). A live check against `https://ecosystem.hl7.org/tx-reg` and `tx.fhir.org/r4` passed: 14 open R4 servers listed; LOINC and SNOMED CT routed to tx.fhir.org; `9999-9` and SNOMED `99999999999` reported as errors ("Unknown code …"), the valid codes with no issue. **Open:** the comparison with `validator_cli.jar` (not available here) and the docker-based quickstart §2 run.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies.
- **Foundational (Phase 2)**: depends on Setup and blocks all stories.
- **US5 (Phase 3, P1)**: depends only on Foundational. It is independent of every terminology story.
- **US2 (Phase 4, P1)**: depends on Foundational. It can run in parallel with US5 (different files except `ServiceRegistry.java`; merge T025 and T037 carefully).
- **US4 (Phase 5, P2)**: depends on US2 (the allowlist checks at save time).
- **US3 (Phase 6, P2)**: depends on US2 (the allowlist at call time) and US4 (settings reach the build).
- **US1 (Phase 7, P1)**: depends on US2, US3 (clients, budget, wrapper) and US4.
- **Polish (Phase 8)**: after the stories it touches. T072 to T074 can start any time after Phase 2.

### Why US1 (P1) comes last

Ecosystem routing reuses the allowlist (US2), settings (US4), and the pooled guarded clients, budget and degradation (US3). Building them first, each with its own tests, keeps US1 to the resolver and the routing module.

### Within Each User Story

- Tests are written first and must fail.
- Value types come before services, services before builders and wiring, and wiring before endpoints.
- A story is complete at its checkpoint before the next dependent story starts.

### Parallel Opportunities

- **Setup:** T002 to T005 together.
- **Foundational:** T007, T009, T011, T012 and T013 together. Then T008, T010 and T014; T015 and T016 last.
- **US5:** T017 to T019 together. Then T020 to T025 are mostly sequential (shared registry types); T026 last.
- **US2:** T027 to T031 together; T032 and T033 together; then T034 → T035 → T036 → T037 → T038.
- **US4:** T039 to T041 together; then T042 → T043; T044 and T047 can run in parallel with T042 and T043; then T045 → T046.
- **US3:** T048 to T052 together; T053 to T055 together; then T056 → T057 → T058 → T059 → T060; T061 in parallel.
- **US1:** T062 and T063 together; then T064 → T065 → T066 → T067.
- **Polish:** T068 to T074 together.

---

## Parallel Example: User Story 2

```bash
# Tests first, together:
Task: "TxServerAllowlistTest in src/test/java/nzi/fhir/validator/core/terminology/TxServerAllowlistTest.java"
Task: "NetworkGuardTest in src/test/java/nzi/fhir/validator/core/terminology/NetworkGuardTest.java"
Task: "TxRegistryIT in src/test/java/nzi/fhir/validator/core/terminology/TxRegistryIT.java"
Task: "HealthApiTest terminology section in src/test/java/nzi/fhir/validator/web/endpoint/HealthApiTest.java"

# Then the independent value types, together:
Task: "TxServerEntry in src/main/java/nzi/fhir/validator/core/terminology/TxServerEntry.java"
Task: "BuiltInTxServer in src/main/java/nzi/fhir/validator/core/terminology/BuiltInTxServer.java"
```

## Parallel Example: User Story 3

```bash
Task: "TerminologyOnlySupportTest in src/test/java/nzi/fhir/validator/core/terminology/TerminologyOnlySupportTest.java"
Task: "CircuitBreakerTest in src/test/java/nzi/fhir/validator/core/terminology/CircuitBreakerTest.java"
Task: "TerminologyBudgetTest in src/test/java/nzi/fhir/validator/core/terminology/TerminologyBudgetTest.java"
Task: "TerminologyRoutingIT (server mode) in src/test/java/nzi/fhir/validator/web/TerminologyRoutingIT.java"
Task: "TerminologyFailureIT in src/test/java/nzi/fhir/validator/web/TerminologyFailureIT.java"
```

---

## Implementation Strategy

### MVP First (User Story 5 only)

1. Phase 1 (Setup) and Phase 2 (Foundational): behaviour is unchanged and all tests stay green.
2. Phase 3 (US5): included-only validation.
3. **Stop and validate:** `IncludedOnlyValidationIT` plus the full suite. This is a coherent, releasable 4.0.0 step on its own, and it simplifies `ValidatorRegistry` for everything after it.

### Incremental Delivery

1. US5 → included-only contexts (breaking; 4.0.0 milestone).
2. US2 → the allowlist is loaded, stored and reported (no behaviour change for contexts yet).
3. US4 → settings can be stored and changed; contexts rebuild.
4. US3 → server mode delivers the first terminology checks (an allowlisted national server or `tx.fhir.org`).
5. US1 → ecosystem routing, the headline feature.
6. Polish → metrics, performance evidence, docs, constitution, changelog.

Each step ends at a green `./mvnw verify`.

---

## Implementation Notes (2026-11-09)

Deviations from the task text, found while implementing (also recorded in research.md):

- **T003–T005:** the 500-entry bundle and the test IG are built in code (`TestPackages.loincBundle`, `TestPackages.terminologyIg`) instead of fixture files; the hand-made registry fixtures use `{{BASE}}`/`{{BASE2}}`, and the recorded ones are rewritten by `StubTxRegistry.replace`.
- **T026, T052:** two `ObservabilityIT` tests and one `EventLoopResponsivenessIT` test were about on-demand builds and were removed with the behaviour; the include-time responsiveness test remains.
- **T029, T036:** the production registry client and resolver never fetch in the test JVM (`fv.tx.registry.offline`, set by surefire): this only removes network access, it never changes an address or allows a host.
- **T051, T063:** the routing ITs live in `core/validator` (`TerminologyRoutingIT`, `TerminologyFailureIT`, `EcosystemRoutingIT`) and build snapshots directly, with test-only infrastructure (`TestTerminology`) that allows loopback stubs.
- **T056 (research R3):** HAPI sends only the value set URL; `IgAwareRemoteTerminologySupport` also overrides `validateCodeInValueSet` to send the definition.
- **T057 (research R11):** HAPI builds every request through `FhirContext.getRestfulClientFactory()`; the guarded factory is installed there, with one guarded HTTP client for all servers.
- **T058, T065 (research R4):** the terminology module sits before `InMemoryTerminologyServerValidationSupport`.
- **T068:** `fv_tx_code_cache_hits_total` was not exported at first (HAPI's chain cache exposes no hit count); convergence task T078 added it by counting code checks the chain answers without reaching the terminology module. Routing-cache hits are exported as `fv_tx_routing_lookups_total{result="hit"}`.
- **T075:** the CHANGELOG entry is "4.0.0 - unreleased"; `pom.xml` stays `4.0.0-SNAPSHOT` until the release.

---

## Phase 9: Convergence

- [X] T078 Export a code-cache hit count for terminology contexts (`fv_tx_code_cache_hits_total{fhir_version}`) in `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java`, for example by counting the terminology checks HAPI makes for a terminology context and subtracting the calls that reach `RoutedTerminologySupport`; assert it in `src/test/java/nzi/fhir/validator/core/validator/TerminologyRoutingIT.java` (a repeated code raises it by one) per FR-026 (partial)
- [X] T079 Make the guarded client factory of `src/main/java/nzi/fhir/validator/core/terminology/TerminologyClients.java` independent of which pool installed last: install it once per `FhirContext` process-wide (or verify before each call that the context's factory is this pool's), and add a test in `src/test/java/nzi/fhir/validator/core/terminology/` with two pools on one `FhirContext` showing each call uses its own pool's guard per plan: research R11 (partial)
- [X] T080 Include a new context's code-cache bound in the include-time heap check: pass `HeapBudget.terminologyCacheBytes(settings, FV_TX_CACHE_SIZE)` through `IgPackageService.include` and `ServiceRegistry`'s `setAdmitBuild` into `src/main/java/nzi/fhir/validator/core/validator/HeapBudget.java` `admitInclude`, and extend `src/test/java/nzi/fhir/validator/core/validator/HeapBudgetTest.java` per SC-009 (partial)
- [X] T081 Update the test table in `specs/007-terminology-routing/quickstart.md` §1 to the real classes (`core/validator/TerminologyRoutingIT`, `core/validator/TerminologyFailureIT`, `core/validator/EcosystemRoutingIT`, `core/terminology/TxRegistryIT`, `web/TerminologySettingIT`, `core/validator/IncludedOnlyValidationIT`; the zero-outbound check is `EcosystemRoutingIT.modeNoneAndTheBaseContextStayOfflineNextToATerminologyContext`) per quickstart.md (partial)
