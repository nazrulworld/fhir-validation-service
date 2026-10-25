# Implementation Plan: Service Hardening — Correctness, Throughput and Safe Administration

**Branch**: `001-service-hardening` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-service-hardening/spec.md` (with planning input:
*"fix should include not codebase but also docker build and compose"*)

## Summary

Fix the 50 audited defects ([audit-findings.md](./audit-findings.md): B1–B21, P1–P7, F1–F10,
D1–D12) across the code, the container image, compose and CI. The core changes:

1. **Immutable validator snapshots** per FHIR version, built on a dedicated package pool and
   swapped atomically. Validations run on a dedicated, bounded validation pool. Together these fix
   the one-at-a-time bottleneck, event-loop stalls, mixed IG versions and races.
2. **PostgreSQL-only propagation**: a revision counter plus `LISTEN/NOTIFY`, with a 5 s poll as
   fallback. Every instance converges within 10 s. Profiles are preloaded in memory, which fixes
   the first-use bug.
3. **Safe administration**: API keys with ADMIN/VALIDATE roles, SSRF-safe fetching from an
   allowlist, and archive guards that check before unpacking.
4. **Honest API**: proper content negotiation, `OperationOutcome` for FHIR media types, RFC 9457
   problems, a guaranteed response for every request, and consistent version handling. Released
   as **1.0.0**, because the changes are breaking.
5. **Operable deployment**: one configuration mechanism through environment variables, readiness,
   liveness and metrics, graceful drain, and a single-process non-root image under `tini` with no
   bundled database. A profile-based `compose.yaml` with a separate PostgreSQL service and an
   optional 2-instance cluster. CI publishes images only after `mvn verify`, a container smoke test
   and an image scan pass.

## Technical Context

**Language/Version**: Java 17 (unchanged; the runtime image is Temurin 17 JRE)

**Primary Dependencies**: Vert.x 4.5.14 (core, web, web-openapi, pg-client, config, web-client),
HAPI FHIR 8.2.0, org.hl7.fhir.utilities 6.5.18, Flyway 11.8.2, Caffeine (through HAPI), Log4j2.
**New** (justified in research R13): `vertx-micrometer-metrics`, `micrometer-registry-prometheus`,
`reactiverse-contextual-logging`. **Build/CI only**: hadolint, trivy, k6 (perf scripts).

**Storage**: PostgreSQL 16, schema `fhir_validator_schema`. New migrations V3 (revision, resolved
set, widened version column) and V4 (`api_keys`). See [data-model.md](./data-model.md).

**Testing**: JUnit 5 + vertx-junit5, Testcontainers PostgreSQL (`BaseTestContainer`), contract
tests per operation, a two-instance propagation test in one JVM, a container smoke test
(`scripts/smoke.sh`), and k6 load scripts (`perf/`).

**Target Platform**: Linux containers on amd64 and arm64 (GHCR), and Compose v2 for local and
small deployments. Kubernetes-compatible probes.

**Project Type**: Single-module web service (Maven, shaded jar).

**Performance Goals**: p95 under 200 ms for single-resource validation on a warm validator.
Throughput scales at least 3× from 1 to 4 cores. During a 20 MB IG load, p95 rises no more than
20% and probes answer within 1 s. Cross-instance propagation within 10 s.

**Constraints**: The event loop never blocks (Principle I). Every queue and cache is bounded and
configurable. Package cache defaults to 256 MB. No new shared infrastructure beyond PostgreSQL.
Image ≤ 550 MB (jar-dominated; see contracts/container.md).

**Scale/Scope**: N stateless instances against one PostgreSQL. 4 enabled FHIR versions. Tens of
IGs, thousands of profiles. About 25 Java files touched or added, 2 migrations, `openapi.yaml`,
Dockerfile, compose, Makefile, 3 workflows.

All unknowns are resolved in [research.md](./research.md). No NEEDS CLARIFICATION remains.

## Constitution Check

*GATE: checked before Phase 0, and re-checked after Phase 1 design (below).*

| Principle | Status (post-design) | How the design complies |
|-----------|----------------------|-------------------------|
| I. Non-blocking event loop | ✅ | Named `fv-validation` and `fv-packages` executors, `executeBlocking(Callable, false)`. Package parsing and snapshot builds move off the loop (R1, R5). Profile lookup becomes an in-memory map, so `.result()` on a pending Future goes away (R3). Deprecated callback `executeBlocking` is removed (baseline deviation 2) |
| II. Stateless, horizontally scalable | ✅ | PostgreSQL stays the source of truth. Snapshots and profile maps are derived and rebuildable. `LISTEN/NOTIFY` plus polling keeps caches consistent (R4). Static `HashMap`s are removed (baseline deviation 1). Validators are built once per revision and shared across verticle instances. The app image no longer bundles a database (D2) |
| III. FHIR conformance | ✅ | No issue filtering. Deterministic, version-aware dependency resolution, with the resolved set persisted (R6). All four enabled versions get explicit validators. STU3 is added (R19). `OperationOutcome` is available (R11) |
| IV. Contract-first API | ✅ | `openapi.yaml` changes are listed in [contracts/http-api.md](./contracts/http-api.md) and land with the code. One problem format, and no internal messages in responses. Breaking changes → **1.0.0** and a `CHANGELOG.md` migration note |
| V. Test-first, real dependencies | ✅ | A regression test per closed finding (SC-009). Testcontainers for persistence and propagation. Contract tests for every operation. Per-version fixtures (R20) |
| VI. Performance budgets | ✅ | Targets stated above. Limits configurable with bounds ([configuration.md](./contracts/configuration.md)). k6 results attached to the PR for hot-path changes |
| VII. Observability and operability | ✅ | Prometheus `/metrics`, correlation ids, readiness reflecting warm-up and DB state, 12-factor configuration through env, no secrets in the repo (`.env` untracked) (R13–R15, R17). Baseline deviations 4 and 6 closed |
| VIII. Simplicity and dependencies | ✅ (with justified additions) | Three runtime dependencies added, each justified with the simpler alternative rejected (R13). Version comparator written in-house instead of a library (R7). No Redis or broker. Schema changes only through V3/V4 |
| Security & Compliance | ✅ | Archive guard (R10), registry allowlist plus SSRF checks (R9), configurable CORS with no wildcard default (closes baseline deviation 3), no PHI in INFO logs (existing debug-only body logging kept) |
| Workflow gates | ✅ | CI: `mvn verify`, route/contract tests, migrations on an empty DB and on the previous schema, smoke test, scan (R18) |

**Gate result: PASS.** No violations, so Complexity Tracking stays empty. Baseline deviations
1–7 are all closed by this feature. Deviation 5 (README mentions Redis and Java 11) is fixed as
part of the documentation tasks.

## Project Structure

### Documentation (this feature)

```text
specs/001-service-hardening/
├── spec.md               # Requirements (clarified)
├── audit-findings.md     # Evidence: B/P/F/D findings with file:line
├── plan.md               # This file
├── research.md           # Phase 0 decisions R1–R20
├── data-model.md         # Phase 1: schema changes, in-memory entities, state machine
├── quickstart.md         # Phase 1: end-to-end validation guide
├── contracts/
│   ├── http-api.md       # openapi.yaml deltas, problem format, headers
│   ├── configuration.md  # Environment variable contract
│   └── container.md      # Image, compose and CI contract
├── checklists/requirements.md
└── tasks.md              # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

