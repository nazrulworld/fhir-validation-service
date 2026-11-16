# Research: Shared Package Definitions and Per-Request Terminology Settings

**Feature**: `010-shared-packages-request-tx` | **Date**: 2026-11-16

Sources: `var/shared_package_resources_research.md`, `var/terminology_decoupling_plan.md`, the code on `main`
(1.0.0) and the HAPI 8.12.1 sources (`hapi-fhir-validation-8.12.1-sources.jar`). File references are to `main`.

---

## R1. Where the per-request setting lives during validation

**Decision**: a request-scoped `TerminologyScope` held in a `ThreadLocal`, opened next to `TerminologyBudget` in
`ValidatorSnapshot.validate` and closed in `finally`. One `RequestScopedTerminologySupport` module per snapshot
reads it on every call.

**Rationale**: HAPI validates synchronously on the `fv-validation` worker that runs `snapshot.validate`. The service
already relies on this for `TerminologyBudget.CURRENT`, `TerminologyClients.CURRENT` and
`RoutedTerminologySupport.REACHED`. `RoutedTerminologySupport` has no definition state: it only needs `route(system)`
and reads package CodeSystems and ValueSets through `vsc.getRootValidationSupport()`. So the scoped module subclasses
it and delegates `route()` to the scope.

**Alternatives considered**:
- One chain and validator per (snapshot, setting), created lazily. It keeps HAPI's chain cache unchanged, but the
  number of chains grows with the servers used, each chain has its own definition cache, and every new chain pays
  HAPI's lazy initialisation again. Rejected.
- Pass the setting through `ValidationOptions` or `ConceptValidationOptions`. HAPI does not carry custom data
  through to the support modules. Rejected.

## R2. What each mode does inside the scoped module

**Decision**:

| Scope | `route(system)` | Other behaviour |
|---|---|---|
| `none` | n/a | Every terminology method returns `null` / `false`. The chain then goes on to the in-memory and common modules, as in 1.0.0 with no module. |
| `server(url)` | `Optional.of(url)` | As `ServerTerminologySupport` today. |
| `ecosystem(default?)` | `resolver.choose(version, system, default, allowlist.current())` | As `EcosystemRoutingTerminologySupport` today. |
| `denied` | `Optional.empty()` | "Not checked" warning, as `DeniedTerminologySupport` today (a context default that is no longer allowed). |

`ServerTerminologySupport`, `EcosystemRoutingTerminologySupport` and `DeniedTerminologySupport` become small router
functions (`TerminologyRouter`) that the scope holds. They are no longer chain modules.

**Rationale**: `none` must give exactly 1.0.0 results (FR-027, SC-004). In 1.0.0, a `none` context has no
terminology module, so the chain asks `InMemoryTerminologyServerValidationSupport` next. A module that answers
`isCodeSystemSupported = false` and `isValueSetSupported = false` is skipped by HAPI's loop (`ValidationSupportChain`
lines 820–826, 915–918). The result is the same as having no module. `isCodeSystemSupported` results are cached by the
chain per module and system. That is why the `none` scope must answer `false` from the scope at call time rather than
the usual "not decided locally" answer: otherwise a cached `true` from a `server` request would make HAPI call the
module during a `none` request. The module then returns `null`, and HAPI goes on to the next module, which is still
correct. To be safe in both directions, the module's `isCodeSystemSupported` and `isValueSetSupported` never change
the outcome, only the dispatch, and the code methods check the scope first.

**Equivalence test**: run the spec 003, 004 and 007 corpora with no parameters against 1.0.0's expected outcomes
(golden files) for contexts in modes `none`, `server` and `ecosystem`.

## R3. Moving the code cache out of the chain (FR-012 to FR-014)

**Finding (HAPI 8.12.1)**: `ValidationSupportChain` caches `validateCode`, `validateCodeInValueSet` (when the value
set has a URL) and `lookupCode` in its expiring cache. The key (`ValidateCodeKey`, `LookupCodeKey`) is built from the
options, system, code, display and value set URL. It does not include a server or context. The same
`CacheConfiguration` also enables the non-expiring definition cache (`fetchStructureDefinition`, `fetchValueSet`, …):
`CacheConfiguration.disabled()` turns off **both** (lines 214–224). Sharing one cached chain across settings would
therefore serve server A's answer for server B.

