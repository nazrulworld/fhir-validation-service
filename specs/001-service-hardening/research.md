# Phase 0 Research: Service Hardening

**Feature**: [spec.md](./spec.md) · **Evidence**: [audit-findings.md](./audit-findings.md) · **Date**: 2026-10-25

Each decision lists what was chosen, why, and what was rejected. IDs in brackets point to audit
findings and spec requirements.

---

## R1. Validation concurrency model [P1, FR-007, FR-010]

**Decision**: Run validations on a dedicated named `WorkerExecutor` (`fv-validation`), with pool
size `FV_VALIDATION_WORKERS` (default = available processors). Submit with
`executeBlocking(Callable, ordered=false)`. An admission counter in front of it caps in-flight plus
queued work at `FV_VALIDATION_MAX_PENDING` (default 4 × workers). Above the cap, return
**503 + `Retry-After`** immediately. Deploy the HTTP verticle with `FV_HTTP_INSTANCES` instances
(default = processors, at most 8), all sharing one process-wide `ValidatorRegistry`.

**Rationale**: The current deprecated `executeBlocking(Handler)` defaults to `ordered=true`, which
serialises all work on the single verticle context. That is the root cause of one-core
throughput. A named pool also keeps package loading (R5) separate from validation, so one cannot
starve the other. The bounded admission counter satisfies Principle VI (no unbounded queues).

**Alternatives rejected**:
- Only setting `ordered=false` on the default worker pool: package loading and Flyway would still
  compete with validation for the same 20 threads.
- One validator per verticle instance: validators are expensive to build (Principle II), so N
  copies would multiply memory.

## R2. Atomic validator configuration (copy-on-write snapshots) [B4, B5, FR-004, FR-006]

**Decision**: Treat each validator as an **immutable snapshot**: FHIR context, a support chain with
a fresh `NpmPackageValidationSupport` holding exactly the resolved package set, and the profile
support. `ValidatorRegistry` holds one `AtomicReference<ValidatorSnapshot>` per FHIR version, with a
`revision` number. To change the IG set, build a new snapshot on the `fv-packages` pool and then
swap the reference. In-flight validations finish on the snapshot they started with.

**Rationale**: This fixes the mixed-version leftovers (B5), because a new snapshot never holds old
content, and the races (B4), because nothing is mutated. It also gives FR-006 atomicity for free.
Static `HashMap`s go away (baseline deviation 1).

**Alternatives rejected**:
- Removing resources from the existing support: HAPI has no public removal API, and it would
  still race with readers.
- A read/write lock around validation: blocks all validation for seconds while an IG loads.

## R3. Custom profiles [B1, B2, B17, FR-001, FR-022]

**Decision**: When a snapshot is built, **preload all profiles** for that FHIR version from
`fhir_profiles` into a `ConcurrentHashMap<canonicalUrl, StructureDefinition>`. This map backs
`CustomProfileValidationSupport`, which becomes a pure in-memory lookup with no Future and no
`.result()`. Registering a profile does three things:
1. Upsert the row and bump the version's configuration revision in one transaction (R4).
2. On commit, every instance (including the local one) reloads the changed profile into its map.
3. Call `ValidationSupportChain.invalidateCaches()` on the current snapshot.

The map keys are exact canonical URLs, so the lossy cache key that caused B17 goes away. The 24 h
Caffeine profile cache and the negative-cache `EmptyResource` are deleted.

**Rationale**: `.result()` on a pending Future is the B1 bug. Profiles are small: thousands at
most, a few MB. Preloading makes every request see a consistent view, including the first one.

**Alternatives rejected**:
- Blocking `toCompletionStage().get()` inside `fetchStructureDefinition`: prohibited by
  Principle I, and holds a worker thread on a DB round trip.
- A full snapshot rebuild per profile registration: correct but slow (seconds) for a frequent,
  small change.

## R4. Cross-instance propagation [B2, B3, FR-002, FR-003, SC-002]

