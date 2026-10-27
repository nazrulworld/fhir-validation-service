---

description: "Task list for 001-service-hardening"
---

# Tasks: Service Hardening — Correctness, Throughput and Safe Administration

**Input**: Design documents from `specs/001-service-hardening/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md), [audit-findings.md](./audit-findings.md)

**Tests**: Included and written **first**. Constitution Principle V makes test-first mandatory, and spec SC-009 requires a regression test for every closed finding. Each test task must fail before its implementation task is done.

**Organization**: Tasks are grouped by user story. Finding IDs (B/P/F/D) and requirement IDs (FR/SC) are given in brackets for traceability.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: User story from spec.md (US1–US6)

## Path Conventions

Single Maven module at the repository root. Every path below is relative to the root:

- Main code: `src/main/java/nzi/fhir/validator/…`
- Tests: `src/test/java/nzi/fhir/validator/…`
- Integration tests (`*IT.java`) extend `src/test/java/nzi/fhir/validator/testcontainers/BaseTestContainer.java` (real PostgreSQL)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Version, dependencies, repository hygiene

- [X] T001 Update `pom.xml`: set `<version>1.0.0-SNAPSHOT</version>`, and change `<description>` to "A Vert.x-based FHIR validation service with PostgreSQL". Add version properties `vertx-micrometer` (= `${vertx.version}`), `micrometer.version` (1.12.x) and `contextual-logging.version` (1.2.x). Add dependencies `io.vertx:vertx-micrometer-metrics`, `io.micrometer:micrometer-registry-prometheus` and `io.reactiverse:reactiverse-contextual-logging`, each using its property (no inline versions). Point the shade plugin `Main-Class` at `nzi.fhir.validator.Bootstrap`
- [X] T002 [P] Create `CHANGELOG.md` with an `## [1.0.0] - Unreleased` section and empty `### Breaking`, `### Added`, `### Fixed`, `### Removed` subsections (Keep a Changelog format)
- [X] T003 [P] Untrack secrets: run `git rm --cached .env` and add `.env` to `.gitignore`. Create `.env.example` listing **every** variable in `specs/001-service-hardening/contracts/configuration.md`, plus the compose variables `FV_TAG`, `FV_MEMORY_LIMIT`, `PGADMIN_DEFAULT_EMAIL` and `PGADMIN_DEFAULT_PASSWORD`. Use placeholder values only (e.g. `PG_PASSWORD=change-me`). Remove the Redis/Ollama variables and `DOCKER_DEFAULT_PLATFORM` [D4, D9]
- [X] T004 [P] Update `.dockerignore` to also exclude `specs/`, `.specify/`, `.claude/`, `perf/`, `scripts/`, `.env*` and `*.md`. Add `.hadolint.yaml` with `failure-threshold: warning`
- [X] T005 [P] Create the test helper `src/test/java/nzi/fhir/validator/testsupport/Fixtures.java`. Its static methods load `src/test/resources/fhir/resources/dk/*.json`, `src/test/resources/fhir/profiles/dk/*.json`, `src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz` and `src/test/resources/ig/packages/hl7/fhir/us/core/hl7.fhir.us.core-7.0.0.tgz` as `Buffer`/`String`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Configuration, errors, executors, schema V3, process split, the version helper, and the deployment base (image, compose, smoke test, CI). Every story depends on this phase.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Tests (write first, must fail)

- [X] T006 [P] `ConfigLoaderTest` in `src/test/java/nzi/fhir/validator/core/config/ConfigLoaderTest.java`. Assert store precedence: classpath `config.json` < `FV_CONFIG_FILE` < env. Assert legacy aliases (`PG_HOST` → `pg.host`). Assert that an out-of-bounds value (e.g. `FV_HTTP_PORT=70000`) fails startup with a message naming `FV_HTTP_PORT`. Assert that a missing `FV_PG_PASSWORD` fails unless `FV_PROFILE=dev`. Assert that `FV_CORS_ALLOWED_ORIGINS=*` is refused when `FV_PROFILE=prod` [FR-028, B18]
- [X] T007 [P] `PackageVersionComparatorTest` in `src/test/java/nzi/fhir/validator/core/npm/PackageVersionComparatorTest.java`. A table-driven test covering: `2.0.0 > 1.5.0`, `1.10.0 > 1.9.0`, `1.0.0 > 1.0.0-ballot`, `1.0.0-ballot2 > 1.0.0-ballot1`, `1.0.0+build` = `1.0.0`, and that `current`/`dev`/`cibuild` sort below every SemVer label. It must not throw on any input [FR-025, B7, B19]
- [X] T008 [P] `ErrorResponderTest` in `src/test/java/nzi/fhir/validator/web/ErrorResponderTest.java`. A route that throws `new RuntimeException("secret-internal")` must produce `500`, `Content-Type: application/problem+json`, a body with `type`, `title`, `status` and `correlationId`, and a body that doesn't contain `secret-internal` or `java.`. A failed `Future` passed to `respond(ctx, future)` ends the response exactly once [FR-019, FR-020, B16]
- [X] T009 [P] `MigrationUpgradeIT` in `src/test/java/nzi/fhir/validator/core/service/MigrationUpgradeIT.java`. (a) On an empty database, Flyway V1→V3 succeeds. (b) On a database migrated only to V2 and containing `fhir_implementation_guides` and `fhir_validator_logs` rows, V3 succeeds and the data is kept. Assert that `ig_package_version` accepts a 64-character label, that `fhir_validator_logs.revision` defaults to `1`, that rows exist for STU3/R4/R4B/R5, and that `resolved_ig_packages = included_ig_packages` after the backfill [FR-024, B8]
- [X] T010 [P] `SupportedFhirVersionTest` in `src/test/java/nzi/fhir/validator/core/model/SupportedFhirVersionTest.java` (extend the existing file). `parseEnabled("r4")` returns `R4`. `parseEnabled("STU3")` returns `STU3`. `parseEnabled("R6")`, `parseEnabled("2b6c…uuid")` and `parseEnabled(null)` return empty. `R6.isEnabled()` is false [FR-021, B15]

### Implementation

- [X] T011 Create `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java`. Build a `ConfigRetriever` with stores in this order: classpath `config.json` → optional file `FV_CONFIG_FILE` → `type: env`. Map env names to config keys exactly as in `contracts/configuration.md`, including the `PG_*` aliases. Validate every bound listed there, and fail with `IllegalStateException("Invalid FV_XXX: …")` on a violation (makes T006 pass)
- [X] T012 Rewrite `src/main/java/nzi/fhir/validator/core/config/ApplicationConfig.java` as an immutable typed facade over the merged `JsonObject`, created once by `ConfigLoader`. Keep `DB_POSTGRES_SCHEMA_NAME`. Delete `src/main/java/nzi/fhir/validator/core/config/VerticleConfig.java`, `src/main/resources/application.properties`, `src/main/resources/application-production.properties` and `src/main/resources/application-docker.properties`. Reduce `src/main/resources/config.json` to the defaults in the configuration contract (remove `timeout`, `retries`, `backoff` and `package.servers`) [F6]
- [X] T013 Update `src/main/java/nzi/fhir/validator/core/config/PgConfig.java` to take `ApplicationConfig`. Remove the password-prefix log line (log only `host:port/database`). Remove the default password. Add `setSslMode` from `pg.sslMode` and the pool size from `pg.poolSize` [B18]
- [X] T014 Create `src/main/resources/db/migration/V3__config_revision_and_version_length.sql`:
  - `ALTER TABLE fhir_validator_schema.fhir_implementation_guides ALTER COLUMN ig_package_version TYPE VARCHAR(64)`
  - add columns `content_sha256 BYTEA NULL`, `source VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'` (allowed values `UPLOAD` | `REGISTRY` | `URL` | `UNKNOWN`, enforced by a CHECK constraint) and `updated_at TIMESTAMPTZ NULL`
  - `ALTER TABLE fhir_validator_schema.fhir_validator_logs` to add `resolved_ig_packages TEXT[] NOT NULL DEFAULT '{}'` and `revision BIGINT NOT NULL DEFAULT 1`
  - `UPDATE … SET resolved_ig_packages = included_ig_packages`
  - `INSERT INTO fhir_validator_schema.fhir_validator_logs (validator_id, fhir_version) VALUES ('standard_validator','STU3'),('standard_validator','R4'),('standard_validator','R4B'),('standard_validator','R5') ON CONFLICT (validator_id, fhir_version) DO NOTHING`
  - `UPDATE fhir_validator_schema.fhir_profiles SET modified_at = COALESCE(modified_at, created_at, now())`, then `ALTER TABLE fhir_validator_schema.fhir_profiles ALTER COLUMN modified_at SET DEFAULT now()`, so every profile row has a change timestamp the poll fallback can rely on

  Makes T009 pass