**Decision**: `ScopedValidationSupportChain extends ValidationSupportChain` (it replaces `CacheCountingChain`):
- It keeps HAPI's cache configuration, so definition lookups are memoised exactly as today (FR-014).
- It overrides the three code methods. Each override looks up `TerminologyCodeCache` first. On a miss it runs HAPI's
  module loop **without** the chain cache (a short private copy of the 8.12.1 loop, including
  `generateResultForUnknownCodeSystem` for `validateCode`). It stores the result and counts a hit or a miss.
- A unit test pins the copied loop against `super` for the same modules with caching disabled, so a HAPI upgrade that
  changes the loop fails the build.

**Alternatives considered**:
- `CacheConfiguration.disabled()`, with an explicit cache in the module only. This loses definition memoisation
  (every `fetchStructureDefinition` walks the chain), and the in-memory module's local answers would no longer be
  cached either. Rejected under FR-014.
- An inner, uncached terminology chain under an outer cached chain. The outer chain still caches code results by the
  HAPI key. Rejected.

## R4. Code cache key, size and invalidation

**Decision**: `TerminologyCodeCache` is one Caffeine cache per instance:
`maximumSize(FV_TX_CACHE_SIZE)` (new default 50,000, bounds 100 to 1,000,000) and
`expireAfterWrite(FV_TX_CACHE_TTL_MS)` (default 10 minutes). Key:

`(snapshotId, scopeId, op, fhirVersion, system, code, display, valueSetKey, optionsKey)`

- `snapshotId`: a unique `long` per built `ValidatorSnapshot`. A result can depend on the context's own CodeSystems
  and ValueSets: they are checked locally, or sent with the request (spec 007 FR-014). So two contexts, or two builds
  of one context, never share a result. This is why spec FR-012 was amended during planning. Entries of a replaced
  snapshot are never read again and expire or are evicted. A dropped snapshot also invalidates its entries
  (`invalidateSnapshot`), so they do not hold memory for up to 10 minutes.
- `scopeId`: `none`, `denied`, `server:<normalised url>` or `ecosystem:<normalised default or ->`. For `ecosystem`,
  the server chosen for a system depends only on (FHIR version, system, default server, allowlist), so the scope
  identifies the route. An allowlist refresh that changes the list for a version drops that version's `ecosystem:*`
  entries.
- `valueSetKey`: the URL when the value set has one. Otherwise, for `validateCodeInValueSet` without a URL, the
  result is not cached (HAPI does the same: `key == null`).
- `optionsKey`: the `ConceptValidationOptions` flags HAPI's key uses (infer system, validate display).

Degraded request (`TerminologyBudget.degraded()`): drop every entry with that request's `scopeId`, across all
snapshots ("drop the entries of its own terminology scope", FR-013). This replaces
`chain.invalidateExpiringCaches()`, which also dropped unrelated expiring entries. A "not checked" answer (from the
`denied` scope, an unavailable server, an open circuit or a used-up budget) is never stored, so it is never served to
a later request (FR-012). Results produced by a call that degraded the request (including a `null` `lookupCode`
from an unavailable server, which carries no marker) are not stored either.

**Rationale**: at about 1 KB per entry (spec 007 research R15), 50,000 entries is about 50 MB. It is reserved once
per instance (R9).

## R5. Allowlist checks (FR-008, FR-011)

**Decision**:
- **Request override**: `TxServerAllowlist.require(url, version)` runs in `FhirValidationService` inside the
  admission slot (as the access check already does), right after the context key is resolved and the access check
  passes, and before the context-readiness wait (`registry.snapshot(key)`) and the validation worker. It throws the
  existing 400 `tx-server-not-registered` or 503 `tx-registry-unavailable`. For `txMode=ecosystem` without `tx`, the
  check is `require(settings, version)` (at least one allowed server).
- **Context default**: checked **on every request**, in memory (`isAllowed`). A default that is no longer allowed
  gives the `denied` scope. This covers "on apply" and "on allowlist change" (FR-011) without hooking `ConfigSync`
  or the allowlist refresher. A WARN is logged once per (context, server) transition, not per request.
- The per-call check in `TerminologyClients.call` (spec 007 FR-010c) and `NetworkGuard` stay as they are.

**Alternatives considered**: precomputing a denied flag in `ConfigSync` and on allowlist refresh. It is the same
behaviour with two more event paths to keep consistent. Rejected; the lookup is a hash lookup.

## R6. Where the context default comes from

**Decision**: the terminology setting leaves `ResolvedContext` (and so leaves `sameAs`). `ConfigSync` publishes a
per-version immutable `TerminologyDefaults` map (`name#version` → `TerminologySettings`) next to `PinnedAccess`. It
is swapped atomically on every sync, including `IG_TERMINOLOGY` notifications. `FhirValidationService` reads it the
same way it reads `PinnedAccess`. `ValidatorRegistry.details()` shows the published default.