Legend: `+` new, `~` changed, `-` deleted.

```text
src/main/java/nzi/fhir/validator/
├── Bootstrap.java                         + main(): config → Flyway → registry warm-up → deploy N HttpVerticle; shutdown hook
├── core/
│   ├── config/
│   │   ├── ApplicationConfig.java         ~ typed facade over the merged vertx-config JsonObject (R14)
│   │   ├── ConfigLoader.java              + store chain: classpath → FV_CONFIG_FILE → env, with bounds validation
│   │   ├── PgConfig.java                  ~ no password logging, SSL mode, no default password
│   │   └── VerticleConfig.java            - merged into ConfigLoader
│   ├── concurrency/
│   │   ├── Executors.java                 + named fv-validation / fv-packages WorkerExecutors
│   │   └── AdmissionController.java       + bounded pending counter → 503
│   ├── validator/
│   │   ├── ValidatorRegistry.java         + EnumMap<Version, AtomicReference<ValidatorSnapshot>>
│   │   ├── ValidatorSnapshot.java         + immutable validator plus revision and resolved set
│   │   ├── SnapshotBuilder.java           + builds the chain from resolved packages on fv-packages
│   │   ├── ProfileStore.java              + per-version ConcurrentHashMap of profiles
│   │   ├── ConfigSync.java                + PgSubscriber on fv_config_changed, plus poll fallback
│   │   └── OutcomeRenderer.java           + ValidationResponse JSON / OperationOutcome JSON+XML
│   ├── npm/
│   │   ├── PostgresPackageCacheManager.java ~ parse off-loop, single fetch path, store sha/source
│   │   ├── AsyncBasePackageCacheManager.java ~ registry fallback fix (B9, B19); or folded into the manager
│   │   ├── PackageCache.java              + byte-weighted Caffeine cache
│   │   ├── PackageArchiveGuard.java       + size/ratio/entry/traversal checks (R10)
│   │   ├── SafePackageFetcher.java        + allowlist, SSRF address checks, redirects, streaming cap (R9)
│   │   ├── DependencyResolver.java        + deterministic BFS (R6)
│   │   └── PackageVersionComparator.java  + SemVer ordering (R7)
│   ├── security/
│   │   ├── ApiKeyAuthHandler.java         + bearer → role. Hash lookup with a TTL cache
│   │   └── ApiKeyRepository.java          + api_keys CRUD and bootstrap key upsert
│   ├── model/
│   │   ├── ValidatorIdentity.java         - replaced by SupportedFhirVersion (R19)
│   │   ├── ValidationRequestContext.java  ~ media-type negotiation. UUID/api_clients branch removed
│   │   ├── MediaTypes.java                + Accept/Content-Type parser (q-values, wildcards, params)
│   │   └── ValidationResponse.java        ~ now used. Adds fhirVersion and appliedProfiles
│   ├── enums/SupportedFhirVersion.java    ~ isEnabled() and a case-insensitive parse helper
│   └── service/
│       ├── FhirValidationService.java     ~ thin: admission → snapshot.validate on fv-validation
│       ├── CustomNpmPackageValidationSupport.java ~ no static cache. Lives only inside a snapshot
│       ├── CustomProfileValidationSupport.java    ~ reads ProfileStore. No Futures
│       ├── ProfileService.java            ~ transactional upsert + revision + notify. No Caffeine or EmptyResource
│       ├── IgPackageService.java          ~ include → resolve → persist resolved set + notify. Latest via comparator
│       ├── ConfigRevisionRepository.java  + revision bump and pg_notify in the caller's transaction
│       ├── HealthService.java             ~ readiness: snapshots, DB freshness, draining flag
│       ├── CachingService.java            - no longer needed
│       └── DatabaseService.java           ~ Flyway on fv-packages via Callable executeBlocking
└── web/
    ├── HttpVerticle.java                  ~ renamed from MainVerticle: router only, drain-aware stop()
    ├── ErrorResponder.java                + RFC 9457 problems, router error handlers, respond(ctx, Future)
    ├── RequestIdHandler.java              + X-Request-Id plus contextual logging
    ├── CorsConfig.java                    + allowlisted origins
    └── endpoint/
        ├── ValidationApi.java             ~ negotiation, status mapping, no double responses
        ├── IgPackageApi.java              ~ guard + fetcher, 404s, correct version echo
        ├── ProfileApi.java                ~ shared version helper. No getOrDefault
        ├── HealthApi.java                 ~ + readiness
        └── MetricsApi.java                + /metrics

src/main/resources/
├── openapi.yaml                           ~ per contracts/http-api.md
├── config.json                            ~ defaults only. Unused keys removed
├── application*.properties                - removed (env-only configuration)
├── log4j2.xml                             ~ %X{requestId} in the pattern. JSON layout option
└── db/migration/V3__config_revision_and_version_length.sql, V4__api_keys.sql   +

src/test/java/nzi/fhir/validator/          ~ + PackageVersionComparatorTest, MediaTypesTest, PackageArchiveGuardTest,
                                             SafePackageFetcherTest, DependencyResolverTest, ValidatorSnapshotSwapIT,
                                             ProfileFirstUseIT (B1), ConfigPropagationIT (2 instances, B2/B3),
                                             MigrationUpgradeIT, ConcurrencyIT (P1), contract tests per operation
perf/                                      + validate.js, validate-during-ig-load.js, README.md
scripts/smoke.sh                           + container smoke test (CI and make smoke)

Dockerfile                                 ~ rewritten per R16 / contracts/container.md
.dockerignore                              ~ + specs/, .specify/, .claude/, perf/, *.env
compose.yaml                               + replaces docker-compose.yml (profiles: default, cluster, observability, admin)
docker-compose.yml                         - removed
docker/nginx/lb.conf                       + round-robin for the cluster profile
docker/prometheus.yml                      ~ scrape validator:8880/metrics (and validator-2 in cluster)
docker/docker-entrypoint.sh                - removed (tini + exec java)
docker/init-scripts/wait-for-services.sh   - removed (the app retries the DB and stays not-ready)
docker/postgres-init/init-database.sh      - removed (the official postgres image env does this)
docker/postgres-init/01-init.sql           ~ kept, mounted into the postgres service
.env                                       - untracked (git rm --cached) and git-ignored
.env.example                               + every variable from contracts/configuration.md, placeholders only
.gitignore                                 ~ + .env
Makefile                                   ~ up/up-cluster/up-obs/down/clean/logs/smoke. Redis/ollama/stop-all removed
.github/workflows/ci.yml                   + PR gates
.github/workflows/docker-publish.yml       ~ test → image + smoke + scan → push. latest only on release
.github/workflows/maven-publish.yml        ~ verify. Attach compose.yaml + .env.example
pom.xml                                    ~ 1.0.0-SNAPSHOT. New dependencies as properties. Description without Redis
CHANGELOG.md                               + 1.0.0 breaking changes and migration notes
README.md                                  ~ remove Redis and Java 11. Document compose profiles, auth and env
```