**Decision**: Store a `revision BIGINT` per FHIR version in `fhir_validator_logs` (renamed
conceptually to *validator configuration*, see data-model). Every configuration change (IG include,
profile upsert):
1. Increments `revision` and calls `pg_notify('fv_config_changed', '{"fhirVersion":"R4","revision":42,"kind":"IG|PROFILE","key":"..."}')`
   **in the same transaction**. PostgreSQL delivers the notification only on commit.
2. Each instance runs a `PgSubscriber` on that channel. When a notification arrives for a revision
   newer than its own, the instance reloads the profile (kind PROFILE) or rebuilds the snapshot
   (kind IG).
3. As a safety net, each instance polls `SELECT fhir_version, revision` every
   `FV_CONFIG_POLL_INTERVAL` (default 5 s). This covers notifications missed during a reconnect.

**Rationale**: It uses only PostgreSQL, the sole shared infrastructure (Principle II). Notify is
near-instant, and the poll caps staleness at 5 s, well inside the 10 s target in SC-002.

**Alternatives rejected**:
- Redis pub/sub or a clustered event bus: new infrastructure; Principle II requires amending the
  constitution for that.
- Short cache TTLs only: either stale or wasteful, and can't guarantee SC-001.

## R5. Package loading off the event loop and in-memory package cache [P2, P3, FR-008, FR-009]

**Decision**: Create a second named `WorkerExecutor` `fv-packages` (size `FV_PACKAGE_WORKERS`,
default 2). Every call to `NpmPackage.fromPackage`, every resource parse and every snapshot build
runs there. The event loop only moves bytes (DB to buffer). Add a Caffeine
`Cache<IgPackageName, NpmPackage>` with a **byte-weighted** limit `FV_PACKAGE_CACHE_MAX_MB`
(default 256), weighted by compressed size × 4 as an estimate of expanded size.

**Rationale**: Unpacking and parsing large IGs on the event loop is the cause of P2. The named
pool also caps concurrent heavy loads, which protects memory.

**Alternative rejected**: The default worker pool, because it shares capacity with Flyway and
anything else that runs there.

## R6. Deterministic, version-aware dependency resolution [B6, FR-005]

**Decision**: Resolve dependencies **breadth-first** from the included IGs, using these rules:
1. Skip core packages (`hl7.fhir.rX.core`), as today.
2. A version spelled out on the dependency is used exactly.
3. A missing version, `current` or `latest` resolves to the highest stored version by R7 ordering.
   If none is stored and registries are enabled, use the registry's latest.
4. If the same package is reached with different versions, keep the **highest by R7 ordering** and
   log a warning with both paths.
5. Cycles are cut by the visited set, keyed by name.

Persist the resolved set (`name#version` list) as `resolved_ig_packages` on the configuration row
so every instance builds the same snapshot without resolving again.

**Rationale**: These rules make resolution reproducible (Principle III). Persisting the resolved
set removes any sensitivity to load order.

**Alternative rejected**: "first one reached wins", which is today's behaviour and depends on
timing.

## R7. Version ordering for "latest" [B7, B19, FR-025]

**Decision**: Write a small in-house `PackageVersionComparator`, with an exhaustive unit test
table. It follows SemVer 2.0 precedence: numeric major.minor.patch, then pre-release tags rank
below the release; build metadata is ignored. Labels that aren't SemVer (`current`, `dev`) sort
below every SemVer label. "latest" is computed in Java over
`SELECT ig_package_version … WHERE ig_package_id = $1`, so `created_at` ordering goes away.

**Rationale**: About 80 lines, no new dependency (Principle VIII). FHIR package versions are close
to SemVer.

**Alternative rejected**: `semver4j`, a dependency for something this small.

## R8. Authentication and authorisation [F2, FR-012]