- [X] T015 [P] Create `src/main/java/nzi/fhir/validator/core/npm/PackageVersionComparator.java` per research R7. SemVer 2.0 precedence: pre-release ranks below release, build metadata is ignored, and non-SemVer labels sort below all SemVer labels. Add a static `max(Collection<String>)` (makes T007 pass)
- [X] T016 [P] Create `src/main/java/nzi/fhir/validator/core/concurrency/Executors.java`. It holds two named `WorkerExecutor`s: `fv-validation` (size `validation.workers`, maxExecuteTime `validation.timeoutMs`) and `fv-packages` (size `packages.workers`), plus `close()` returning `Future<Void>`
- [X] T017 [P] Add `SupportedFhirVersion.isEnabled()` (true for STU3, R4, R4B, R5 only) and `static Optional<SupportedFhirVersion> parseEnabled(String)` (case-insensitive) in `src/main/java/nzi/fhir/validator/core/enums/SupportedFhirVersion.java`. Leave `R6` in the enum (makes T010 pass)
- [X] T018 Create `src/main/java/nzi/fhir/validator/web/ErrorResponder.java`. It has the problem `type` catalogue from `contracts/http-api.md` (`invalid-request`, `invalid-resource` (carrying an `issues` extension array), `unsupported-fhir-version`, `unauthorized`, `forbidden`, `not-found`, `package-not-found`, `package-source-not-allowed`, `package-too-large`, `package-unsafe`, `unsupported-media-type`, `not-acceptable`, `payload-too-large`, `overloaded`, `not-ready`, `internal-error`). Add `problem(ctx, status, type, detail)`, `respond(RoutingContext, Future<?>)` (ends the response once and maps typed exceptions to problems), and `install(Router)`, which registers `errorHandler` for 400/401/403/404/405/406/413/415/500/503 plus a catch-all `failureHandler`. Add a typed `ProblemException(status, type, detail)` in the same package (makes T008 pass)
- [X] T019 [P] Create `src/main/java/nzi/fhir/validator/web/RequestIdHandler.java`. Accept `X-Request-Id` if it matches `[A-Za-z0-9-]{8,64}`, otherwise generate a UUID. Store it with `ContextualData.put("requestId", id)` and echo it as a response header. Change the pattern in `src/main/resources/log4j2.xml` to include `%X{requestId}` [FR-031]
- [X] T020 [P] Create `src/main/java/nzi/fhir/validator/web/CorsConfig.java`. Build a `CorsHandler` from `http.cors.allowedOrigins` (exact origins; return no handler when empty). Delete `setCros` and its `addRelativeOrigin(".*")` [baseline deviation 3]
- [X] T021 Update `src/main/java/nzi/fhir/validator/core/service/DatabaseService.java`. Run Flyway with `executors.packages().executeBlocking(Callable)` (no deprecated Handler form). Retry the connection with exponential backoff (1 s → 30 s) while keeping readiness false. Never "continue anyway" [D11, Principle I]
- [X] T022 Split the process. Create `src/main/java/nzi/fhir/validator/Bootstrap.java`: `main()` does `ConfigLoader` → `Vertx` (with metrics options placeholder) → `Executors` → `DatabaseService` → shared `ServiceRegistry` (new file `src/main/java/nzi/fhir/validator/ServiceRegistry.java`, holding the pool, executors, config and services) → deploy `HttpVerticle` with `instances = http.instances`. Rename `src/main/java/nzi/fhir/validator/web/MainVerticle.java` to `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`, so it only builds the router from `openapi.yaml`. Install, in order: `RequestIdHandler`, `CorsConfig`, `BodyHandler` (limit `http.maxBodyMb`, uploads in `java.io.tmpdir/ig-uploads`) and `ErrorResponder.install`. Listen on `http.port` [D6]. **Interim wiring until T048**: `Bootstrap` runs the existing `ValidationApi.create(vertx, pool)` **once** before deploying and passes the resulting `ValidationApi` (and the single `IgPackageService`/`ProfileApi`) through `ServiceRegistry` to every `HttpVerticle` instance. No verticle builds validators itself, so validation keeps working at the Phase 2 checkpoint and validators aren't built N times
- [X] T023 Add a readiness skeleton. Add `GET /health/readiness` (operationId `healthApiReadiness`) and the `Problem` schema to `src/main/resources/openapi.yaml`. Implement it in `src/main/java/nzi/fhir/validator/web/endpoint/HealthApi.java` and `src/main/java/nzi/fhir/validator/core/service/HealthService.java`: 200 when the DB answered `SELECT 1` within `health.dbStalenessMs` **and** every enabled version has a validator, otherwise 503 with `reasons[]`. Make the `FhirVersion` path schema case-insensitive with `pattern: '^([Ss][Tt][Uu]3|[Rr]4[Bb]?|[Rr]5)$'` instead of `enum` [FR-011]
- [X] T024 Rewrite `Dockerfile` per `contracts/container.md` §1 and research R16:
  - **Builder**: `FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-17 AS builder`, `dependency:go-offline` with a cache mount, `package -DskipTests`
  - **Runtime**: `FROM eclipse-temurin:17-jre-jammy`. `apt-get install --no-install-recommends tini curl`. Create user and group `10001`. Copy the jar to `/app/app.jar` (root-owned, read-only)
  - **Env**: `ENV FV_HTTP_PORT=8880 JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"`. `USER 10001:10001`. `EXPOSE 8880`
  - **Health**: `HEALTHCHECK --interval=15s --timeout=3s --start-period=120s --retries=4 CMD curl -fsS http://127.0.0.1:${FV_HTTP_PORT}/health/readiness || exit 1`
  - **Start**: `ENTRYPOINT ["/usr/bin/tini","--"]`, `CMD ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]`
  - **Labels**: OCI labels from build args `VERSION` and `REVISION`
  - No PostgreSQL, gosu, netcat, `VOLUME` or public DNS flags [D1, D2, D5, D6, D7, D8, D11]
- [X] T025 [P] Delete `docker/docker-entrypoint.sh`, `docker/init-scripts/wait-for-services.sh` and `docker/postgres-init/init-database.sh`. Keep `docker/postgres-init/01-init.sql` [D2, D5, D11]
- [X] T026 Create `compose.yaml`, default profile only, and delete `docker-compose.yml`:
  - **`postgres`**: `postgres:16.8-alpine`, env `POSTGRES_DB=${PG_DATABASE:-fhir_validator}`, `POSTGRES_USER=${PG_USER:-postgres}`, `POSTGRES_PASSWORD=${PG_PASSWORD:?set PG_PASSWORD in .env}`, volume `pgdata`, mount `./docker/postgres-init:/docker-entrypoint-initdb.d:ro`, healthcheck `pg_isready`. Not published (a git-ignored `compose.override.yaml` can publish it on loopback)
  - **`validator`**: `image: ghcr.io/nazrulworld/fhir-validation-service:${FV_TAG:-latest}` plus `build: .`, `env_file: .env`, `FV_PG_HOST=postgres`, `depends_on: postgres: condition: service_healthy`, `ports: ["127.0.0.1:${FV_HTTP_PORT:-8880}:8880"]`, `mem_limit: ${FV_MEMORY_LIMIT:-1g}`, `stop_grace_period: 30s`, healthcheck on `/health/readiness`
  - No `platform:` keys [D2, D3, D9, FR-033]
- [X] T027 [P] Create `scripts/smoke.sh` (POSIX sh, `set -eu`) per `contracts/container.md` §3:
  1. `docker compose up -d --wait`
  2. Poll `/health/readiness` until 200 (timeout 180 s)
  3. POST `src/test/resources/fhir/resources/dk/Patient.json` to `/R4/validate` and assert HTTP 200 with a JSON boolean `valid` (via `jq`)
  4. `docker compose kill -s TERM validator` and assert exit code 0 within 30 s
  5. Print `SMOKE OK`. On failure, dump `docker compose logs` and exit 1
- [X] T028 [P] Rewrite `Makefile`: targets `up` (`docker compose up -d --wait`), `up-cluster`, `up-obs`, `down`, `clean` (interactive confirm, then `down -v`), `logs`, `smoke` (`sh scripts/smoke.sh`) and `run-postgres` (password auth on `127.0.0.1:54329`, no trust). Remove `run-redis`, `run-ollama` and `stop-all` [D12]
- [X] T029 Create `.github/workflows/ci.yml`, triggered on `pull_request` and `push`. Jobs:
  - `test`: temurin 17, `./mvnw -B verify`
  - `container`: needs `test`. `hadolint/hadolint-action` on `Dockerfile`, `docker compose config -q` (with a generated `.env` from `.env.example`), `docker build`, then `sh scripts/smoke.sh`
  - `arm64-build`: buildx `linux/arm64` build without push

  [FR-035, D10]

**Checkpoint**: `./mvnw verify` and `make smoke` pass. The image has a single process, and compose runs a separate PostgreSQL.

---

## Phase 3: User Story 1 - Results reflect the configured profiles and IGs, every time, on every instance (Priority: P1) 🎯 MVP

**Goal**: Immutable per-version validator snapshots built from the persisted resolved package set. Profiles preloaded in memory. Revision + `LISTEN/NOTIFY` + poll propagation. Never serve an outdated revision.

**Independent Test**: Two instances on one DB. Register a profile through A, then validate through B: the first attempt reflects it. Include an IG, then upgrade it from X to Y: no X-only content remains (quickstart §4).

### Tests for User Story 1 (write first, must fail)

- [X] T030 [P] [US1] `ProfileFirstUseIT` in `src/test/java/nzi/fhir/validator/core/service/ProfileFirstUseIT.java`. Insert the dk-core-patient profile directly into `fhir_profiles` and start the services. The **first** validation of `Patient.json` with `?profile=http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient` must contain issues from that profile (fails today, B1). Register an updated profile and re-validate: the change is reflected immediately on the same instance [FR-001, B1]
- [X] T031 [P] [US1] `ValidatorSnapshotSwapIT` in `src/test/java/nzi/fhir/validator/core/validator/ValidatorSnapshotSwapIT.java`. Build a snapshot with a test IG `x#1.0.0` defining profile P1, then include `x#2.0.0` without P1 (craft two small `.tgz` packages in-test). After the swap, P1 is unknown. Run 200 validations concurrently during the swap: each result is fully consistent with either revision [FR-004, FR-006, B4, B5]
- [X] T032 [P] [US1] `DependencyResolverTest` in `src/test/java/nzi/fhir/validator/core/npm/DependencyResolverTest.java`. Covers fixture graphs: an explicit version is used exactly; a missing, `current` or `latest` version resolves to the highest stored version; a diamond with `a#1.0.0` and `a#1.2.0` picks `1.2.0` and emits a warning; cycles terminate; core packages are skipped; the result is identical across 100 shuffled input orders [FR-005, B6]
- [X] T033 [P] [US1] `ConfigPropagationIT` in `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java`. Two `Vertx` instances, each with its own `ServiceRegistry`, share one Testcontainers DB. A profile registered and an IG included through instance A take effect on instance B within 10 s, without a restart. Also with NOTIFY disabled on B: the poll fallback still converges within 10 s. Also stale handling: delete the package row for a resolved IG and bump the revision, then B answers 503 `not-ready` for that version only, `/health/readiness` is 503, and after the package is re-inserted B recovers automatically [FR-002, FR-003, FR-011, SC-001, SC-002, B2, B3]
- [X] T034 [P] [US1] `ProfileStoreTest` in `src/test/java/nzi/fhir/validator/core/validator/ProfileStoreTest.java`. Profiles with URLs `http://ex.org/a-b` and `http://ex.org/a.b` are stored and returned separately [FR-022, B17]

### Implementation for User Story 1