**Structure decision**: keep the single Maven module and the existing
`core/{config,enums,model,npm,service}` and `web/endpoint` layout. New concerns get their own
small packages (`core/concurrency`, `core/validator`, `core/security`) so each finding fix has an
obvious home. `MainVerticle` is split into `Bootstrap` (process lifecycle, run once) and
`HttpVerticle` (deployed N times), because shared validators must be built once per process
(Principle II) while HTTP handling scales across event loops.

## Delivery Phasing (input to /speckit-tasks)

The stories are independently deliverable (spec Assumptions). The suggested order puts
foundations first:

| Phase | Content | Closes |
|-------|---------|--------|
| 0. Foundations | ConfigLoader (env), ErrorResponder + RequestId, Executors, V3 migration, Bootstrap/HttpVerticle split, CHANGELOG | F6, F9, B16 (partial), B18, D6 (app side) |
| 1. Deployment base | New Dockerfile, `compose.yaml` (default profile), `.env.example`, untrack `.env`, smoke script, `ci.yml` | D1–D5, D7, D8, D11, D12, F4 |
| 2. US2 throughput | AdmissionController, validation pool, package pool, PackageCache, readiness | P1–P3, P5, F5 (probes) |
| 3. US1 correctness | Snapshots + registry, ProfileStore, DependencyResolver, ConfigSync (NOTIFY + poll), cluster compose profile | B1–B6, B17, F8 |
| 4. US4 API | MediaTypes, OutcomeRenderer, status mapping, version helper, removal of per-client validators, STU3 | B10–B16, B20, B21, F10 |
| 5. US3 security | V4 + API keys, SafePackageFetcher, PackageArchiveGuard, CORS config | F1–F3, P4 |
| 6. US5 packages | PackageVersionComparator, registry fallback, version length (V3 already) | B7–B9, B19, P6, P7 |
| 7. US6 operability | Micrometer metrics, graceful drain, observability/admin compose profiles, publish workflows, README | F7, D9, D10, FR-031 |

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Snapshot rebuild memory peak (old and new snapshot coexist during a swap) | Builds are serialised per version on `fv-packages`. Document a heap guideline (≥ 2× the largest snapshot). Expose a snapshot-size metric |
| HAPI `ValidationSupportChain` internal caches holding stale profiles | Call `invalidateCaches()` on profile change. Integration test registers, updates and re-validates (B1/B2 regression) |
| NOTIFY lost while the subscriber reconnects | Poll fallback every 5 s compares revisions (R4) |
| Breaking API changes surprise existing clients | 1.0.0 with a CHANGELOG migration table. Anonymous validate stays on by default. The JSON response shape is unchanged for `application/json` |
| Testcontainers in CI on arm64 | Run Java tests on amd64 runners. arm64 is covered by the buildx image build and a QEMU smoke test of startup only |

## Complexity Tracking

No constitution violations to justify. The three new runtime dependencies are justified in
research R13 (Principle VIII allows them with justification).
