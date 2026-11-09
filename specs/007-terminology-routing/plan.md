# Implementation Plan: Terminology Servers per IG Context, with Ecosystem Routing, and Included-Only Validation Contexts

**Branch**: `007-terminology-routing` | **Date**: 2026-11-09 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/007-terminology-routing/spec.md`; technical outline
`var/terminology_routing_plan.md`

## Summary

Two changes ship together as **4.0.0**.

**1. Terminology per included context.**
- **The setting:** each included IG context gets a terminology setting (`none` by default, `server` or `ecosystem`).
  - It is stored on `fhir_validator_snapshots` (V9) and is part of the context's build identity, so changing it
    rebuilds the context through the existing `ConfigSync` → `applyPinned` path.
  - It is set with `POST /{version}/validator` and changed with `PATCH /{version}/validator` (named
    `/{version}/include-ig` until the pre-release rename in #67).
- **The allowlist:** the official HL7 registry is the only one, and it is fixed in code. A server may be contacted
  only when the registry lists it, open and healthy, for the snapshot's FHIR version.
  - It is checked on save, on build and before every call. Redirects are refused, and so are non-public addresses
    (checked in the DNS resolver).
  - The last good list is kept in `tx_registry_snapshot`.
  - A built-in enum (`tx.fhir.org` R4 and R5) is used only while no list has ever been obtained. Saving any other
    server in that state gives 503 `tx-registry-unavailable`.
- **The chain:**
  - Codes are first decided locally.
  - Otherwise a `TerminologyOnlySupport` module asks either the context's server, or the server the registry's
    `/resolve` names for the code system. It delegates to pooled HAPI `RemoteTerminologyServiceValidationSupport`
    clients, extended to send the IG's ValueSet and its CodeSystems (`tx-resource`).
  - Failures, an open circuit, or a spent per-request budget (10 s) become warnings, never errors, and are never
    served from the cache.

**2. Included-only validation.**
- The on-demand context path is removed from `ValidatorRegistry` (registry map, LRU eviction, build wait, build
  admission).
- `?ig=` naming a stored, visible, but not-included version is answered **400 `invalid-request`** (clarification
  Q1). `latest` resolves among included versions.
- `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS` are deprecated: they are logged as such and ignored.

## Technical Context

**Language/Version**: Java 17 (project toolchain; service version 3.2.1 → 4.0.0)

**Primary Dependencies**:
- Vert.x 4.5.35: core, web, web-openapi, pg-client, and web-client (already present; used only for the periodic
  registry discovery fetch). Routing lookups (`/resolve`) and server calls run inside the synchronous validation
  chain and use the guarded synchronous Apache HttpClient (research R9, R11).
- HAPI FHIR 8.12.1: validation, structures, caching-caffeine.
- **New: `ca.uhn.hapi.fhir:hapi-fhir-client` `${hapi-fhir.version}`**, needed at run time by
  `RemoteTerminologyServiceValidationSupport` (`IGenericClient`, `ApacheRestfulClientFactory`). See Complexity
  Tracking.

**Storage**: PostgreSQL through Flyway `V9__context_terminology.sql`:
- two columns on `fhir_validator_snapshots`;
- a new 4-row table `tx_registry_snapshot`.

**Testing**:
- JUnit 5 with `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`), and the `AppHarness` and
  `CoreHarness` test support.
- New: `StubTxRegistry` and `StubTerminologyServer` (Vert.x), plus recorded registry answers from 2026-11-09 (research
  R14).

**Target Platform**: Linux containers (x86_64 and arm64), horizontally scaled behind a load balancer

**Project Type**: Web service (single Maven module)

**Performance Goals**:
- Mode `none` and the base context: p95 unchanged (< 200 ms warm). The only change is one `ThreadLocal` set and
  clear per validation.
- Terminology modes:
  - a cached code adds about 0 ms;
  - an uncached code adds one round trip (typically 50-300 ms), bounded by `FV_TX_TIMEOUT_MS` (5 s) and by the
    10 s per-request budget.

**Constraints**:
- No profile, ValueSet or CodeSystem is ever fetched from the network (spec 003 FR-002 is kept).
- 0 outbound requests for mode `none` and the base context.
- No outbound request to anything but allowlisted, public, open servers (or the registry), with no redirects.

**Scale/Scope**:
- Up to about 100 included contexts per FHIR version.
- Allowlist under 50 servers per version.
- Routing cache bounded to 10 000 entries.
- Code cache per snapshot of 5 000 by default (about 5 MB).

## Constitution Check

*GATE: checked before Phase 0, and checked again after Phase 1 (results below reflect the final design).*

| Principle | Status | How the design complies |
|---|---|---|
| **I. Non-blocking event loop** | PASS | Terminology HTTP calls run inside HAPI validation, which already runs on the validation `WorkerExecutor` (`executeBlocking`). The calls block a worker, never the loop. Registry fetches use the non-blocking Vert.x `WebClient` on timers. No `.get()`/`.join()`. Saving a setting is a `Future` chain in `IgPackageService`. |
| **II. Stateless, horizontally scalable** | PASS | Settings live in PostgreSQL and propagate through the existing revision + `LISTEN/NOTIFY` (new kind `IG_TERMINOLOGY`) + poll. Rebuilds happen once per changed context per instance. The registry list is per instance and refreshed independently; the last good copy is shared in `tx_registry_snapshot`, and instances may differ for at most one refresh interval, which is safe because both come from the same registry and are enforced locally (R10). The routing and code caches are derived and rebuildable. |
| **III. FHIR conformance** | PASS | Results come from the servers' `$validate-code`, as with the reference validator in ecosystem mode. Unavailable or not-checked codes are reported as explicit warnings with fixed texts (matching the CLI), never silently dropped. The IG's ValueSet is sent, so membership follows the IG (Q2). Every supported FHIR version is handled explicitly: STU3 and R4B have no registry servers, so non-`none` modes are refused for them (R8). |
| **IV. Contract-first** | PASS | `openapi.yaml` changes first ([contracts/http-api.md](./contracts/http-api.md)). Validation status codes change for not-included IGs: a breaking change → **4.0.0** with a migration note. Errors stay problem documents with no internal messages. |
| **V. Test-first, real dependencies** | PASS | Persistence and migrations run on Testcontainers PostgreSQL. HTTP peers are real local Vert.x stub servers, not mocks. Mockito only in pure unit tests. Every new or changed endpoint has contract and IT coverage (quickstart §1). |
| **VI. Performance budgets** | PASS | Latency and memory are stated above and in research R15. Every new size and timeout is bounded in `ConfigLoader` (data-model §5). The hot path changes, so the PR carries `perf/validate.js` before/after results and a new `perf/validate-terminology.js`. |
| **VII. Observability** | PASS | New metrics `fv_tx_*` (contracts §6). `/health` detail shows allowlist source and age per version. Log lines carry the request id. Client codes appear in DEBUG only. No secrets: v1 has no server credentials. |
| **VIII. Simplicity, dependencies** | PASS with one justified addition | `hapi-fhir-client` is added; see Complexity Tracking. The circuit breaker is hand-written (about 40 lines), with no resilience library. The schema changes only through V9. |
| **Security & compliance** | PASS, with a **constitution amendment** | Today the text says only "Outbound package downloads MUST be restricted to configured package registries". This feature adds outbound terminology calls, so the Security section gains: "Outbound terminology calls MUST be restricted to open servers listed by the official HL7 terminology registry (or the built-in fallback list while no registry list exists), on public addresses, without redirects; validated resources are never sent." Version 1.1.1 → 1.2.0 (MINOR: section expanded). PHI: only codes, systems, displays and IG definitions leave the service. |

**Post-design re-check**: no violations. The single new dependency is justified below.

## Project Structure

### Documentation (this feature)

```text
specs/007-terminology-routing/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R15
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── http-api.md      # Phase 1: openapi.yaml changes, metrics, outbound calls
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── ServiceRegistry.java                      # CHANGE: build TerminologyServices; registry wiring without on-demand args
├── core/config/
│   ├── ApplicationConfig.java                # CHANGE: tx.* getters; remove ig.maxOnDemand / ig.buildWaitMs
│   └── ConfigLoader.java                     # CHANGE: FV_TX_* entries with bounds; DEPRECATED list with WARN
├── core/terminology/                         # NEW package
│   ├── TerminologyMode.java                  # enum NONE, SERVER, ECOSYSTEM
│   ├── TerminologySettings.java              # record + JSON parse/validate (data-model §1)
│   ├── TxServerEntry.java                    # kept registry entry + URL normalisation
│   ├── TxServerAllowlist.java                # immutable lists per version; isAllowed / require / defaultServer
│   ├── BuiltInTxServer.java                  # enum fallback list (tx.fhir.org r4, r5)
│   ├── TxRegistry.java                       # fixed address; discovery fetch (WebClient); refresh timer; table load/store
│   ├── RegistryResolver.java                 # /resolve over guarded sync HttpClient; Caffeine per-entry expiry; choice order (R9)
│   ├── NetworkGuard.java                     # public-address DnsResolver (uses SafePackageFetcher.isNonPublic)
│   ├── TerminologyClients.java               # pooled clients per (url, version); client factory; circuit breakers
│   ├── IgAwareRemoteTerminologySupport.java  # extends RemoteTerminologyServiceValidationSupport: tx-resource
│   ├── TerminologyOnlySupport.java           # exposes only the 5 terminology operations (R2)
│   ├── DeniedTerminologySupport.java         # answers non-local codes "No permitted terminology server" (fail closed)
│   ├── EcosystemRoutingTerminologySupport.java  # per-call server choice + local decline rule (R4)
│   ├── TerminologyBudget.java                # thread-local per-request budget + degraded flag (R5)
│   └── TerminologyServices.java              # process-wide bundle; OFFLINE constant
├── core/validator/
│   ├── SnapshotBuilder.java                  # CHANGE: assemble(..., TerminologySettings, TerminologyServices)
│   ├── ValidatorSnapshot.java                # CHANGE: budget around validate; clear expiring cache when degraded
│   ├── ResolvedContext.java                  # CHANGE: terminology component; sameAs; drop on-demand factory
│   ├── ConfigSync.java                       # CHANGE: attach settings in resolveAll; IG_TERMINOLOGY kind
│   ├── ValidatorRegistry.java                # CHANGE: remove on-demand map/eviction/wait; Kind {BASE, PINNED}
│   ├── HeapBudget.java                       # CHANGE: no evictor; add the code-cache bound to the estimate
│   └── IgReference.java                      # CHANGE: resolve among included keys; not-included → 400
├── core/service/
│   ├── ConfigRevisionRepository.java         # CHANGE: read tx columns; updateTerminology(); KIND_IG_TERMINOLOGY
│   ├── IgPackageService.java                 # CHANGE: include + PATCH accept terminology; allowlist checks
│   ├── FhirValidationService.java            # CHANGE: pass included keys to IgReference.resolve
│   └── HealthService.java                    # CHANGE: terminology section
├── core/security/AccessChange.java           # CHANGE: optional terminology field in the PATCH body
├── core/metrics/ServiceMetrics.java          # CHANGE: fv_tx_* metrics; drop on-demand series
├── core/npm/SafePackageFetcher.java          # CHANGE: isNonPublic made reusable through NetworkGuard
└── web/
    ├── ProblemException.java                 # CHANGE: igNotIncluded, txServerNotRegistered, txRegistryUnavailable
    └── endpoint/ValidationApi.java           # CHANGE: include/PATCH bodies pass terminology; docs only for validate