**Decision**: Use **API keys** carried as `Authorization: Bearer <key>`. Keys are stored only as
SHA-256 hashes in a new `api_keys` table with a role of `ADMIN` or `VALIDATE`. Declare security
schemes in `openapi.yaml` and mount a `RouterBuilder.securityHandler("apiKey", …)`.
**As implemented**: enforcement is one router root handler (`ApiKeyAuthHandler.rootHandler`) mounted before
contract validation, so unauthenticated calls are refused before their input is examined; the security scheme
stays in `openapi.yaml` as documentation. Read endpoints follow `FV_AUTH_ANONYMOUS_VALIDATE` like validation.
- Admin operations (`/igs/*` writes, `include-ig`, `register-profile`) always require `ADMIN`.
- Validation requires `VALIDATE` or `ADMIN`, unless `FV_AUTH_ANONYMOUS_VALIDATE=true` (default
  `true` in 1.0 for compatibility, documented as discouraged).
- Bootstrap: when `FV_ADMIN_API_KEY` is set, its hash is upserted at startup as the `bootstrap`
  admin key. With no admin key configured, admin operations are refused (secure by default).
- Health, readiness and metrics stay anonymous.

**Rationale**: No identity provider is needed, it suits service-to-service calls, and the
contract-first security handler support already exists in vertx-web-openapi.

**Alternative rejected**: OAuth2/OIDC (`vertx-auth-oauth2`). It needs an IdP in every deployment,
so it's deferred to a later feature (FT7).

## R9. SSRF-safe downloads [F1, FR-013]

**Decision**: `downloadUrl` and registry fetches go through one `SafePackageFetcher`:
- Scheme must be `https`. The host must be in `FV_PACKAGE_SOURCES` (default `packages.fhir.org,
  packages2.fhir.org`).
- The host is resolved before connecting, and the request is refused if any address is loopback,
  link-local, site-local/private (RFC 1918, fc00::/7), multicast or unspecified. The resolved IP
  is pinned for the connection.
- Redirects are followed manually, at most 3, and each hop is re-checked.
- Connect/read timeouts come from `FV_PACKAGE_FETCH_TIMEOUT_MS` (default 30000).
- The response size is capped by R10 while streaming.

**Rationale**: Matches the constitution's "restricted to configured registries" rule and blocks
DNS-rebinding tricks.

## R10. Archive safety and bounded buffering [F3, P4, FR-014, SC-007]

**Decision**: Add a `PackageArchiveGuard` that streams the `.tgz` through commons-compress
(`GzipCompressorInputStream` + `TarArchiveInputStream`, already pulled in by
`org.hl7.fhir.utilities`) **before** `NpmPackage.fromPackage`. It enforces:
- compressed size ≤ `FV_PACKAGE_MAX_COMPRESSED_MB` (50)
- expanded size ≤ `FV_PACKAGE_MAX_EXPANDED_MB` (500)
- entries ≤ `FV_PACKAGE_MAX_ENTRIES` (20000)
- ratio ≤ `FV_PACKAGE_MAX_RATIO` (100)
- no entry names that are absolute or contain `..`

Downloads stream through a counting `WriteStream` that aborts past the limit. Uploads already have
a body limit, now configurable as `FV_HTTP_MAX_BODY_MB` (default 20, applied to validation and
upload bodies alike). The upload file is opened as a stream on the packages pool instead of
`readFile` into the heap.

**Rationale**: Checking before parsing means a bomb never expands in memory.

## R11. Content negotiation and response formats [B12, B13, FR-017]

**Decision**:
- Remove the `enum` constraints on the `Accept` and `Content-Type` header parameters in
  `openapi.yaml`, and parse them in code with a proper media-type parser (q-values, wildcards,
  parameters).
- Request body: `*/json` or `*+json` → JSON, `*/xml` or `*+xml` → XML. Anything else → **415**.
- Response mapping:

  | Accept | Response |
  |--------|----------|
  | absent, `*/*` or `application/json` | the existing `ValidationResponse` JSON shape (unchanged for current clients) |
  | `application/fhir+json` | a FHIR `OperationOutcome` (JSON) |
  | `application/xml`, `application/fhir+xml` | a FHIR `OperationOutcome` (XML) |
  | none of these acceptable | **406** |