- [X] T035 [P] [US1] Create `src/main/java/nzi/fhir/validator/core/validator/ProfileStore.java`: one per FHIR version, a `ConcurrentHashMap<String, IBaseResource>` keyed by the exact canonical URL. Add `loadAll(Pool)` (parsing on `fv-packages`), `refresh(Pool, url)` and `get(url)`
- [X] T036 [US1] Rewrite `src/main/java/nzi/fhir/validator/core/service/CustomProfileValidationSupport.java` so `fetchStructureDefinition` reads `ProfileStore.get(url)` synchronously. No `Future` and no `.result()`. Implement `fetchAllStructureDefinitions` from the store [B1]
- [X] T037 [P] [US1] Create `src/main/java/nzi/fhir/validator/core/npm/DependencyResolver.java` per research R6: BFS, skip core packages, exact explicit versions, missing/`current`/`latest` → `PackageVersionComparator.max` over stored versions, conflicts keep the highest and add a warning, visited set by name. Returns `Resolution(List<String> resolved, List<String> warnings)`, sorted for determinism (makes T032 pass)
- [X] T038 [US1] Refactor `src/main/java/nzi/fhir/validator/core/service/CustomNpmPackageValidationSupport.java`. Remove the static `NPM_PACKAGE_VALIDATION_SUPPORT_CACHE`, `includedIgPackages` mutation and `loadIgPackageFromDatabase`. Add a constructor `(FhirContext, List<NpmPackage>)` that adds every JSON resource of each package's `package` folder at construction time and exposes `getPackages()` [B4, B5, F8]
- [X] T039 [US1] Create `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java`: immutable `fhirVersion`, `revision`, `List<String> resolvedPackages`, `FhirValidator`, `ValidationSupportChain`, `ProfileStore`, plus `validate(String body, SupportedContentType, List<String> profiles)`. Move the parse + `addProfilesToResource` + `validateWithResult` logic here from `FhirValidationService`
- [X] T040 [US1] Create `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java`. `Future<ValidatorSnapshot> build(version, revision, resolvedPackages)` runs on `executors.packages()`. It loads each package through `PostgresPackageCacheManager` (with `NpmPackage.fromPackage` inside the worker) and builds the chain in this order: `DefaultProfileValidationSupport`, `InMemoryTerminologyServerValidationSupport`, `CommonCodeSystemsTerminologyService`, `CustomProfileValidationSupport(ProfileStore)`, `CustomNpmPackageValidationSupport(packages)`. Then `FhirInstanceValidator`. A missing package fails the build with `MissingPackageException(name#version)`
- [X] T041 [US1] Create `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`: an `EnumMap<SupportedFhirVersion, AtomicReference<State>>` with states `ABSENT | BUILDING | ACTIVE(snapshot) | STALE(snapshot, wantedRevision, reason)` per `data-model.md` §2. Track two numbers per version: `appliedRevision` (the highest DB revision fully applied by this instance, advanced by profile refreshes **and** snapshot swaps) and `lastIgRevision` (the revision whose `resolved_ig_packages` the snapshot was built from). A snapshot rebuild happens only when `resolved_ig_packages` differs from the active snapshot's set. A profile-only revision advances `appliedRevision` without a rebuild. Add `current(version)`, which returns the snapshot only when ACTIVE and otherwise throws `ProblemException(503, "not-ready")`. Add `applyRevision(version, revision, resolved)`, which serialises builds per version and retries a failed build with backoff 1 s → 60 s. Add `isReady()`
- [X] T042 [P] [US1] Create `src/main/java/nzi/fhir/validator/core/service/ConfigRevisionRepository.java`. `Future<Long> bump(SqlConnection tx, version, kind, key)` runs `UPDATE fhir_validator_schema.fhir_validator_logs SET revision = revision + 1, updated_at = now() WHERE validator_id='standard_validator' AND fhir_version=$1 RETURNING revision`, then `SELECT pg_notify('fv_config_changed', $json)` with JSON `{fhirVersion, revision, kind:"IG"|"PROFILE", key}`. Also `Future<List<Row>> currentRevisions(Pool)`
- [X] T043 [US1] Rewrite `src/main/java/nzi/fhir/validator/core/service/ProfileService.java`. `registerProfile` runs in one `withTransaction`: validate the profile (it must be a `StructureDefinition`, and its `fhirVersion` element, if present, must match the service version), upsert `fhir_profiles`, setting `modified_at = now()` on **both** insert and update, then `ConfigRevisionRepository.bump(…, "PROFILE", url)`. On success, refresh the local `ProfileStore` and call `snapshot.chain().invalidateCaches()`. `getProfile` reads `ProfileStore`. Remove `EmptyResource`, `getCacheKey` and the static `CachingService`. Delete `src/main/java/nzi/fhir/validator/core/service/CachingService.java` (makes T030 and T034 pass) [B1, B2, B17]
- [X] T044 [US1] Add `Future<IncludeResult> include(version, name, versionOrLatest)` to `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`. It checks the package exists (otherwise `ProblemException(404, "package-not-found")`), unions it with the current `included_ig_packages` (replacing any other version of the same name), runs `DependencyResolver`, persists `included_ig_packages` and `resolved_ig_packages`, and calls `ConfigRevisionRepository.bump(…, "IG", name#version)` in one transaction. It returns `{fhirVersion, revision, included, resolved, warnings}` [FR-005, B3, B6]
- [X] T045 [US1] Create `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java`. A `PgSubscriber` on channel `fv_config_changed`: for kind PROFILE it calls `ProfileStore.refresh` + `invalidateCaches`; for kind IG it reads `resolved_ig_packages` and calls `ValidatorRegistry.applyRevision`. It also polls `currentRevisions` every `sync.pollIntervalMs`. When the DB revision is greater than `appliedRevision`, it (1) refreshes profiles with `modified_at > lastSeenProfileChange`, (2) compares the row's `resolved_ig_packages` with the active snapshot and calls `applyRevision` only if they differ, and (3) sets `appliedRevision` to the DB revision once both steps succeed. It reconnects the subscriber automatically [FR-002, R4] (makes T031 and T033 pass)
- [X] T046 [US1] Rewrite `src/main/java/nzi/fhir/validator/core/service/FhirValidationService.java` into a thin, stateless service. `Future<ValidationResult> validate(version, body, contentType, profiles)` = `registry.current(version)` → `snapshot.validate` on the validation executor. Remove the static `validationServicesStorage`, `put/get/remove/clear/size`, `saveSateToDatabase`, `syncPreviousStateFromDatabase`, `addNpmIgPackage` and `getIncludedIgPackagesListForNpmPackageValidation` [F8, baseline deviation 1]
- [X] T047 [US1] Update `handleIncludeImplementationGuide` in `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java` to call `IgPackageService.include` through `ErrorResponder.respond`, returning 200 `{status:"success", fhirVersion, revision, included, resolved, warnings}`. Delete `includeIgPackage`, the `synchronized(response)` blocks and the second response write [B20]. Delete `ValidationApi.create(...)` startup logic, which moves to Bootstrap
- [X] T048 [US1] Wire into `src/main/java/nzi/fhir/validator/Bootstrap.java`. For each enabled version (STU3, R4, R4B, R5), run `ProfileStore.loadAll`, then read the revision and `resolved_ig_packages`, then `ValidatorRegistry.applyRevision`, then start `ConfigSync`. The HTTP verticles deploy without waiting for the builds to finish: readiness stays false until every version is ACTIVE [FR-011, B15, P5]
- [X] T049 [P] [US1] Add the `cluster` profile to `compose.yaml`: service `validator-2` (same definition as `validator`, no host port) and service `lb` (`nginx:1.27-alpine`, `127.0.0.1:8881:80`, mounting `docker/nginx/lb.conf`). Create `docker/nginx/lb.conf` with an upstream round-robin over `validator:8880` and `validator-2:8880`, `proxy_next_upstream error timeout http_503` and `client_max_body_size 25m` [FR-033, SC-010]

**Checkpoint**: Quickstart §4 passes. US1 tests are green.

---

## Phase 4: User Story 2 - Validation throughput uses the capacity it is given, and admin work never stalls it (Priority: P1)

**Goal**: Parallel validation up to a bound, 503 beyond it, package work fully off the event loop, a bounded package cache, and accurate readiness.

**Independent Test**: Throughput at 1 vs 4 cores ≥ 3×. During a 20 MB IG load, validation p95 rises ≤ 20% and probes answer in < 1 s (quickstart §5).

### Tests for User Story 2 (write first, must fail)

- [X] T050 [P] [US2] `ConcurrencyIT` in `src/test/java/nzi/fhir/validator/core/service/ConcurrencyIT.java`. With `validation.workers=4`, submit 8 validations through a snapshot instrumented with a `CountDownLatch(4)` that holds each validation until 4 are running at once. The test must complete (it times out today, because of the serial `ordered=true` execution) [FR-007, P1]
- [X] T051 [P] [US2] `AdmissionControllerTest` in `src/test/java/nzi/fhir/validator/core/concurrency/AdmissionControllerTest.java`. With `maxPending=2`, a third concurrent request gets `503` with type `overloaded` and a `Retry-After` header. The counter returns to 0 after completion and after failure [FR-010]
- [X] T052 [P] [US2] `EventLoopResponsivenessIT` in `src/test/java/nzi/fhir/validator/web/EventLoopResponsivenessIT.java`. Set `VertxOptions.setMaxEventLoopExecuteTime(200ms)` and capture `BlockedThreadChecker` warnings with a Log4j test appender. While uploading and including `hl7.fhir.us.core-7.0.0.tgz`, poll `/health/liveness` and `/health/readiness` every 100 ms: every response arrives in < 1 s, and no blocked-thread warning is logged [FR-008, P2, SC-004]

### Implementation for User Story 2