src/main/resources/
├── openapi.yaml                              # CHANGE (first): contracts/http-api.md
└── db/migration/V9__context_terminology.sql  # NEW

src/test/java/nzi/fhir/validator/
├── core/terminology/                         # NEW: TxServerAllowlistTest, RegistryResolverTest, NetworkGuardTest,
│                                             #      TerminologySettingsTest, TerminologyBudgetTest, CircuitBreakerTest
├── core/validator/IncludedOnlyValidationIT.java   # NEW (replaces OnDemandContextIT, OwnershipOnDemandIT, ContextEvictionIT)
├── web/TerminologyRoutingIT.java, TerminologyFailureIT.java, TerminologySettingIT.java  # NEW
├── testsupport/StubTxRegistry.java, StubTerminologyServer.java                         # NEW
└── (existing tests that relied on on-demand contexts: include the IG first)
src/test/resources/tx-reg/                    # NEW: recorded discovery (R3, R4, R4B, R5) and resolve answers
perf/validate-terminology.js                  # NEW

docs: README.md (terminology section; on-demand text removed), CHANGELOG.md (4.0.0 with upgrade note),
.specify/memory/constitution.md (Security amendment, 1.2.0), specs/003 and 004 amendment notes (SC-002, FR-002
wording; FR-010 to FR-018 superseded).
```

**Structure Decision**: Single Maven module, as before. All new terminology code sits in one new package,
`core/terminology/`, so the network-facing surface is in one reviewable place. The on-demand removal is confined to
`core/validator/`, `IgReference` and configuration.

## Implementation Order (for /speckit-tasks)

1. **Contract and schema first:**
   - the `openapi.yaml` changes;
   - `V9` and `MigrationUpgradeIT`;
   - the constitution amendment.
2. **Included-only validation (US5, P1):** remove on-demand from `ValidatorRegistry`, `IgReference`, the config
   and the metrics; add `IncludedOnlyValidationIT`; update the existing tests. This change is independent of
   terminology and lands first, so later steps build on a simpler registry.
3. **Allowlist (US2, P1):** `TxServerEntry`, `TxServerAllowlist`, `BuiltInTxServer`, `TxRegistry` (discovery and
   table), `NetworkGuard`, and health.
4. **Settings (US4, P2):** `TerminologySettings`; repository and `ConfigSync`; include and PATCH; the 400 and 503
   rules; propagation.
5. **Server mode (US3, P2):** `TerminologyClients`, `IgAwareRemoteTerminologySupport`, `TerminologyOnlySupport`,
   the `SnapshotBuilder.assemble` change, `TerminologyBudget`, and cache clearing.
6. **Ecosystem mode (US1, P1):** `RegistryResolver`, `EcosystemRoutingTerminologySupport`, and the local decline
   rule (R4 verification test first).
7. **Observability and performance:** metrics, `perf/validate-terminology.js`, before/after k6 runs.
8. **Docs:** README, CHANGELOG 4.0.0 upgrade note, spec 003 and 004 amendment notes.

## Complexity Tracking

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| New dependency `hapi-fhir-client` (same HAPI version property; it uses the Apache HttpClient 4.5.14 already present) | `RemoteTerminologyServiceValidationSupport` needs `IGenericClient` at run time; it is HAPI's maintained `$validate-code`/`$lookup` client and answer parser, including issue and display-mismatch mapping. | Our own client on the Vert.x `WebClient`: the validation chain is synchronous, so waiting for an async answer on the worker would need blocking `.get()`/`.join()` (forbidden by Principle I), and it would re-implement HAPI's answer parsing. A hand-written client on the existing Apache HttpClient: duplicates HAPI's parameter building and result mapping, which is a larger, riskier surface than one first-party HAPI module. |
| Hand-written circuit breaker (about 40 lines) | FR-019: skip a failing server for a cool-down period. | A resilience library (Resilience4j or Vert.x circuit-breaker) is a new dependency for one counter and a timestamp. |
| Clearing the chain's expiring cache after degraded validations | HAPI caches `validateCode` results, including degraded ones (R6). | Turning off the chain cache would lose fetch caching on the hot path. A second result cache inside the module would duplicate HAPI's. |