**Rationale**: `OperationOutcome` is the only standard XML shape for validation results
(Principle III: SHOULD be expressible as OperationOutcome), and it keeps current JSON clients
unchanged.

## R12. Status codes and error format [B10, B16, F9, FR-018–FR-020]

**Decision**:

| Situation | Status | Body |
|-----------|--------|------|
| Resource parsed and validated (valid or not) | **200** | Result, with the `valid` flag |
| Body not parseable as FHIR in the declared format | **400** | Problem (type `invalid-resource`), with the parse failure in the RFC 9457 extension member `issues: [{severity:"FATAL", location, message}]` |
| Bad parameters / unknown FHIR version | **400** | Problem |
| Missing / insufficient credentials | **401 / 403** | Problem |
| Unknown package / profile on read or register-by-name | **404** | Problem |
| Unsupported media types | **415 / 406** | Problem |
| Body over limit | **413** | Problem |
| Overload or not ready | **503** + `Retry-After` | Problem |
| Unexpected failure | **500** | Problem |

Every non-200 response, including the 400 for an unparseable body, uses **RFC 9457
`application/problem+json`** (one error format, constitution Principle IV): `{type, title, status, detail,
correlationId}`. `detail` carries curated messages only, never exception text. One
`ErrorResponder` plus a router `errorHandler` per status code (and a catch-all failure handler)
guarantees every request gets a response (FR-020). Every async chain ends in a single
`respond(ctx, Future)` helper. This removes the double-response code (B20) and the NPE hangs
(B10, B12).

**Compatibility**: Status code and body changes are breaking, so the release is **1.0.0**
(Principle IV), with a migration note in the new `CHANGELOG.md` (baseline deviation 7).

## R13. Observability [F5, FR-011, FR-031]

**Decision**:
- **Metrics**: `vertx-micrometer-metrics` with `micrometer-registry-prometheus`, exposed at
  `GET /metrics`. Built-in HTTP server and pool metrics, plus custom ones:
  `fv_validations_total{version,outcome}`, `fv_validation_seconds` histogram,
  `fv_validation_pending`, `fv_config_revision{version}`, `fv_package_cache_*` and
  `fv_profile_count{version}`.
- **Correlation id**: `X-Request-Id` (accepted if it matches `[A-Za-z0-9-]{8,64}`, otherwise
  generated), echoed in the response, and put in the log context through
  `reactiverse-contextual-logging`. That library propagates Vert.x context-local data into Log4j2
  MDC across async callbacks. Worker tasks are wrapped to copy it.
- **Probes**: `GET /health/liveness` stays process-only. New `GET /health/readiness` returns 200
  only when all supported versions have a snapshot, the DB answered within the last
  `FV_READINESS_DB_STALENESS_MS`, and the instance isn't draining. `/health` remains a detailed
  report.

**New dependencies (justified under Principle VIII)**: Micrometer is the Vert.x-native way to meet
the Principle VII metrics requirement; hand-rolled Prometheus text output was rejected as
reinventing it. Contextual logging solves MDC loss on the event loop; manual ThreadContext
handling was rejected as error-prone across callbacks.

## R14. Single configuration mechanism [F6, D6, FR-028]

**As implemented**: a dependency-free `ConfigLoader` replaced vertx-config (removed from `pom.xml`). It
merges the same three sources in the same order, synchronously at startup, with an injectable environment map for
tests; vertx-config added nothing beyond that. The rest of this decision is unchanged.

**Decision**: Use only vertx-config. Store order: classpath `config.json` (defaults) → optional
file at `FV_CONFIG_FILE` → **environment variables** (`type: env`), mapped through a documented
key table (see [contracts/configuration.md](./contracts/configuration.md)). `ApplicationConfig`
becomes a typed facade over the merged `JsonObject`, read once at bootstrap. Legacy `PG_*` names
remain accepted aliases. `http.port` gets an env override (`FV_HTTP_PORT`) to fix D6. Unused keys
(`retries`, `backoff`) are deleted. The password-prefix log line goes away (B18), as do the
default password in the code, `application*.properties` and `pg.password` fallbacks. A missing
password becomes a startup error unless `FV_PROFILE=dev`.

