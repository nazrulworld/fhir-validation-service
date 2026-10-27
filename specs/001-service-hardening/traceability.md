# Traceability: audit findings → tasks → regression tests (SC-009)

Every finding in [audit-findings.md](./audit-findings.md) is closed by a test, or has a recorded reason why not.
Test classes are under `src/test/java/nzi/fhir/validator/`; scripts under `scripts/`.

## Bugs

| Finding | Closed by | Regression test |
|---------|-----------|-----------------|
| B1 profiles skipped on first use | T035, T036, T043 | `core/service/ProfileFirstUseIT` |
| B2 profile changes not propagated | T042, T043, T045 | `core/validator/ConfigPropagationIT` |
| B3 include-ig only on one instance | T044, T045 | `core/validator/ConfigPropagationIT` |
| B4 shared validator mutated concurrently | T038–T041 | `core/validator/ValidatorSnapshotSwapIT` (200 concurrent validations during the swap) |
| B5 old IG version content left behind | T038, T040 | `core/validator/ValidatorSnapshotSwapIT` |
| B6 non-deterministic dependency resolution | T037, T044 | `core/npm/DependencyResolverTest` |
| B7 "latest" = most recent upload | T015, T082 | `core/npm/PackageVersionComparatorTest`, `core/npm/PackageLatestResolutionIT` |
| B8 version labels > 16 chars | T014, T082 | `core/service/MigrationUpgradeIT`, `core/npm/PackageLatestResolutionIT` |
| B9 registry fallback race | T083 | `core/npm/RegistryFallbackTest` |
| B10 hanging register of unknown IG | T077, T083 | `web/endpoint/IgPackageApiContractTest#registerUnknownPackageByNameIs404Promptly` |
| B11 version reported as name | T077 | `web/endpoint/IgPackageApiContractTest#downloadReportsActualNameAndVersion` |
| B12 `*/*` / charset headers break validation | T073, T076, T079 | `core/model/MediaTypesTest`, `web/endpoint/ValidationApiContractTest` |
| B13 JSON body labelled XML | T074, T076 | `web/endpoint/ValidationApiContractTest#fhirXmlAcceptReturnsOperationOutcomeXml` |
| B14 half-built per-client validators | T075 | `web/endpoint/ValidationApiContractTest#uuidValidatorPathIsRejected` |
| B15 R6 stored as R4 / STU3 unused | T017, T048, T078 | `core/model/SupportedFhirVersionTest`, `web/endpoint/ProfileApiContractTest`, `core/service/FhirValidationServiceTest#everyEnabledVersionValidates` |
| B16 failures reported as invalid; leaked messages | T018, T076 | `web/ErrorResponderTest`, `testsupport/ContractAssertions` (all contract tests) |
| B17 profile cache key collisions | T035 | `core/validator/ProfileStoreTest` |
| B18 password logged / short password crash | T011, T013 | `core/config/ConfigLoaderTest#passwordIsNeverPartOfToString`, `core/config/NoSecretsInLogsTest` |
| B19 one unreachable registry breaks latest lookup | T083 | `core/npm/RegistryFallbackTest#latestLookupIgnoresUnreachableRegistries` |
| B20 double response writes in include-ig | T047 | `web/ErrorResponderTest#failedFutureEndsResponseExactlyOnce`; include-ig paths in `ValidationApiContractTest` |
| B21 fake conformance report / inverted 404 | T077 | `web/endpoint/IgPackageApiContractTest`, `core/service/IgPackageServiceTest#whenListPackageFiles_thenSucceeds` |

## Performance

| Finding | Closed by | Regression test |
|---------|-----------|-----------------|
| P1 one validation at a time | T016, T053, T054 | `core/service/ConcurrencyIT` (4 validations must run at once) |
| P2 IG parsing on the event loop | T040, T055 | `web/EventLoopResponsivenessIT` (no blocked event loop, probes < 1 s during upload + include) |
| P3 packages re-read on every rebuild | T056 | Covered indirectly (`fv_package_cache_*` metrics in `web/ObservabilityIT`); no dedicated hit-rate test |
| P4 unbounded upload/download buffering | T066–T068 | `core/npm/PackageArchiveGuardTest`, `core/npm/SafePackageFetcherTest#oversizedChunkedResponseIsAbortedWhileStreaming`. Uploads are still read whole, but only up to `FV_HTTP_MAX_BODY_MB` |
| P5 slow, opaque startup | T022, T048, T040 (warm-up) | `BootstrapIT#livenessAnswersAndUnknownRouteIsProblem` (liveness before validators) |
| P6 duplicate registry downloads | T083 | `core/npm/RegistryFallbackTest#slowerRegistryWithThePackageWinsOverFast404` (stored once) |
| P7 transaction for single SELECT; duplicate services | T084 | Closed by code review, without an FR: an internal code-quality finding with no observable behaviour to specify |

## Flow, architecture, security