**Rationale**: the default is configuration, not build input (FR-001). Keeping it in `ResolvedContext` and ignoring it
in `sameAs` would leave a stale copy on `activeResolved` (`ValidatorRegistry` only replaces it on a build).

**Consequence**: an `IG_TERMINOLOGY` notification still re-reads the version's row (`ConfigSync.sync`). Every slot is
then `sameAs` its active build, so no build starts (SC-001). The propagation window is unchanged.

## R7. Sharing package definitions (FR-017 to FR-023)

**Decision**: `SharedPackageDefinitions`, one per instance:
- Key `(fhirVersion, name#version, sha256)`. A package without a recorded hash is not shared: it is parsed privately,
  as today (`ResolvedContext.hashes` allows missing hashes).
- Value: `SharedPackage`, an immutable `List<IBaseResource>` parsed once (`parsePackageResources`, unchanged) plus
  `expandedBytes`.
- Store: Caffeine with `weakValues()`. `CustomNpmPackageValidationSupport` holds strong references to its
  `SharedPackage`s, so an entry lives while any snapshot holds it and is collected after the last one is dropped
  (FR-019, SC-008). Nobody counts references by hand.
- Single flight (FR-018): a `ConcurrentHashMap<Key, Object>` of per-key lock objects. A build that misses the cache
  takes the key's lock (`synchronized`) on its own `fv-packages` worker, looks in the cache again, and parses only if
  the entry is still absent. Concurrent builds for the same key wait on the lock; blocking is allowed inside
  `executeBlocking`, and builds are bounded by `AsyncLimiter`. A parse that throws stores nothing, so the next lock
  holder parses again. The lock object is removed only after a successful put (never after a failure, so a waiter
  and a newcomer can't parse at the same time on two lock objects). No future is created, `get()` or `join()`ed
  (constitution I).
- The parse does not run inside Caffeine's `get(key, fn)`. A parse takes seconds, and Caffeine computes inside
  `ConcurrentHashMap.compute`, which blocks other keys in the same bin.

**Build of one context** (`CustomNpmPackageValidationSupport`):
1. Get or parse each package's `SharedPackage`.
2. Collect the set's StructureDefinitions in order. `SnapshotPregenerator.withSnapshots` already returns **new**
   objects for the definitions it completes and leaves the originals untouched (it works on private deep copies).
3. Any StructureDefinition that still has no snapshot after step 2 is replaced by a private `terser.clone`
   (FR-020). HAPI's lazy path (`WorkerContextValidationSupportAdapter`) would otherwise generate a snapshot with
   **this** context's chain and store it on the shared object.
4. `addResource` every resource, in the same order as 1.0.0, into the context's own `PrePopulatedValidationSupport`.
   The indexes are private, and the "last added wins" rule for duplicate canonicals is unchanged (FR-021).

**Why sharing the R5 copies is free**: `WorkerContextValidationSupportAdapter.convertToCanonicalVersionAndGenerateSnapshot`
caches the converted R5 object in the source object's `userData` (`TO_CANONICAL_USERDATA_KEY`), under
`synchronized (theResource)`. A shared source therefore has one shared R5 copy. The JVM-wide core definitions
(`DefaultProfileValidationSupport`, cached per `FhirContext`) already work this way. **Risk**: core caches compiled
FHIRPath and slicing data in `ElementDefinition` userData. Within one snapshot these objects are already used by
concurrent requests, so no new kind of race is added. Whether the cached data depends on the context is what the
differential check (SC-004) has to prove. FR-022 makes that check a release gate.

**Alternatives considered**: C2 (one locked `PrePopulatedValidationSupport` per package, composed in the chain)
changes which definition wins for a duplicate canonical (first module wins, instead of last added wins). Rejected
(FR-021). C3 (composing contexts per request) was rejected in the input note.

## R8. Differential check and spike (SC-004 to SC-006)

**Decision**:
- `SharedDefinitionsDifferentialIT` (Testcontainers) builds DK Core 3.5.0 and MedCom Core 4.0.0 as two R4 contexts, validates
  `src/test/resources/corpus/010/**` (examples plus invalid variants) sequentially and with 8 threads × 2 contexts ×
  N rounds, and compares each OperationOutcome against golden files, issue by issue and in order. The golden files are
  generated once from the 1.0.0 tag by `scripts/golden-010.sh` and committed.
- The memory figure (SC-005) comes from `jcmd GC.class_histogram` after a full GC, on 1.0.0 and on the branch, both
  with the two contexts loaded. The release gate is at least 20% less memory for the second context; the target is
  40%. The build time (SC-006) comes from the build log line. Both go in `perf/results-1.1.0.md`.
- **Spike first**: tasks start with the shared cache behind a test-only constructor flag, then run SC-004 and
  SC-005 before the terminology work merges. Sharing has no operator switch (FR-022). The flag exists only in test
  code, to produce the 1.0.0-equivalent "no sharing" build for comparison, and is never read from configuration.

## R9. Heap accounting (FR-024)

**Decision**:
- `HeapBudget.reserve(unsharedExpandedBytes, sharedExpandedBytes)`:
  `BUILD_OVERHEAD + 6 × unsharedExpanded + 1 × sharedExpanded`. The `1×` covers the context's own indexes, chain
  caches and private clones of incomplete definitions. The spike checks it and may lower it.
- "Unshared" means not present in `SharedPackageDefinitions` when the build starts. A package parsed by a concurrent
  build at the same moment is counted twice, which over-reserves and is safe.
- The per-build `terminologyCacheBytes` is removed. The code cache is reserved once at startup:
  `TX_CACHE_ENTRY_BYTES × FV_TX_CACHE_SIZE` (50 MB by default), taken from the budget in `ServiceRegistry`.
  `admitInclude` stops adding a per-context cache bound.

## R10. Request parameters and resolution (FR-005 to FR-007)

**Decision**: `ValidationRequestOptions.fromRequest` parses `tx` and `txMode` into a `TerminologyOverride` (both
optional). Rules:
- Each parameter may appear at most once; a repeat gives 400 `invalid-request`.
- `txMode` must be exactly `none`, `server` or `ecosystem` (`TerminologyMode.fromWire`). It is case-sensitive.
- `tx` goes through the same length check and `TerminologySettings.normaliseUrl` as a saved setting.
- `txMode=none&tx=…` gives 400.

The URL is never echoed. Messages are fixed strings. `EffectiveTerminology.resolve(D, override)` is a pure function
that implements the spec's resolution table. The one row that depends on `D` and can fail (`txMode=server` without a
server anywhere) also gives 400 `invalid-request`.