## R15. Graceful shutdown [F7, D5, FR-029, SC-008]

**Decision**:
- **JVM**: a shutdown hook calls `vertx.close()`. The hook thread (not an event loop) waits on a
  `CountDownLatch` released from `vertx.close().onComplete(...)`, with a timeout. It never calls
  `.get()`/`.join()` on a future (Principle I). `MainVerticle.stop()` marks the instance
  *draining*, so readiness fails, and waits `FV_SHUTDOWN_PRE_DRAIN_MS` (default 5000) so load
  balancers notice. It then closes the HTTP server (stop accepting), waits for in-flight requests
  until `FV_SHUTDOWN_GRACE_MS` (default 20000), and closes the PG pool and executors.
- **Container**: the JVM is PID 1 through `exec`, with `tini` as init, so SIGTERM reaches the JVM
  directly. Compose sets `stop_grace_period: 30s`.

## R16. Container image [D1, D2, D5, D6, D7, D8, D11, FR-027, FR-032, FR-034]

**Decision**:
- **Builder stage**: `maven:3.9-eclipse-temurin-17` on `$BUILDPLATFORM`, with
  `dependency:go-offline` cache mount. `package -DskipTests`, because tests run in CI before the
  image build (R18).
- **Runtime stage**: `eclipse-temurin:17-jre-jammy`. The only added packages are `tini` and
  `curl` (for the health check). There's no PostgreSQL, gosu or netcat, and no `VOLUME`.
- **User**: `USER 10001:10001` (`fhir`). `/app` is owned by root and read-only to the app. A
  writable `/tmp` holds uploads.
- **Entrypoint and command**: `ENTRYPOINT ["/usr/bin/tini","--"]`,
  `CMD ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]`. The default `JAVA_OPTS` is
  `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`. The hard-coded DNS servers are removed.
- **Port and health**: `EXPOSE 8880` (the default for `FV_HTTP_PORT`).
  `HEALTHCHECK CMD curl -fsS http://127.0.0.1:${FV_HTTP_PORT:-8880}/health/readiness`, with
  `--start-period=120s`.
- **Labels**: OCI labels (`org.opencontainers.image.*`) with version and revision passed as build
  args.
- **Files deleted**: `docker/docker-entrypoint.sh` (replaced by a direct exec),
  `docker/init-scripts/wait-for-services.sh` (the app retries the DB connection itself and stays
  not-ready meanwhile), `docker/postgres-init/init-database.sh`, and `application-docker.properties`
  (replaced by env vars).
- **Kept**: `docker/postgres-init/01-init.sql`, mounted into the compose postgres service. It's
  harmless, since Flyway creates the schema anyway.

**All-in-one dev image**: dropped. Compose (R17) gives the same one-command experience without a
second image to maintain.

## R17. Compose layout [D2, D3, D4, D9, FR-033, SC-010]

**Decision**: A single `compose.yaml` (renamed from `docker-compose.yml`) with **profiles**:

| Service | Profile | Notes |
|---------|---------|-------|
| `postgres` | default | `postgres:16.8-alpine` pinned, healthcheck `pg_isready`, named volume, **not published** (opt-in via a git-ignored `compose.override.yaml`) |
| `validator` | default | `image: ghcr.io/nazrulworld/fhir-validation-service:${FV_TAG:-latest}` + `build: .`, `depends_on: postgres (service_healthy)`, `env_file: .env`, port `127.0.0.1:${FV_HTTP_PORT:-8880}:8880`, `mem_limit` from `.env`, `stop_grace_period: 30s`, healthcheck on readiness |
| `validator-2` + `lb` (nginx:1.27-alpine) | `cluster` | Two instances behind round-robin on `:8881`, for the SC-001/002 multi-instance checks |
| `prometheus` (pinned `v2.53.x`), `postgres-exporter` (pinned `v0.15.x`) | `observability` | No `platform:` pins; scrapes `validator:8880/metrics` |
| `pgadmin` (pinned) | `admin` | Bound to `127.0.0.1:5050`, credentials required from `.env` (no defaults) |