| Finding | Closed by | Regression test |
|---------|-----------|-----------------|
| F1 SSRF via downloadUrl | T067, T068 | `core/npm/SafePackageFetcherTest` |
| F2 no authentication | T062–T065 | `web/ApiKeyAuthIT` |
| F3 archives not sanitised | T066 | `core/npm/PackageArchiveGuardTest` |
| F4 database bundled in the image | T024, T026 | `scripts/smoke.sh` (separate postgres service); CI `container` job |
| F5 health / observability gaps | T023, T057, T088 | `web/ObservabilityIT`, `core/validator/ConfigPropagationIT` (readiness on STALE), `web/endpoint/HealthApiTest` |
| F6 two configuration systems | T011, T012 | `core/config/ConfigLoaderTest` |
| F7 no graceful shutdown | T089 | `web/GracefulShutdownIT`; `scripts/smoke.sh` (SIGTERM stop) |
| F8 static HashMap registries | T041, T046 | Replaced by `ValidatorRegistry`; covered by `ConfigPropagationIT`, `ValidatorSnapshotSwapIT` |
| F9 three error shapes | T018 | `testsupport/ContractAssertions` in every contract test |
| F10 no OperationOutcome / applied profiles | T074 | `web/endpoint/ValidationApiContractTest#fhirJsonAcceptReturnsOperationOutcomeJson`, `#profileQueryParameterIsApplied` |

## Docker build, compose, CI

| Finding | Closed by | Verification |
|---------|-----------|--------------|
| D1 unbuildable image (`appuser`) | T024 | `scripts/smoke.sh` builds the image; CI `container` and `arm64-build` jobs |
| D2 database in the app image | T024, T026 | `scripts/smoke.sh` |
| D3 database exposed with default credentials | T026, T090 | `docker compose config` in CI; no published postgres port, `${PG_PASSWORD:?}` |
| D4 committed `.env` | T003 | `.gitignore`; `.env.example`. **Deferred**: rewriting git history; the guide requires rotating that password (docs/upgrade-1.0.md) |
| D5 broken signal handling | T024 (tini + exec) | `scripts/smoke.sh` graceful-stop step |
| D6 unconfigurable port | T011, T022, T024 | `core/config/ConfigLoaderTest#fileOverridesClasspathAndEnvOverridesFile` |
| D7 hard-coded DNS, no JAVA_OPTS | T024 | Dockerfile review; `JAVA_OPTS` honoured by `CMD` |
| D8 health check tied to the database | T024, T026 | Compose/Docker health checks use `/health/readiness`; liveness separate |
| D9 compose defects | T026, T049, T090 | `docker compose --profile cluster --profile observability --profile admin config`; cluster run in containers (IG propagated to both instances) |
| D10 untested publishing | T029, T091, T092 | CI workflow structure (test → image + smoke + scan → push) |
| D11 fragile first RUN, old builder, "continue anyway" | T021, T024 | `DatabaseService` retries until the database is reachable (`NoSecretsInLogsTest` exercises the retry path) |
| D12 Makefile leftovers | T028 | Closed by Makefile review, without an FR: developer tooling outside the service's specified behaviour |

## Deferred / not covered by automated tests

- **D4 history**: the old `.env` stays in git history. The password it held is treated as public; rotate it (upgrade guide step 0).
- **P3, P7**: no dedicated behavioural tests (cache hit rate, query shape).
- **SC-003, SC-004 (performance numbers)**: measured with `perf/` k6 scripts and attached to the PR, not part of `mvn verify`.
- **SC-008 (rolling restart < 0.1 % failures)**: drain is tested per instance (`GracefulShutdownIT`); the fleet-wide rolling restart under load is `scripts/rolling-restart.sh` (cluster profile, k6 through the load balancer, fails at ≥ 0.1 %), run on demand rather than in `mvn verify`. Result 2026-10-26: 34,113 requests, 0.035 % failed (T130).

## Requirements without an acceptance scenario (CHK050)

Most FRs are exercised by a user-story scenario, an edge case or a success criterion. These are covered by tests or
by a review instead:

| Requirement | Covered by |
|-------------|------------|
| FR-009 package reuse | No dedicated test (see P3 above). Cache effectiveness is visible in `fv_package_cache_*` metrics |
| FR-023 read-endpoint 404s | `web/endpoint/IgPackageApiContractTest#dependenciesOfUnknownPackageIs404`, `core/validator/ValidatorSnapshotSwapIT#includeOfUnknownPackageIs404` |
| FR-031 metrics and correlation ids | SC-011; `web/ObservabilityIT` |
| FR-032 non-root, signals, multi-arch | Dockerfile review (`USER 10001:10001`, tini); `scripts/smoke.sh` graceful stop; CI `container` (amd64) and `arm64-build` (QEMU startup smoke) |
| FR-034 health checks | Compose and Dockerfile health checks use `/health/readiness` (D8 above) |
| FR-035 publishing gates | CI workflow structure (D10 above): tests, image smoke test and a CRITICAL-severity scan run before the push job |
| FR-021 IG version mismatch (edge case) | `web/endpoint/IgPackageApiContractTest#includeOfAnIgForAnotherFhirVersionIs400`, `core/service/DeclaredFhirVersionTest` |