- [X] T053 [P] [US2] Create `src/main/java/nzi/fhir/validator/core/concurrency/AdmissionController.java`: `AtomicInteger pending`, limit `validation.maxPending`. `Future<T> admit(Supplier<Future<T>>)` fails fast with `ProblemException(503, "overloaded")` and sets `Retry-After: 1` through `ErrorResponder` (makes T051 pass)
- [X] T054 [US2] In `src/main/java/nzi/fhir/validator/core/service/FhirValidationService.java`, run `snapshot.validate` with `executors.validation().executeBlocking(callable, false)` (unordered), wrapped in `AdmissionController.admit`. (Vert.x `maxExecuteTime` only logs a blocked-thread warning; it cannot interrupt a running validation, so no timeout mapping is possible) (makes T050 pass) [P1]
- [X] T055 [US2] In `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java`, move every `NpmPackage.fromPackage` call (`loadPackageFromCache`, `addPackageToCache`) into `executors.packages().executeBlocking(Callable)`. The event-loop callbacks only receive `Buffer`s. Do the same in `IgPackageService.registerIg(byte[])` in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java` [P2]
- [X] T056 [P] [US2] Create `src/main/java/nzi/fhir/validator/core/npm/PackageCache.java`: a Caffeine `Cache<String nameAtVersion, NpmPackage>` with `maximumWeight(packages.cacheMaxMb × 1 MiB)`, weigher = compressed byte length × 4, disabled when 0. Use it in `PostgresPackageCacheManager.loadPackage` before the DB read [FR-009, P3]
- [X] T057 [US2] Extend readiness in `src/main/java/nzi/fhir/validator/core/service/HealthService.java`. Ready only if `ValidatorRegistry.isReady()` (every enabled version has a snapshot and none is STALE; a version that is rebuilding keeps serving its previous snapshot and stays ready, so an IG include never pulls all instances out of the load balancer at once), the last successful DB ping is newer than `health.dbStalenessMs` (background ping every 2 s, not per request), and the instance isn't draining. `/health` reports the per-version state, revision and STALE reasons (makes T052 pass) [FR-011]
- [X] T058 [P] [US2] Create `perf/validate.js` (k6: POST R4 `Patient.json`, `VUS` and `BASE` from env, thresholds `p(95)<200`), `perf/validate-during-ig-load.js` (the same plus one VU uploading `hl7.fhir.us.core-7.0.0.tgz` at t=30 s; threshold checks for SC-004) and `perf/README.md` (how to pin CPUs with `docker compose run --cpus 1|4` and record results for the PR) [SC-003, SC-004, Principle VI]

**Checkpoint**: Quickstart §5 passes. US2 tests are green, and US1 tests are still green.

---

## Phase 5: User Story 3 - Only authorised administrators can change validation behaviour, and package ingestion cannot harm the service (Priority: P2)

**Goal**: API keys with ADMIN/VALIDATE roles (anonymous validation by default), SSRF-safe fetching, and archive guards.

**Independent Test**: Admin operations without a key are refused. A `downloadUrl` to a private address is refused with no connection. A bomb archive is rejected and memory stays flat (quickstart §3).

### Tests for User Story 3 (write first, must fail)

- [X] T059 [P] [US3] `PackageArchiveGuardTest` in `src/test/java/nzi/fhir/validator/core/npm/PackageArchiveGuardTest.java`. Generate archives in-test with commons-compress: (a) 1 MB gzip expanding past `maxExpandedMb`, (b) an entry `package/../../etc/passwd`, (c) an absolute entry `/etc/x`, (d) `maxEntries + 1` entries, (e) ratio > `maxRatio`, (f) a valid small package. (a)–(e) are rejected with the matching reason, and (f) passes. Peak heap growth during (a) is < 10% of baseline [FR-014, SC-007, F3]
- [X] T060 [P] [US3] `SafePackageFetcherTest` in `src/test/java/nzi/fhir/validator/core/npm/SafePackageFetcherTest.java`. Refused without any connection attempt: `http://packages.fhir.org/x` (not https), `https://evil.example/x` (not on the allowlist), and hosts resolving to `127.0.0.1`, `10.0.0.5`, `169.254.169.254`, `::1` and `fc00::1` (use an injectable resolver). A redirect from an allowed host to a disallowed host is refused. A response larger than `maxCompressedMb` is aborted mid-stream [FR-013, F1]
- [X] T061 [P] [US3] `ApiKeyAuthIT` in `src/test/java/nzi/fhir/validator/web/ApiKeyAuthIT.java`. With no `FV_ADMIN_API_KEY`, every admin operation (`/igs/upload`, `/igs/register`, `/{v}/include-ig`, `/{v}/register-profile`) returns 401/403. With a bootstrap key, admin operations succeed. Validation without a key succeeds by default, and with `FV_AUTH_ANONYMOUS_VALIDATE=false` it returns 401 without a key and 200 with a VALIDATE key. A VALIDATE key on an admin operation returns 403. A revoked key is refused within `auth.cacheTtlMs`. Health, readiness and metrics need no key [FR-012, SC-006, F2]

### Implementation for User Story 3

- [X] T062 [US3] Create `src/main/resources/db/migration/V4__api_keys.sql`: table `fhir_validator_schema.api_keys` with `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `name VARCHAR(64) UNIQUE NOT NULL`, `key_hash BYTEA UNIQUE NOT NULL`, `role VARCHAR(16) NOT NULL CHECK (role IN ('ADMIN','VALIDATE'))`, `created_at TIMESTAMPTZ DEFAULT now()`, `revoked_at TIMESTAMPTZ NULL` and `last_used_at TIMESTAMPTZ NULL`. Extend `MigrationUpgradeIT` (T009) to apply V4 on both paths
- [X] T063 [P] [US3] Create `src/main/java/nzi/fhir/validator/core/security/ApiKeyRepository.java`: `findActiveByHash(byte[])`, `upsertBootstrap(String plaintextKey)` (stores SHA-256 under `name='bootstrap'`, role `ADMIN`), and `touch(id)` (at most once per minute per key, asynchronous). Validate the key format as 32–128 characters of `[A-Za-z0-9_-]`
- [X] T064 [US3] Create `src/main/java/nzi/fhir/validator/core/security/ApiKeyAuthHandler.java`: parse `Authorization: Bearer`, SHA-256 the key, look it up through a Caffeine cache (TTL `auth.cacheTtlMs`) and put the role in the routing context. Add a factory method `requireRole(Role…)` returning a handler, and `optionalForValidate()`, which honours `auth.anonymousValidate`. Failures use `ErrorResponder` 401 `unauthorized` / 403 `forbidden`
- [X] T065 [US3] Edit `src/main/resources/openapi.yaml`: add `components.securitySchemes.apiKey` (http bearer) and per-operation `security` exactly as in the table in `contracts/http-api.md`. In `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`, set `RouterBuilderOptions.setRequireSecurityHandlers(true)` and mount `routerBuilder.securityHandler("apiKey", …)`. In `Bootstrap`, call `ApiKeyRepository.upsertBootstrap` when `auth.bootstrapAdminKey` is set (makes T061 pass)
- [X] T066 [P] [US3] Create `src/main/java/nzi/fhir/validator/core/npm/PackageArchiveGuard.java`. `check(InputStream)` streams through `GzipCompressorInputStream` + `TarArchiveInputStream`, counting compressed bytes, expanded bytes and entries, rejecting names that are absolute or contain `..`, and enforcing `packages.maxCompressedMb`, `maxExpandedMb`, `maxEntries` and `maxRatio`. It throws `ProblemException(413, "package-too-large")` or `(422, "package-unsafe")`. It runs on `fv-packages` (makes T059 pass)
- [X] T067 [P] [US3] Create `src/main/java/nzi/fhir/validator/core/npm/SafePackageFetcher.java`. https only. Host must be in `packages.sources`. Resolve the host through an injectable resolver and refuse loopback, link-local, site-local, unique-local (fc00::/7), multicast and any-local addresses. Connect to the pinned IP with the SNI/Host header set. Follow redirects manually (max 3, each re-checked). Use timeout `packages.fetchTimeoutMs`. Stream the body into a `Buffer` with a byte cap of `maxCompressedMb`, aborting beyond it (makes T060 pass)
- [X] T068 [US3] Wire security into ingestion:
  - `handleDownloadIg` in `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java` uses `SafePackageFetcher` and is refused with 400 `package-source-not-allowed` before any connection
  - `handleUploadAndRegisterIg` opens the upload with `vertx.fileSystem().open` and runs `PackageArchiveGuard` on `fv-packages` instead of `readFile`
  - `IgPackageService.registerIg(byte[])` always runs `PackageArchiveGuard` before `NpmPackage.fromPackage`
  - Registry fetches in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java` use `SafePackageFetcher` *(done with the registry rewrite in T083)*

  [F1, F3, P4]

**Checkpoint**: Quickstart §3 passes. US3 tests are green.

---

## Phase 6: User Story 4 - The API behaves predictably for ordinary HTTP clients (Priority: P2)

**Goal**: Content negotiation, `OperationOutcome` for FHIR media types, status mapping per research R12, one error format, every request answered, consistent versions, and removal of per-client validators.

**Independent Test**: Contract suite with the header-variant matrix, malformed bodies, unknown packages and simulated failures. Every request gets the documented status, body shape and type, within 5 s (quickstart §2).

### Tests for User Story 4 (write first, must fail)

- [X] T069 [P] [US4] `MediaTypesTest` in `src/test/java/nzi/fhir/validator/core/model/MediaTypesTest.java`. Covers: `*/*` → default JSON; `application/json; charset=utf-8` → JSON; `application/fhir+json;q=0.5, application/fhir+xml` → fhir+xml; `text/plain` as Accept → not acceptable; Content-Type `application/x+json` → JSON body; `text/csv` → unsupported; absent headers → JSON [FR-017, B12]
- [X] T070 [P] [US4] `ValidationApiContractTest` in `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java` (replaces `ValidationApiTest`, keeping its fixtures). For `/{v}/validate`, assert:
  - `Accept: */*` → 200 JSON `ValidationResponse`
  - `Accept: application/fhir+xml` → 200 XML `OperationOutcome`
  - `Accept: application/fhir+json` → 200 `OperationOutcome` JSON
  - `Accept: text/plain` → 406 problem
  - `Content-Type: text/csv` → 415
  - a parseable but invalid resource → 200 `valid:false` with issues
  - an unparseable body → 400 `application/problem+json` with type `invalid-resource` and an `issues[0].severity == "FATAL"`
  - `R6` → 400 `unsupported-fhir-version`
  - a UUID path → 400
  - lowercase `r4` → 200
  - `STU3` with an STU3 Patient → 200
  - a body over `http.maxBodyMb` → 413
  - a simulated internal failure (snapshot throws) → 500 problem with no exception text

  Each request completes within 5 s [FR-017–FR-021, B12, B13, B14, B15, B16, SC-005]