**Secrets**: `.env` is removed from git (`git rm --cached`) and added to `.gitignore`. A committed
`.env.example` documents every variable with placeholders. Compose uses `${PG_PASSWORD:?set
PG_PASSWORD in .env}` so it refuses to start without one. The Redis/Ollama leftovers and
`DOCKER_DEFAULT_PLATFORM` go away. The Makefile gains `up`, `up-cluster`, `up-obs`, `down`, `logs`
and `smoke` targets, and `stop-all` is removed.

**Alternative rejected**: several override files (`compose.override.yml`, …). Profiles keep one
file and are standard Compose v2.

## R18. CI publishing pipeline [D10, FR-035]

**Decision**:
- **`docker-publish.yml`** gets three jobs:
  1. `test`: `mvn -B verify` with Testcontainers on ubuntu-latest Docker.
  2. `image`: needs `test`. Buildx for amd64+arm64 with `hadolint` lint. It first builds amd64
     with `load: true`, then runs a **smoke test**: `docker compose up -d --wait`, a ready check,
     validating a fixture with `curl`, and checking that the response is valid JSON with
     `valid:true`. Then `trivy` scans and fails on CRITICAL.
  3. `push`: needs `image`, multi-arch, runs only on `main` and on releases. Tags are `sha`,
     semver, and `latest` only for releases (not every main push), so `latest` means "latest
     release".
- **`maven-publish.yml`**: attaches `compose.yaml` and `.env.example`, which work standalone
  because the validator service has an `image:` reference. It runs `mvn verify` rather than
  `package` without tests.
- **Image name**: `ghcr.io/${{ github.repository }}`, with `.env.example` kept in sync.

## R19. Removing per-client validators and unsupported versions [B14, B15, FR-016, FR-021]

**Decision**:
- Delete the UUID/`api_clients` branch in `ValidationRequestContext` and the `ValidatorIdentity`
  custom-id constructor path. `ValidatorIdentity` collapses to `SupportedFhirVersion`.
- Add an explicit `SupportedFhirVersion.isEnabled()` set: STU3, R4, R4B, R5. R6 stays in the enum
  (it's in the DB enum type) but is disabled.
- Create **STU3** validators, which fixes the STU3 gap (B15). Every endpoint validates the version
  through one `FhirVersionParam` helper. `getOrDefault` mapping goes away.
- The `openapi.yaml` `FhirVersion` enum stays `[STU3, R4, R4B, R5]`, and matching becomes
  case-insensitive (lowercase is accepted and normalised).

## R20. Testing strategy (Principle V)

| Layer | Tooling | Covers |
|-------|---------|--------|
| Unit | JUnit 5 | `PackageVersionComparator`, media-type negotiation, `PackageArchiveGuard` (crafted bomb/traversal tars generated in-test), SSRF address classifier, dependency resolver (fixture graphs) |
| Integration | Testcontainers PG via `BaseTestContainer` | Snapshot swap and B5 regression, profile first-use (B1 regression), NOTIFY propagation between **two Vert.x instances in one JVM sharing one DB** (B2/B3), migrations V3/V4 on an empty DB and on top of the V2 schema |
| Contract | — | Every operation in `openapi.yaml`, with the header-variant matrix and an error-shape assertion; no hung requests (each test has a 5 s timeout) |
| Load | k6 script in `perf/` | SC-003 and SC-004, run manually or nightly; results attached to the PR per Principle VI |
| Container | CI smoke test (R18) | Plus `scripts/smoke.sh`, reused by `make smoke` |