**OpenAPI**: `tx` is `type: string, maxLength: 512`. `txMode` is `enum [none, server, ecosystem]`. Vert.x
`RouterBuilder` validates the enum. A contract-layer failure is turned into the curated 400 by
`handleValidateFailure`, the same as for `ig`.

## R11. Reporting the effective setting (FR-010)

**Decision**:
- Response headers `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source` (`request` | `context`), on every 200 only.
  `X-Tx-Server` is the server used for `server`; for `ecosystem` the `tx` value, else the context's server, else
  absent (the registry's default is not reported); absent for `none`.
- Add them to `CorsConfig.exposedHeaders`. Otherwise browsers can't read them. The `X-Validation-Context*` headers
  are not exposed today either; adding them is out of scope and noted for a later change.
- Usage: `UsageRecord` gains `txMode`, `txServer` and `txSource`. The `validation_usage` table gains three nullable
  columns (V16) so older rows stay valid. The log sink writes the same fields.

**Alternatives considered**: body fields or an OperationOutcome extension. Rejected in clarification (Q4).

## R12. Failure isolation per server (FR-015)

**Finding**: already true. `TerminologyClients.breakers` is keyed by the normalised URL, and `clients` by
(URL, version). The breaker and pool are per server, not per context. A request override reuses the same entries.
**Decision**: no change. A unit test confirms that an open circuit on B doesn't affect A.

## R13. Warm-up (FR-004)

**Decision**: `SnapshotBuilder` opens a `none` scope (and the 0 ms budget) around the warm-up validation. The scope
requires no network access and no allowlist.

## R14. Release and versioning

**Decision**: `pom.xml` and `openapi.yaml` `info.version` go to **1.1.0**. The changelog gets a 1.1.0 entry with:
- the two amended spec 007 behaviours (no rebuild on a setting change; code results kept per server and context);
- the new `FV_TX_CACHE_SIZE` default;
- the new parameters and headers;
- migration V16.

No path, payload or status code of an existing request changes, so this is not a breaking change under Principle IV.
No Complexity Tracking entry or upgrade guide is needed. The maintainer's instruction stands: if review finds a
break, it ships in 1.x through the Principle IV exception, never as 2.0.