- [X] T071 [P] [US4] `IgPackageApiContractTest` in `src/test/java/nzi/fhir/validator/web/endpoint/IgPackageApiContractTest.java`. Assert:
  - `POST /igs/register {"name":"no.such.pkg","version":"9.9.9"}` → 404 `package-not-found` within 5 s (a local mock registry returns 404) *(moved to US5, T081: needs the configurable registry list)*
  - download-by-URL success returns the real `version`, not the name
  - `GET /igs/unknown/1.0.0/dependencies` → 404
  - a DB failure on dependencies → 500 *(moved to US5 with the configurable registry list, see T081)*
  - `/conformance` returns `{name, version, files}` without a `conformance` key

  [FR-020, FR-023, B10, B11, B21]
- [X] T072 [P] [US4] `ProfileApiContractTest` in `src/test/java/nzi/fhir/validator/web/endpoint/ProfileApiContractTest.java` (replaces `ProfileApiTest`). `R6` → 400 and nothing stored under R4. A profile whose `fhirVersion` is `5.0.0` posted to `/R4/register-profile` → 400. A valid STU3 profile → 200 with `revision`. Error bodies are problem+json [FR-021, B15]

### Implementation for User Story 4

- [X] T073 [P] [US4] Create `src/main/java/nzi/fhir/validator/core/model/MediaTypes.java`: parse `Accept` (q-values, wildcards, parameters) and `Content-Type`. `requestFormat(header)` returns JSON/XML/unsupported, matching `*/json`, `*+json`, `*/xml` and `*+xml`. `responseFormat(acceptHeader)` returns `JSON_LEGACY` (absent, `*/*` or `application/json`), `FHIR_JSON`, `FHIR_XML` (`application/xml` or `application/fhir+xml`) or NONE (makes T069 pass)
- [X] T074 [P] [US4] Create `src/main/java/nzi/fhir/validator/core/validator/OutcomeRenderer.java`. `renderLegacy(ValidationResult, version, appliedProfiles)` produces the existing `{valid, messages[{severity, location, message}]}` plus the additive `fhirVersion` and `appliedProfiles`, using `src/main/java/nzi/fhir/validator/core/model/ValidationResponse.java` (currently unused). `renderOperationOutcome(ValidationResult, FhirContext, xml|json)` uses HAPI's `result.toOperationOutcome()` and the version's parser
- [X] T075 [US4] Remove per-client validators. In `src/main/java/nzi/fhir/validator/core/model/ValidationRequestContext.java`, delete `UUID_V4_PATTERN`, the `api_clients` query and `createValidatorIdentity`, and derive the version with `SupportedFhirVersion.parseEnabled` (otherwise `ProblemException(400, "unsupported-fhir-version")`) and the formats with `MediaTypes`. Delete `src/main/java/nzi/fhir/validator/core/model/ValidatorIdentity.java` and replace every reference with `SupportedFhirVersion` [FR-016, B14]
- [X] T076 [US4] Rewrite `handleDoValidation` in `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java`. Status mapping per research R12: 200 for a completed validation (valid or invalid); 400 problem `invalid-resource` with a `FATAL` entry in `issues` for `DataFormatException`; 406/415 from `MediaTypes`; 503 from the registry or admission; 500 otherwise. Render with `OutcomeRenderer` according to `responseFormat`, and set `Content-Type` to the rendered type. Every path goes through `ErrorResponder.respond`. Remove the try/catch that returns 400 for server errors (makes T070 pass) [B12, B13, B16]
- [X] T077 [US4] Update `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java`. A null package from `registerIg` → `ProblemException(404, "package-not-found")`. Fix the download response to `.put("version", npmPackage.version())`. Replace every `err.toString()` body with `ErrorResponder`. Delete `sendErrorResponse`. In `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`, make `getDependencyGraph` fail with 404 when no row exists, and make `generateConformanceReport` return `{name, version, files}` (makes T071 pass) [B10, B11, B21]
- [X] T078 [US4] Update `src/main/java/nzi/fhir/validator/web/endpoint/ProfileApi.java`. Use `SupportedFhirVersion.parseEnabled` (otherwise 400), remove the `getOrDefault(..., R4)` fallback, register a `ProfileService` for each enabled version only, return `{status, profileUrl, revision}`, and use `ErrorResponder` for errors (makes T072 pass) [B15]
- [X] T079 [US4] Apply the remaining `contracts/http-api.md` changes to `src/main/resources/openapi.yaml`:
  - remove the `enum` from the `Accept` and `Content-Type` header parameters on `validationApiValidate`
  - add `application/*+json` and `application/*+xml` request bodies
  - responses 400/401/403/406/413/415/500/503 referencing `Problem`, plus `OperationOutcome` (`type: object`) for the FHIR media types
  - `ValidationResponse` gains the optional `fhirVersion` and `appliedProfiles`
  - include-ig 200 schema with `revision`, `included`, `resolved` and `warnings`; 404 for include-ig, register and dependencies
  - `conformance` marked `deprecated: true` with the `{name, version, files}` schema

**Checkpoint**: Quickstart §2 passes. US4 tests are green.

---

## Phase 7: User Story 5 - IG package lifecycle handles real-world packages (Priority: P3)

**Goal**: Long version labels, "latest" meaning the highest version, robust fallback across several registries, single storage, and accurate metadata.

**Independent Test**: Store `2.0.0`, then `1.5.0`: latest is `2.0.0`. `4.0.0-ballot-2023` is stored. Only the slower registry has the package, and registration still succeeds.

### Tests for User Story 5 (write first, must fail)

- [X] T080 [P] [US5] `PackageLatestResolutionIT` in `src/test/java/nzi/fhir/validator/core/npm/PackageLatestResolutionIT.java`. Insert `p#2.0.0`, then `p#1.5.0`, then `p#2.1.0-ballot`: `resolveLatest("p")` is `2.0.0`. A version label `4.0.0-ballot-2023` and a 64-character label round-trip. A 65-character label is rejected with 400 [FR-024, FR-025, B7, B8]
- [X] T081 [P] [US5] `RegistryFallbackTest` in `src/test/java/nzi/fhir/validator/core/npm/RegistryFallbackTest.java`. Two local Vert.x HTTP servers act as registries (on the allowlist via test config). Server A returns 404 within 10 ms; server B returns the package after 300 ms; the fetch succeeds and the DB row count is 1. Both 404 → not found. A hangs and B succeeds → success within `fetchTimeoutMs`. For latest-version lookup, A is unreachable and B lists versions → B's highest is used [FR-026, B9, B19, P6]

### Implementation for User Story 5

