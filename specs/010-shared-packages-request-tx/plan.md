# Implementation Plan: Shared Package Definitions and Per-Request Terminology Settings

**Branch**: `010-shared-packages-request-tx` | **Date**: 2026-11-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/010-shared-packages-request-tx/spec.md`; original notes
`var/shared_package_resources_research.md` and `var/terminology_decoupling_plan.md`

## Summary

Two changes ship together as **1.1.0**, a minor release with no breaking HTTP changes.

**A. A context holds definitions only; the terminology setting is applied per request.**
- `ResolvedContext` loses its terminology setting, so `sameAs` ignores it, and a setting change never starts a
  build.
- `ConfigSync` publishes each context's setting as a per-version `TerminologyDefaults` map, next to `PinnedAccess`.
- Every snapshot has one `RequestScopedTerminologySupport` module, wrapped in `TerminologyOnlySupport` and placed
  before the in-memory module as today. It reads a thread-local `TerminologyScope` (`none`, `denied`, `server` or
  `ecosystem`), opened in `ValidatorSnapshot.validate` next to `TerminologyBudget`.
- `FhirValidationService` resolves the effective setting from the context default and the new `tx`/`txMode`
  parameters (pure resolution table, data-model §4). It checks the allowlist before validation:
  - a caller-named server that is not allowed gets the existing 400 `tx-server-not-registered` or 503
    `tx-registry-unavailable`;
  - a context default that is no longer allowed gives the `denied` scope.
- Code results leave HAPI's chain cache. `ScopedValidationSupportChain` keeps HAPI's definition caching and answers
  the three code operations from one per-instance `TerminologyCodeCache` (Caffeine; default 50,000 entries, was
  5000 per context). The key includes the snapshot and the scope, so answers are never shared between servers or
  contexts.
- The 200 response reports the setting used in `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source`. The usage record gets
  three nullable columns (V16).

**B. Contexts share parsed package definitions.**
- `SharedPackageDefinitions` (Caffeine `weakValues`, one parse per key, guarded by a per-key lock) maps
  `(fhirVersion, name#version, sha256)` to an immutable list of parsed resources.
- `CustomNpmPackageValidationSupport` gets or parses each package from it, then completes StructureDefinitions with
  `SnapshotPregenerator` (which returns private copies). It clones any definition still lacking a snapshot and
  indexes everything in its own `PrePopulatedValidationSupport`, in the 1.0.0 order.
- `HeapBudget` reserves the full factor only for packages not already shared.
- The R5 copies cached on the shared objects come along for free.
- Sharing has no switch. The release is gated on a differential check against 1.0.0's results (SC-004) and on at
  least 20% less memory for a second context (SC-005).

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Vert.x 4.5.x (web, web-openapi, pg-client, config); HAPI FHIR 8.12.1 (validation,
`ValidationSupportChain`, `NpmPackageValidationSupport`) with org.hl7.fhir.core 6.9.12; Caffeine (already present
through `hapi-fhir-caching-caffeine` and used by `RegistryResolver`); Log4j2; Micrometer/Prometheus

**Storage**: PostgreSQL + Flyway. One forward-only migration, `V16__usage_terminology.sql` (three nullable columns
on `validation_usage`). Everything else is in memory and derived.

**Testing**: JUnit 5 + `vertx-junit5`; Testcontainers PostgreSQL (`BaseTestContainer`, `AppHarness`); the existing
mock terminology server from spec 007 ITs; new golden-file corpus `src/test/resources/corpus/010/`

**Target Platform**: Linux containers (amd64, arm64), JVM with `-XX:+ExitOnOutOfMemoryError`

**Project Type**: single-module web service (`nzi.fhir.validator`)

**Performance Goals**:
- p95 of single-resource validation on a warm context with no terminology server within 5% of 1.0.0 (SC-007).
- A terminology setting change costs no build (SC-001). Before, it cost a full rebuild of up to minutes.
- A second context with only shared packages builds faster than on 1.0.0 (target 50% less time, SC-006).

**Constraints**:
- Validation results are identical to 1.0.0 for requests without the new parameters (SC-004, a hard gate).
- No outbound call to a server that is not allowed.
- No new dependency or infrastructure.
- The code cache is bounded (100 to 1,000,000 entries).
- Shared entries are bounded by what live snapshots reference.

**Scale/Scope**: up to about 10 to 20 included contexts per FHIR version per instance; the most common shared
packages are `hl7.terminology.r4` and `hl7.fhir.uv.extensions.r4`. Memory impact:
- −20% (gate) to −40% (target) for each extra context that shares packages;
- +50 MB reserved once for the code cache;
- minus the per-context code-cache reservations of 1.0.0 (5 MB each).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design: still passing.*

| Principle | Status | How the design complies |
|---|---|---|
| **I. Non-blocking event loop** | PASS | Parsing and pregeneration stay on `fv-packages`. Same-key builds wait on a per-key lock inside their `fv-packages` worker (allowed in `executeBlocking`; builds are bounded by `AsyncLimiter`); no future is joined. Resolving the effective setting and the allowlist check are in-memory lookups on the event loop, the same as `checkAccess`. Code-cache lookups run inside validation on `fv-validation`. V16 runs through Flyway at startup as today. No `.get()` or `.join()` on any future (research R7). |
| **II. Stateless, horizontally scalable** | PASS | Shared package entries, the code cache and `TerminologyDefaults` are derived, in-memory caches that each instance rebuilds from PostgreSQL. Cache consistency: a terminology setting change still bumps the version revision and sends `pg_notify` (`IG_TERMINOLOGY`); `ConfigSync` swaps `TerminologyDefaults` on the next sync, and the poll catches missed notifications. Snapshots are still built at most once per identity per instance, and the identity now excludes terminology. |
| **III. FHIR conformance** | PASS (gated) | Results for requests without the new parameters must be identical to 1.0.0 (FR-023, FR-027): the golden-file differential IT, run sequentially and concurrently, is a release gate (FR-022). Duplicate-canonical precedence and lookup order are unchanged (FR-021). No issue is added, removed or downgraded; the setting used is reported only in headers. |
| **IV. Contract-first API** | PASS | `openapi.yaml` changes land first ([contracts/http-api.md](./contracts/http-api.md)): two optional query parameters, three 200 response headers and description text. All additive; no existing request changes status or body. So 1.1.0 is a MINOR release with no Complexity Tracking entry. Errors reuse `invalid-request`, `tx-server-not-registered` and `tx-registry-unavailable`. |
| **V. Test-first, real dependencies** | PASS | Each story starts with a failing IT on Testcontainers PostgreSQL. New ITs: `TerminologyNoRebuildIT` (US1), `TerminologyOverrideIT` (US2), `SharedDefinitionsDifferentialIT`, `SharedPackageLifecycleIT` (US3), `HeapBudgetSharedIT` (US4). Pure unit tests: resolution table, parameter parsing, code-cache keys and invalidation, the copied HAPI loop, heap arithmetic. `MigrationUpgradeIT` covers V16 on a populated 1.0.0 schema. The validate endpoint already has contract tests; new cases are added there. |
| **VI. Performance and capacity budgets** | PASS | Impact is stated above. The hot validation path changes (scope, scoped chain), so a k6 comparison against 1.0.0 is required in the PR (SC-007, `perf/compare-run.sh`). Memory and build-time numbers go in `perf/results-1.1.0.md`. Bounds: `FV_TX_CACHE_SIZE` keeps its 100 to 1,000,000 range; the new default is 50,000. Shared entries need no size setting: they are bounded by live snapshots, which are bounded by `HeapBudget`. |
| **VII. Observability** | PASS | New metrics (data-model §10): shared entries, expanded bytes, lookups by result, code-cache misses, invalidations, size, and validations by `tx_source`. No URL is used as a tag. Build log lines state whether each package was reused or parsed (FR-026). A context default that is no longer allowed is logged at WARN once per change. Configuration still comes only from the environment. |
| **VIII. Simplicity, dependencies** | PASS | No new dependency or infrastructure. Caffeine is already in use. One forward-only migration (V16). Three router classes replace three chain modules. The copied HAPI loop (about 60 lines) is the one added complexity; it is pinned by `ScopedChainLoopTest` (research R3) and is preferred over losing definition caching. |
| **Security & compliance** | PASS | Outbound terminology still goes only to allowlisted, public, non-redirecting servers: a caller-named server is checked before validation and again before every call. A refused server gets no call. Validated resources are never sent. `tx` is never logged above DEBUG, and URLs never appear as metric tags. Access checks (404/424) run before any terminology answer, so a caller can't probe another organisation's context settings. |
| **Workflow gate: migrations on the previous release's schema** | PASS | V16 is additive and nullable. `MigrationUpgradeIT` applies it to a populated V15 (1.0.0) database. Rolling upgrade: a 1.0.0 instance doesn't write the new columns, and a 1.1.0 instance on the old schema can't start until migration (Flyway runs first), so this is the usual order. |

## Project Structure

### Documentation (this feature)

```text
specs/010-shared-packages-request-tx/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R14
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   └── http-api.md      # Phase 1: validate parameters, headers, errors
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── ServiceRegistry.java                       # wire SharedPackageDefinitions, TerminologyCodeCache, defaults; reserve code-cache heap
├── core/
│   ├── npm/
│   │   └── SharedPackageDefinitions.java      # NEW: weakValues cache + in-flight parses (research R7)
│   ├── service/
│   │   ├── CustomNpmPackageValidationSupport.java  # get-or-parse shared packages; private clones (R7)
│   │   ├── FhirValidationService.java         # effective setting, allowlist check, scope to the snapshot
│   │   └── IgPackageService.java              # admitInclude without per-context cache bound
│   ├── model/
│   │   └── ValidationRequestOptions.java      # parse tx / txMode → TerminologyOverride
│   ├── terminology/
│   │   ├── TerminologyOverride.java           # NEW (data-model §3)
│   │   ├── EffectiveTerminology.java          # NEW: resolution table (§4)
│   │   ├── TerminologyDefaults.java           # NEW (§2)
│   │   ├── TerminologyScope.java              # NEW: thread-local scope (§5)
│   │   ├── TerminologyRouter.java             # NEW: none / denied / server / ecosystem routing
│   │   ├── RequestScopedTerminologySupport.java    # NEW: the one module per snapshot (R1, R2)
│   │   ├── TerminologyCodeCache.java          # NEW: per-instance Caffeine cache (R4, §6)
│   │   ├── TerminologyServices.java           # + codeCache; chainCache stays (definitions)
│   │   ├── ServerTerminologySupport.java      # REMOVED (becomes a router)
│   │   ├── EcosystemRoutingTerminologySupport.java # REMOVED (becomes a router)
│   │   └── DeniedTerminologySupport.java      # REMOVED (becomes a router)
│   ├── validator/
│   │   ├── ScopedValidationSupportChain.java  # NEW: replaces SnapshotBuilder.CacheCountingChain (R3)
│   │   ├── SnapshotBuilder.java               # one module; shared packages; heap reservation; none scope in warm-up
│   │   ├── ValidatorSnapshot.java             # snapshotId; open scope; per-scope invalidation; Outcome carries effective setting
│   │   ├── ResolvedContext.java               # drop terminology; sameAs on packages + hashes
│   │   ├── ConfigSync.java                    # publish TerminologyDefaults
│   │   ├── ValidatorRegistry.java             # details() from defaults; invalidate code cache on snapshot drop
│   │   └── HeapBudget.java                    # reserve(unshared, shared); one global code-cache reservation
│   ├── usage/
│   │   ├── UsageRecord.java                   # txMode, txServer, txSource
│   │   └── UsageMeter.java                    # record the effective setting
│   └── metrics/
│       └── ServiceMetrics.java                # new metrics (§10)
└── web/
    ├── CorsConfig.java                        # expose X-Tx-* headers
    └── endpoint/ValidationApi.java            # pass the override; X-Tx-* headers

src/main/resources/
├── openapi.yaml                               # contracts/http-api.md; info.version 1.1.0
└── db/migration/V16__usage_terminology.sql    # NEW

src/test/java/nzi/fhir/validator/core/
├── terminology/  EffectiveTerminologyTest, TerminologyOverrideParseTest, TerminologyCodeCacheTest,
│                 RequestScopedTerminologySupportTest, TerminologyClientsIsolationTest (extended)
├── validator/    TerminologyNoRebuildIT, ScopedChainLoopTest, HeapBudgetTest (extended),
│                 HeapBudgetSharedIT, SharedDefinitionsDifferentialIT
└── npm/          SharedPackageDefinitionsTest, SharedPackageLifecycleIT
src/test/java/nzi/fhir/validator/web/       TerminologyOverrideIT, CorsConfigTest
src/test/resources/corpus/010/                 # NEW: examples, invalid variants, golden OperationOutcomes
scripts/golden-010.sh                          # NEW: regenerates golden files from the v1.0.0 tag
perf/results-1.1.0.md                          # NEW: SC-005..SC-007 numbers
CHANGELOG.md, README.md, .env.example, pom.xml # 1.1.0; FV_TX_CACHE_SIZE default; per-request terminology docs
```

**Structure Decision**: the existing single-module layout. New classes go in the package that owns the concern: `npm`
for package sharing, `terminology` for the scope, routing and code cache, and `validator` for the chain and the
snapshot.

## Implementation Order (for /speckit-tasks)

1. **Spike: sharing.** Add `SharedPackageDefinitions` and the changed `CustomNpmPackageValidationSupport`, with a
   test-only "no sharing" constructor. Build the corpus and golden files from `v1.0.0`. Run the differential IT and
   the histogram. Stop and report if SC-004 fails or memory improves by less than 20%.
2. **Foundation for terminology.** Add `TerminologyOverride`, `EffectiveTerminology` (unit-tested table),
   `TerminologyScope`, `TerminologyRouter`, `TerminologyCodeCache` and `ScopedValidationSupportChain` (with the
   loop-pinning test).
3. **US1.** Add `RequestScopedTerminologySupport` in `SnapshotBuilder.assemble`. Drop terminology from
   `ResolvedContext`. Publish `TerminologyDefaults` from `ConfigSync`. Remove the per-build module and the
   allowlist check at build time. Write `TerminologyNoRebuildIT`. Re-run spec 007's ITs; only their
   "setting change rebuilds" assertions change.
4. **US2.** Parse the parameters; resolve and check in `FhirValidationService`; add the headers, CORS, V16 and the
   usage fields; update OpenAPI; write `TerminologyOverrideIT`.
5. **US3.** Make sharing permanent (remove nothing from production; the test flag stays test-only). Add
   `SharedPackageLifecycleIT` (release on drop, replaced hash, concurrent builds, failed parse).
6. **US4.** Change the `HeapBudget` reservations, add the metrics and the build log lines.
7. **Release.** Set 1.1.0 in `pom.xml` and OpenAPI. Update the changelog (amended spec 007 FR-004/FR-010b/FR-018,
   the cache default, parameters, headers, V16), the README and `.env.example`. Record the k6 comparison and
   `perf/results-1.1.0.md`.

## Complexity Tracking

No constitution violations. The only notable complexity, the copy of HAPI's code-check loop in
`ScopedValidationSupportChain`, is justified in research R3 and pinned by a test. It is not a constitution
violation.