- [X] T082 [US5] In `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java`, replace `getLatestVersionFromCache`'s `ORDER BY created_at DESC LIMIT 1` with `SELECT ig_package_version … WHERE ig_package_id = $1` plus `PackageVersionComparator.max`. In `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java`, make `resolveLatestIgPackageVersion` delegate to it. Validate version labels as 1..64 characters with no whitespace (otherwise 400) (makes T080 pass) [B7, B8]
- [X] T083 [US5] Rewrite the registry fetch in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java`. Query the registries from `packages.sources` and complete with the **first non-null success**, failing "not found" only when every registry returned 404/an error or timed out (replacing `CompositeFuture.any`, which completes on the first 404). Cancel the remaining requests once one succeeds and upsert the package once. `getLatestVersion` ignores failed registries instead of `CompositeFuture.all` failing. `isLaterVersion` is replaced by `PackageVersionComparator`. Merge `src/main/java/nzi/fhir/validator/core/npm/AsyncBasePackageCacheManager.java` into the manager and delete it together with its unused `fetchFromServer`. Skip the registry entirely when `packages.registryEnabled=false` (makes T081 pass) [B9, B19, P6]
- [X] T084 [US5] On every upsert into `fhir_implementation_guides` (`PostgresPackageCacheManager.addPackageToCache`, `IgPackageService.registerIg`), set `content_sha256` (SHA-256 of the bytes), `source` (`UPLOAD` | `REGISTRY` | `URL`) and `updated_at = now()`. In `src/main/java/nzi/fhir/validator/core/service/ProfileService.java`, run single-row SELECTs with `pool.preparedQuery` instead of `withTransaction`. Make `Bootstrap`'s `ServiceRegistry` hold exactly one `IgPackageService` and `PostgresPackageCacheManager` with one `WebClient` [P7]

**Checkpoint**: US5 tests are green. Quickstart §2/§3 are still green.

---

## Phase 8: User Story 6 - Operators can run, observe and configure the fleet from the environment (Priority: P3)

**Goal**: Metrics, graceful drain, observability and admin compose profiles, safe publishing workflows, and the upgrade guide.

**Independent Test**: Two instances from the published image against one external DB, configured only through env. Metrics can be scraped. A DB outage flips readiness. A rolling restart under load fails < 0.1% of requests (quickstart §6).

### Tests for User Story 6 (write first, must fail)

- [X] T085 [P] [US6] `GracefulShutdownIT` in `src/test/java/nzi/fhir/validator/web/GracefulShutdownIT.java`. Start a slow validation (instrumented snapshot sleeping 2 s), then undeploy. During pre-drain, readiness is 503. The in-flight request completes with 200. New connections after the server closes are refused. Undeploy completes within `preDrainMs + graceMs` [FR-029, SC-008, F7]
- [X] T086 [P] [US6] `ObservabilityIT` in `src/test/java/nzi/fhir/validator/web/ObservabilityIT.java`. After 3 validations, `/metrics` contains `fv_validations_total`, `fv_validation_seconds_count`, `fv_validation_pending` and `fv_config_revision`. Every log line captured during a request (test appender) contains that request's `X-Request-Id` [FR-031]
- [X] T087 [P] [US6] `NoSecretsInLogsTest` in `src/test/java/nzi/fhir/validator/core/config/NoSecretsInLogsTest.java`. Start with `FV_PG_PASSWORD=Sup3rS3cretValue` and `FV_ADMIN_API_KEY=<40 chars>`: no captured log line contains either value or any 4-character substring of the password [FR-030, B18]

### Implementation for User Story 6

- [X] T088 [US6] In `src/main/java/nzi/fhir/validator/Bootstrap.java`, enable Micrometer: `new VertxOptions().setMetricsOptions(new MicrometerMetricsOptions().setPrometheusOptions(new VertxPrometheusOptions().setEnabled(true)).setEnabled(true))`. Create `src/main/java/nzi/fhir/validator/web/endpoint/MetricsApi.java`, which serves `GET /metrics` (operationId `metricsApiScrape`, added to `src/main/resources/openapi.yaml`, no security). Register the custom meters `fv_validations_total{version,outcome}`, `fv_validation_seconds{version}` (histogram), `fv_validation_pending`, `fv_config_revision{version}`, `fv_package_cache_size_bytes`, `fv_package_cache_hits_total`, `fv_profile_count{version}` and `fv_snapshot_build_failures_total{version}` in `FhirValidationService`, `AdmissionController`, `PackageCache`, `ValidatorRegistry` and `ProfileStore` (makes T086 pass)
- [X] T089 [US6] Graceful shutdown. In `Bootstrap`, add `Runtime.getRuntime().addShutdownHook` → `vertx.close()`. The hook thread waits on a `CountDownLatch` released in `vertx.close().onComplete(...)` (`latch.await(preDrainMs + graceMs + 5 s)`). Never call `.get()`/`.join()` on a future (constitution Principle I). In `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`, `stop(Promise)` sets the shared `draining` flag (readiness 503), waits `shutdown.preDrainMs`, calls `httpServer.close()`, and waits for the in-flight counter (incremented and decremented by a root handler) to reach 0 or `shutdown.graceMs`. `Bootstrap` then closes the pool, `ConfigSync` and `Executors`. Log `drain started` and `drain complete` (makes T085 pass) [FR-029]
- [X] T090 [P] [US6] Add profiles to `compose.yaml`:
  - `observability`: `prom/prometheus:v2.53.2` on `127.0.0.1:9090`, and `prometheuscommunity/postgres-exporter:v0.15.0` with `DATA_SOURCE_NAME` pointing at `postgres`
  - `admin`: `dpage/pgadmin4:9.4.0` on `127.0.0.1:5050`, with `PGADMIN_DEFAULT_PASSWORD=${PGADMIN_DEFAULT_PASSWORD:?set in .env}` and no default email or password

  No `platform:` keys. Update `docker/prometheus.yml` to scrape `validator:8880` and `validator-2:8880` at `/metrics`, and `postgres-exporter:9187` [D3, D9]
- [X] T091 [P] [US6] Rewrite `.github/workflows/docker-publish.yml`:
  - `test` job: `./mvnw -B verify`
  - `image` job (needs `test`): hadolint, then build amd64 with `load: true`, then `sh scripts/smoke.sh`, then `aquasecurity/trivy-action` with `severity: CRITICAL`, `exit-code: 1`
  - `push` job (needs `image`): buildx `linux/amd64,linux/arm64`, images `ghcr.io/${{ github.repository }}`. Tags: `type=sha` on main; `type=semver,pattern={{version}}`, `type=semver,pattern={{major}}.{{minor}}` and `type=raw,value=latest` **only** for `release` events. Build args `VERSION` and `REVISION`

  [FR-035, D10]
- [X] T092 [P] [US6] Update `.github/workflows/maven-publish.yml`: use `actions/checkout@v4` and `actions/setup-java@v4`, run `mvn -B verify`, and attach `target/fhir-validation-service-*.jar`, `compose.yaml` and `.env.example` to the release (not `docker-compose.yml`) [D10]
- [X] T093 [US6] Write `docs/upgrade-1.0.md`, the upgrade guide (spec FR-027 and Assumptions):
  1. Stop **all** 0.1.x instances (mixed fleets are unsupported)
  2. `docker run --rm -v <old_pgdata_volume>:/var/lib/postgresql/16/main …` and `pg_dump -Fc fhir_validator` from the old volume
  3. Start the 1.0.0 `postgres` service and `pg_restore`
  4. Count verification SQL for `fhir_implementation_guides`, `fhir_profiles` and `fhir_validator_logs`, before and after
  5. Start 1.0.0 (Flyway applies V3/V4)
  6. Set `FV_ADMIN_API_KEY`
  7. A rollback note: keep the old volume until verified, and that a V3/V4 schema is not supported by 0.1.x

  Rotate any password that was committed in the old `.env`. Link the guide from `CHANGELOG.md`
- [X] T094 [US6] Test the upgrade guide. Add `scripts/upgrade-rehearsal.sh`: **pull** the published old image `ghcr.io/nazrulworld/fhir-validation-service:0.1.2`. Don't rebuild it: the `v0.1.2` Dockerfile has the same `--chown=fhiruser:appuser` defect (D1). If the pull fails, build from `v0.1.2` with a one-line `sed` patch replacing `fhiruser:appuser` with `fhiruser:fhiruser`. Then register `hl7.fhir.dk.core-3.4.0.tgz` and a profile, follow `docs/upgrade-1.0.md` steps 1–5 automatically, and assert that the counts match and that `/R4/validate` with the dk profile behaves identically. Run it manually before release, and add it to `ci.yml` as a manual (`workflow_dispatch`) job [FR-027]

**Checkpoint**: Quickstart §6 passes. All stories are green.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T095 [P] Update `README.md`: remove the Redis and Java 11 claims (baseline deviation 5). Document `make up` / `up-cluster` / `up-obs`, the env table (link `specs/001-service-hardening/contracts/configuration.md` or copy it into `docs/configuration.md`), API keys, health, readiness and metrics, and the 1.0.0 upgrade link
- [X] T096 [P] Fill `CHANGELOG.md` `[1.0.0]`. **Breaking**: status codes (500 vs 400, 404s, 400 for unparseable), problem+json errors, admin operations need keys, removed properties files and system properties, default PG port 5432, UUID validator paths removed, the all-in-one image removed, `latest` tag only on releases. **Added**: readiness, metrics, `OperationOutcome`, STU3 validator, compose profiles. **Fixed**: each B/P/F/D finding ID with a one-liner
- [X] T097 Remove dead code and imports left behind: `src/main/java/nzi/fhir/validator/core/model/IgPackageName.java` (if unused after T083), unused imports in `src/main/java/nzi/fhir/validator/web/endpoint/*.java`, the commented-out handlers formerly in `MainVerticle`, and `IGPackageIdentity.asClassPath` if unused. Check with `./mvnw -B verify` and the IDE "unused declaration" inspection
- [X] T098 Amend `.specify/memory/constitution.md` (PATCH bump to 1.0.1). Mark Known Baseline Deviations 1–7 as resolved by `001-service-hardening`, and update **Last Amended**
- [X] T099 Create `specs/001-service-hardening/traceability.md`: a table of every finding B1–B21, P1–P7, F1–F10 and D1–D12 → closing task ID(s) → regression test class, or a recorded deferral with reason [SC-009]
- [X] T100 Run `specs/001-service-hardening/quickstart.md` sections 0–6 end-to-end on a clean checkout. Attach the k6 results (`perf/validate.js` at 1 and 4 CPUs, `perf/validate-during-ig-load.js`) and the `make smoke` output to the PR description [SC-003, SC-004, SC-010, Principle VI]
  - *Status 2026-10-25: §0 (`mvn verify`: 180 tests, `make smoke`), §1, §2, §3, §4 (cluster: IG propagated to both instances) and §6 (metrics, readiness on DB outage, no password in logs) verified in containers. **Open:** §5 and SC-003/SC-004 numbers (k6 is not installed on the build machine); attach the k6 results to the PR.*
  - *Status 2026-10-25 (§5, k6 0.54.0 in Docker, image from `main` @ 279a3ea, Apple-silicon host with 12 cores): SC-003: 1 CPU / 4 VUs 247 req/s, p95 8.7 ms; 4 CPUs / 16 VUs 2,556 req/s, p95 24.1 ms; 10.3× (≥ 3×), 0% failed. The 1-CPU run is throttling-bound (avg 16 ms vs median 3.9 ms), so the ratio overstates per-core scaling. SC-004 (8 VUs, 4 CPUs, US Core 7.0.0 uploaded and included at 30 s): validation p95 5.02 ms vs 4.54 ms plain (+10.6%, ≤ 20%); liveness/readiness max 8.5/4.9 ms (< 1 s); 0% failed. Caveat: the US Core fixture is a 1.6 MB archive, smaller than the 20 MB SC-004 names. VUs must not exceed the admission limit (4 × workers), or the run measures 503 `overloaded` instead of validations. Results posted on PR #2.*
  - *Status 2026-10-26 (T129, SC-004 at 20 MB; k6 0.54.0 in Docker, image built from the working tree with T127/T128, 4 CPUs, 8 VUs, `hl7.fhir.r4.examples#4.0.1` from packages.fhir.org: 20.5 MB archive, 180 MiB expanded, 5,311 entries, within the default archive limits): **at the default `FV_MEMORY_LIMIT=2g` (1.5 GB heap) SC-004 fails**: upload 200 in 1.8 s, the R4 rebuild succeeds in 9.3 s, then the JVM terminates with `OutOfMemoryError: Java heap space` (`-XX:+ExitOnOutOfMemoryError`), include gets no answer, 97% of validations fail, and because the include is committed every restart rebuilds with the package and runs out of heap again (crash loop, 2 restarts observed). **At `FV_MEMORY_LIMIT=4g` SC-004 passes**: upload 200 in 1.9 s, include 200 in 8.2 s (no warnings), validation p95 66.3 ms vs 58.0 ms baseline (+14.3%, ≤ 20%), liveness/readiness max 68/46 ms (< 1 s), 0% of 16,390 validations failed, no restarts. The baseline p95 here (58 ms) is higher than the 2026-10-25 run's because the host was under other load; both runs of a pair used the same host state.*

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none
- **Foundational (Phase 2)**: depends on Setup. **Blocks all stories**
- **US1 (Phase 3)**: depends on Foundational only
- **US2 (Phase 4)**: depends on Foundational. T054/T057 touch `FhirValidationService` and `HealthService` after US1's rewrite (T046), so **do US1 before US2** (both P1) or coordinate the edits to those two files
- **US3 (Phase 5)**: depends on Foundational. T068 edits `IgPackageApi` and `PostgresPackageCacheManager`, so sequence it after T055 (US2) if both are in flight
- **US4 (Phase 6)**: depends on Foundational. T076 edits `ValidationApi` after T047 (US1)
- **US5 (Phase 7)**: depends on Foundational. T083 edits `PostgresPackageCacheManager` after T055/T068
- **US6 (Phase 8)**: depends on Foundational. T088 adds meters to classes from US1/US2 (no-op if those stories aren't done yet)
- **Polish (Phase 9)**: after all chosen stories

### Story completion order (recommended)

```text
Setup → Foundational → US1 (P1, MVP) → US2 (P1) → US4 (P2) → US3 (P2) → US5 (P3) → US6 (P3) → Polish
```

US3 and US4 can swap places. Within each story: tests → models/stores → services → endpoints → wiring.

### Within-phase blocking chains

- T011 → T012 → T013 (configuration). T014 standalone. T018 before T022. T022 before T023
- T024 → T026 → T027 → T029 (image → compose → smoke → CI)
- US1: T035 → T036. T037 ‖ T038 → T040 → T041 → T045 → T048. T042 → T043, T044 → T047
- US3: T062 → T063 → T064 → T065. T066 ‖ T067 → T068
- US4: T073 ‖ T074 → T075 → T076. T077, T078, T079 after T075

---

## Parallel Examples

```text
# Foundational tests together:
T006 ConfigLoaderTest | T007 PackageVersionComparatorTest | T008 ErrorResponderTest | T009 MigrationUpgradeIT | T010 SupportedFhirVersionTest

# Foundational independent implementation:
T015 PackageVersionComparator | T016 Executors | T017 SupportedFhirVersion | T019 RequestIdHandler | T020 CorsConfig | T025 delete docker scripts | T027 smoke.sh | T028 Makefile

# US1 tests together:
T030 ProfileFirstUseIT | T031 ValidatorSnapshotSwapIT | T032 DependencyResolverTest | T033 ConfigPropagationIT | T034 ProfileStoreTest

# US1 independent implementation:
T035 ProfileStore | T037 DependencyResolver | T042 ConfigRevisionRepository | T049 compose cluster profile

# US3 implementation:
T063 ApiKeyRepository | T066 PackageArchiveGuard | T067 SafePackageFetcher

# US6 deployment pieces:
T090 compose profiles | T091 docker-publish.yml | T092 maven-publish.yml
```

---

## Implementation Strategy

### MVP (Setup + Foundational + US1)

1. Phases 1–2. This alone fixes the broken image build, the bundled database, the committed secrets, the unconfigurable port and the inconsistent configuration, and gives CI with a smoke test.
2. Phase 3 (US1): correct, consistent results on every instance.
3. **Stop and validate**: quickstart §1 and §4, then `make smoke`. This can ship as a 1.0.0 release candidate.

### Incremental delivery

1. + US2: throughput and responsiveness (the largest performance win, P1). Run k6.
2. + US4: honest API. Do this before announcing 1.0.0 to clients, because it holds most of the breaking changes.
3. + US3: auth and safe ingestion. Required before exposing the service beyond a trusted network.
4. + US5 and US6: package lifecycle, metrics, drain, publishing pipeline and upgrade guide.
5. Polish, then release 1.0.0.

### Notes

- Every bug-fix task has a regression test earlier in the same phase (Principle V). Confirm it fails before implementing.
- Changes to `openapi.yaml` land in the same commit as their handler (Principle IV).
- Never edit `V1`/`V2` migrations. All schema changes go in `V3`/`V4` (Principle VIII).
- Commit after each task or logical group, and keep `./mvnw verify` green at every checkpoint.

---

## Phase 10: Convergence

- [X] T101 CRITICAL: Move outcome rendering off the event loop: render the response (`OutcomeRenderer.render` for the legacy JSON and the `OperationOutcome` JSON/XML) inside the `fv-validation` worker task, so `src/main/java/nzi/fhir/validator/web/endpoint/ValidationApi.java` only writes the finished string; add a regression check to `src/test/java/nzi/fhir/validator/web/EventLoopResponsivenessIT.java` that validates a 1500-entry Bundle with `Accept: application/fhir+xml` without any blocked-event-loop warning per Constitution I (contradicts)
- [X] T102 CRITICAL: Add a configurable bundle size bound: `FV_VALIDATION_MAX_BUNDLE_ENTRIES` (`validation.maxBundleEntries`, default 10000, bounds 1–1000000) in `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java`, `ApplicationConfig.java`, `contracts/configuration.md` and `.env.example`; enforce it in `src/main/java/nzi/fhir/validator/core/validator/ValidatorSnapshot.java` after the lenient parse (Bundle with more entries → `ProblemException(413, "payload-too-large")`); cover it in `src/test/java/nzi/fhir/validator/web/endpoint/ValidationApiContractTest.java` per Constitution VI (partial)
- [X] T103 Add a configurable server-side request timeout `FV_HTTP_REQUEST_TIMEOUT_MS` (`http.requestTimeoutMs`, default 60000, bounds 1000–600000) mounted as a root handler in `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`; on expiry answer a 503 `internal-error`/`not-ready` problem through `ErrorResponder` exactly once (late completions must not write again); document it in `contracts/configuration.md`; add a test with a deliberately slow validation (instrumented snapshot as in `ConcurrencyIT`) asserting the timeout answer per FR-020 (partial)
- [X] T104 Keep drain logging alive until the shutdown hook finishes: set `shutdownHook="disable"` in `src/main/resources/log4j2.xml` and call `org.apache.logging.log4j.LogManager.shutdown()` as the last step of `Bootstrap.shutdown(...)` in `src/main/java/nzi/fhir/validator/Bootstrap.java`; extend `scripts/smoke.sh` to assert the validator log contains both `drain started` and `drain complete` per FR-029 / T089 (partial)
- [X] T105 Reconcile readiness during rebuilds with FR-011: either make `/health/readiness` report not-ready while a version is rebuilding (if load-balancer flapping is acceptable), or run `/speckit-clarify` to amend FR-011 to "a usable validator for every enabled FHIR version, none out of date (a version that is rebuilding keeps serving its previous snapshot)" so spec, `contracts/http-api.md` and `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java#notReadyReasons` agree per FR-011 / T057 (partial)
- [X] T106 Stream uploads into the archive guard instead of reading them whole: in `src/main/java/nzi/fhir/validator/web/endpoint/IgPackageApi.java#handleUploadAndRegisterIg` check the upload's size on disk against `FV_PACKAGE_MAX_COMPRESSED_MB` before reading (413 `package-too-large` without loading it), and run `PackageArchiveGuard` on a stream opened from the uploaded file on `fv-packages`; add a test uploading an archive larger than the compressed limit per FR-014 / T068 (partial)
- [X] T107 Cancel outstanding registry requests once the first registry returns the package in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java#fetchFromRegistries` (e.g. a cancellation hook in `SafePackageFetcher` that resets in-flight requests); extend `src/test/java/nzi/fhir/validator/core/npm/RegistryFallbackTest.java` to assert the hanging registry's request is reset per T083 (partial)
- [X] T108 Add a snapshot-size gauge (`fv_snapshot_packages{version}` and the parsed package bytes of the active snapshot) in `src/main/java/nzi/fhir/validator/core/metrics/ServiceMetrics.java`, and document the heap guideline (≥ 2× the largest snapshot during a rebuild) in `README.md` per plan: Risks & Mitigations (missing)
- [X] T109 Add an opt-in JSON log layout (`FV_LOG_FORMAT=json|text`, default `text`) to `src/main/resources/log4j2.xml` that includes the `requestId`; document it in `contracts/configuration.md` per plan: `log4j2.xml` touch-point (missing)
- [X] T110 Restrict `FV_HTTP_PORT=0` (ephemeral port) to `FV_PROFILE=dev` in `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java`, or document 0 in the bounds column of `contracts/configuration.md`; add the case to `ConfigLoaderTest` per contracts/configuration.md (contradicts)
- [X] T111 Document that `FV_PACKAGE_SOURCES` entries may be hosts (`https://<host>`) or base URLs (`scheme://host[:port]`, host part used for the allowlist) in `contracts/configuration.md` and `.env.example` per contracts/configuration.md (unrequested)
- [X] T112 Document the additional metrics `fv_validator_ready`, `fv_validation_max_pending` and `fv_package_cache_misses_total` in `contracts/http-api.md` (or remove them from `ServiceMetrics.java` if not wanted) per FR-031 (unrequested)
- [X] T113 Record the implemented decisions in `specs/001-service-hardening/research.md`: R14, a dependency-free `ConfigLoader` replaced vertx-config (removed from `pom.xml`); R8, authorisation is a router root handler (`ApiKeyAuthHandler.rootHandler`) mounted before contract validation instead of `RouterBuilder.securityHandler` per plan: research R14, R8 (contradicts)
- [X] T114 Tighten `src/test/java/nzi/fhir/validator/core/config/NoSecretsInLogsTest.java` to reject any 4-character fragment of the password and admin key, as T087 specifies (choose secrets without common 4-letter words) per T087 (partial)

---

## Phase 11: Convergence

- [X] T115 CRITICAL: Bound the API-key lookup cache in `src/main/java/nzi/fhir/validator/core/security/ApiKeyAuthHandler.java`: replace the unbounded `ConcurrentHashMap` (entries for every distinct, including invalid, bearer token are never evicted) with a Caffeine cache with `expireAfterWrite(auth.cacheTtlMs)` and a fixed `maximumSize` (e.g. 10000); add a test to `src/test/java/nzi/fhir/validator/web/ApiKeyAuthIT.java` that sends many distinct invalid keys and asserts the cache size stays at or below the bound per Constitution VI (contradicts)
- [X] T116 CRITICAL: Stop returning internal error text from `/health` and `/health/readiness`: in `src/main/java/nzi/fhir/validator/core/service/HealthService.java` report a fixed `database not reachable` reason (log `lastDbError` instead), and in `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java#describe` return curated reasons only (`Package name#version is not stored`, `validator build failed`) without exception class names or messages; add an assertion to `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java` (stale case) and `src/test/java/nzi/fhir/validator/web/endpoint/HealthApiTest.java` that readiness bodies contain no `Exception` or `java.` text per Constitution IV / FR-019 (contradicts)
- [X] T117 CRITICAL: Remove `err.getMessage()` from the include-ig `warnings` in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java#include`: use a fixed text ("Committed; this instance has not applied it yet, see /health") and log the cause with the request's correlation id; cover it in `src/test/java/nzi/fhir/validator/web/endpoint/IgPackageApiContractTest.java` per Constitution IV / FR-019 (contradicts)
- [X] T118 Stop accepting new requests during the drain in `src/main/java/nzi/fhir/validator/Bootstrap.java#stop`: after `shutdown.preDrainMs`, close the HTTP servers (or answer every new request with 503 `not-ready` + `Connection: close` from a root handler while draining), then wait for in-flight requests up to `shutdown.graceMs`; extend `src/test/java/nzi/fhir/validator/web/GracefulShutdownIT.java` to send a new request during the grace window (after pre-drain, while the slow request is still running) and assert it is refused per FR-029 / US6-AC3 / T089 (partial)
- [X] T119 Never answer from an outdated configuration when a profile refresh fails: in `src/main/java/nzi/fhir/validator/core/validator/ConfigSync.java#syncNow` and `src/main/java/nzi/fhir/validator/core/validator/ValidatorRegistry.java`, mark the version STALE (503 `not-ready`, readiness false) when the DB revision is newer than `appliedRevision` and `refreshChanged`/`refreshProfiles` fails, and clear it once the sync succeeds; add the case (inject a failing profile refresh) to `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java` per FR-002 (partial)
- [X] T120 Answer include-ig within the request timeout once the change is committed: in `src/main/java/nzi/fhir/validator/core/service/IgPackageService.java#include`, bound the wait for `applyLocally` (e.g. min(10 s, `http.requestTimeoutMs`/2)) and then return 200 with a warning that the local rebuild is still in progress, instead of letting `RequestTimeoutHandler` answer 503 "retry later" for a committed change; add a test with a slow snapshot build per FR-020 / contracts/http-api.md include-ig (partial)
- [X] T121 Align the upload size limits: make `/igs/upload` accept bodies up to `FV_PACKAGE_MAX_COMPRESSED_MB` (a route-specific `BodyHandler` limit in `src/main/java/nzi/fhir/validator/web/HttpVerticle.java`), or set the default `FV_HTTP_MAX_BODY_MB` to at least the default package limit, so a 20–50 MB IG gets `package-too-large` or succeeds instead of a generic 413; update `contracts/configuration.md` and `.env.example` per FR-014 / SC-004 (partial)
- [X] T122 Make `log.format` follow the single configuration mechanism: either reject `log.format` set through `FV_CONFIG_FILE` in `src/main/java/nzi/fhir/validator/core/config/ConfigLoader.java` (env/system property only, as Log4j reads it), or reconfigure Log4j from the loaded value at startup; document the choice in `contracts/configuration.md` and add the case to `ConfigLoaderTest` per FR-028 (contradicts)
- [X] T123 Add `FV_CONFIG_FILE` (commented out, with a placeholder path) to `.env.example` per T003 (partial)
- [X] T124 Treat a malformed `Content-Length` from a package source as a bad source response (400 `invalid-request`), not a 500, in `src/main/java/nzi/fhir/validator/core/npm/SafePackageFetcher.java#request`; add the case to `src/test/java/nzi/fhir/validator/core/npm/SafePackageFetcherTest.java` per FR-020 (partial)

---

## Phase 12: Convergence

- [X] T125 Make the incremental profile refresh independent of commit order: `fhir_profiles.modified_at = now()` is the transaction *start* time, so in `src/main/java/nzi/fhir/validator/core/validator/ProfileStore.java#refreshChanged` a row committed after a later-started transaction (`modified_at < lastSeenChange`) is never loaded, while `appliedRevision` still advances and no later poll recovers it. Fix by re-reading with an overlap window (e.g. `modified_at >= lastSeenChange - 60 s`; reloading is idempotent) or by tracking the configuration revision per profile row (new migration `V5`, never edit V1–V4), and add a regression test in `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java` that commits two overlapping registrations in reverse start order and asserts both profiles take effect on instance B per FR-001 / FR-002 / FR-003 / US1-AC2 (partial)
- [X] T126 Resolve registry "latest" by version ordering, not the registry's tag: in `src/main/java/nzi/fhir/validator/core/npm/PostgresPackageCacheManager.java#latestFromListing`, choose `PackageVersionComparator.max` over the listing's `versions` keys and fall back to `dist-tags.latest` only when no versions are listed; add a case to `src/test/java/nzi/fhir/validator/core/npm/RegistryFallbackTest.java` where `dist-tags.latest` is `1.5.0` but `versions` contains `2.0.0`, expecting `2.0.0` per FR-025 / US5-AC2 (partial)

---

## Phase 13: Convergence

- [X] T127 Answer register-profile within the request timeout once the profile is committed: `ProfileService.registerProfile` in `src/main/java/nzi/fhir/validator/core/service/ProfileService.java` waits without a bound on `applyLocally` (`ConfigSync.syncNow`). That call is queued behind any sync already running for the version (`ConfigSync.enqueue`), and `ValidatorRegistry.apply` waits for an in-flight IG rebuild, so a profile registered during a long rebuild gets `RequestTimeoutHandler`'s 503 "retry later" even though the profile is committed. Bound the local wait the way `IgPackageService#awaitLocal` does (min(10 s, `http.requestTimeoutMs`/2)), then return 200 `{status, profileUrl, revision}` with a `warnings` entry ("Committed; this instance is still applying it, see /health"), document the field in `contracts/http-api.md` and `openapi.yaml`, and add a test with a slow snapshot build in `src/test/java/nzi/fhir/validator/web/endpoint/ProfileApiContractTest.java` per FR-020 / US1-AC2 / T120 (partial)
- [X] T128 Stop validator builds from starving the other `fv-packages` work: snapshot builds of every version (`SnapshotBuilder#build`/`#reassemble`) share the pool (default `FV_PACKAGE_WORKERS=2`) with profile parsing at registration (`ProfileService`), profile refresh on other instances (`ProfileStore#refreshChanged`), upload parsing and `SafePackageFetcher`'s DNS resolution. Two concurrent rebuilds can therefore delay profile propagation beyond SC-002's 10 s and let a registry fetch wait outside `FV_PACKAGE_FETCH_TIMEOUT_MS`. Reserve capacity for that work (e.g. cap concurrent builds at `packages.workers - 1` with a minimum of 2 workers, or a small separate pool for profile parsing and DNS), make the fetch timeout cover the DNS step, document it in `contracts/configuration.md`, and add a test in `src/test/java/nzi/fhir/validator/core/validator/ConfigPropagationIT.java` where two versions rebuild slowly while a profile registered on instance A reaches instance B within 10 s per FR-008 / SC-002 (partial)
- [X] T129 Measure SC-004 at the size it names: the recorded run (T100) used the 1.6 MB US Core 7.0.0 archive, but SC-004 names a 20 MB IG and the US2 independent test needs at least 15 MB. Let `perf/validate-during-ig-load.js` take the archive path from an env var (`PACKAGE`), document in `perf/README.md` how to get an IG archive of at least 20 MB (a published package, or a synthetic one built with `src/test/java/nzi/fhir/validator/testsupport/TestPackages.java`) with `FV_PACKAGE_MAX_COMPRESSED_MB` and `FV_PACKAGE_MAX_EXPANDED_MB` raised to fit, then rerun and record p95 change (≤ 20%) and probe latency (< 1 s) in `specs/001-service-hardening/tasks.md` T100's status per SC-004 / US2-AC2 (partial)
- [X] T130 Turn the manual SC-008 check into a repeatable one: add `scripts/rolling-restart.sh` to start the `cluster` compose profile, run k6 (`perf/validate.js` with `BASE=http://127.0.0.1:8881`) for at least 2 minutes, restart `validator` and then `validator-2` (each waiting for `/health/readiness` 200 before the next one), and fail if the k6 failed-request rate is 0.1% or higher. Reference it from `perf/README.md` and from the SC-008 line in `specs/001-service-hardening/traceability.md`, and record the result per SC-008 / FR-029 (partial)

## Phase 14: Convergence

- [X] T131 Stop a too-large IG from crashing the instance in a loop: with the default `FV_MEMORY_LIMIT=2g`, including `hl7.fhir.r4.examples#4.0.1` (20.5 MB, the SC-004 size) exhausted the heap after the R4 rebuild, `-XX:+ExitOnOutOfMemoryError` ended the process, and because the include is already committed every restart rebuilt with it and crashed again, taking all FHIR versions down. Add a memory guard in `src/main/java/nzi/fhir/validator/core/validator/SnapshotBuilder.java` (estimate the build's heap from the packages' expanded size and compare it with `Runtime.maxMemory()` minus the live snapshots) so a build that cannot fit fails like any other rebuild failure: the version is unavailable with the reason named in `/health`, the other versions keep serving, and the process stays up. Reject an include or register that would not fit before it is committed (a problem answer, documented in `contracts/http-api.md` and `openapi.yaml`). Add a test in `src/test/java/nzi/fhir/validator/core/validator/` with an artificially low budget per spec Edge Cases (failed rebuild) / FR-008 / US2-AC2 (contradicts)
- [X] T132 State the memory needed for the SC-004 IG size: README.md, `compose.yaml`, `.env.example` and `contracts/configuration.md` say about 2 GB per instance, but a 20 MB IG (180 MiB expanded) needed `FV_MEMORY_LIMIT=4g` in T129. Either raise the default or document a sizing rule backed by the measurement (base heap for four warmed versions plus a multiple of the largest IG's expanded size), and name the setting that the T131 guard reports, per SC-004 / plan: risk "snapshot rebuild memory peak" (partial)

## Phase 15: Convergence

- [ ] T133 Make the upgrade rehearsal check that the registered profile is in effect after the upgrade, as T094 requires ("`/R4/validate` with the dk profile behaves identically"): `scripts/upgrade-rehearsal.sh` registers `http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient` on 0.1.2 but afterwards only checks that a validation without any profile returns 200. On 0.1.2, validate `src/test/resources/fhir/resources/dk/Patient.json` (and a variant that breaks a dk-core constraint) against the dk profile and record the error-level issues. After the upgrade, validate the same resources with `profile=` the dk URL, assert `appliedProfiles` contains it and that the same resources pass and fail with the same error-level issues (compare issue sets, not raw bodies: 0.1.2 answers in the old `{valid,messages}` shape). Run the rehearsal via `workflow_dispatch` and record the result in `docs/upgrade-1.0.md` §Rehearsal per T094 / FR-027 / spec Edge Cases (upgrade from the all-in-one image) (partial)
