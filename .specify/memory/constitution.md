# FHIR Validation Service Constitution

## Core Principles

### I. Non-Blocking Event Loop (NON-NEGOTIABLE)

- Event-loop threads MUST NOT block. CPU-heavy or blocking work — HAPI FHIR validation, parsing
  large resources/bundles, NPM package loading, file I/O, JDBC/Flyway — MUST run on a worker pool
  (`executeBlocking` or a named `WorkerExecutor` with an explicit, configurable pool size), never
  inline in a route handler.
- All service-layer APIs MUST return `io.vertx.core.Future`. New code MUST NOT introduce
  callback-style APIs (including the deprecated `executeBlocking(Handler<Promise>)` form), MUST NOT
  call `.get()`/`.join()`/`toCompletionStage().toCompletableFuture().get()` on futures, and MUST NOT
  use `Thread.sleep`.
- A `BlockedThreadChecker` warning in logs or tests is a defect and MUST be fixed or filed before
  merge.

Rationale: Vert.x throughput depends entirely on a free event loop; a single blocked loop stalls
every request it owns.

### II. Stateless, Horizontally Scalable Instances

- Any number of instances MUST be able to run behind a load balancer without sticky sessions.
  PostgreSQL is the single source of truth for shared state: registered IG packages, profiles and
  the active validator configuration/state.
- In-memory structures (Caffeine caches in `CachingService`, the validator registry in
  `FhirValidationService`, NPM support caches) MUST be derived, rebuildable caches. Losing them
  MUST NOT lose data; an instance MUST be able to rebuild them from PostgreSQL.
- Every feature spec/plan that adds or mutates shared state MUST describe how caches stay
  consistent across instances (e.g. PostgreSQL `LISTEN/NOTIFY`, versioned state rows, TTL-based
  expiry) before implementation starts.
- Validator instances are expensive: they MUST be built at most once per `ValidatorIdentity` per
  instance, reused across requests, and held in thread-safe structures. Building a validator per
  request is prohibited.
- Coordination topology: PostgreSQL is currently the only shared infrastructure. Introducing
  Redis, clustered Vert.x (event bus across nodes) or a message broker is a governed decision under
  Principle VIII and MUST be recorded by amending this principle.

Rationale: horizontal scale is the primary goal of the service; per-instance hidden state breaks
correctness the moment a second replica is added.

### III. FHIR Conformance and Correctness

- Validation results MUST be faithful to HAPI FHIR / official HL7 validator semantics. Issues MUST
  NOT be suppressed or downgraded silently; any filtering MUST be explicit and opt-in via
  `ValidationRequestOptions`.
- Every supported FHIR version (STU3, R4, R4B, R5) MUST be handled explicitly through
  `SupportedFhirVersion`. Adding a version is a MINOR service change; dropping one is MAJOR.
- Validation responses SHOULD be expressible as a FHIR `OperationOutcome`; every reported issue
  MUST carry severity, location (FHIRPath expression) and message.
- IG packages are identified by `name#version` (`IGPackageIdentity`). Dependency resolution MUST be
  deterministic and reproducible: the same inputs MUST yield the same resolved package set.

Rationale: a validator that is fast but wrong is worse than none; consumers rely on parity with
the reference implementation.

### IV. Contract-First API

- `src/main/resources/openapi.yaml` is the single source of truth for the HTTP contract. Routes
  MUST be mounted through `RouterBuilder` operations defined in that spec; spec changes MUST land
  before (or with) the code that implements them.
- Breaking changes to paths, payloads or status codes MUST bump the service MAJOR version and
  ship a migration note in the changelog.
- Errors MUST use one consistent JSON error format with correct 4xx (client) / 5xx (server)
  status codes. Stack traces and internal exception messages MUST NOT be returned to clients.

Rationale: the spec is how clients integrate and how contract tests are generated; drift is
invisible until it breaks someone.

### V. Test-First with Real Dependencies

- New behaviour MUST start with a failing test (JUnit 5 + `vertx-junit5` `VertxExtension`).
- Persistence and IG-loading code MUST be tested against real PostgreSQL via Testcontainers
  (`BaseTestContainer`). Mockito mocks are permitted only for pure unit tests with no I/O.
- Every endpoint MUST have a contract/integration test. Every bug fix MUST include a regression
  test that fails without the fix.
- Validation-correctness tests MUST use fixture resources with known expected outcomes for each
  supported FHIR version.

Rationale: SQL, Flyway and HAPI behaviour cannot be faithfully mocked; real dependencies catch the
defects that matter.

### VI. Performance and Capacity Budgets

- Every feature spec MUST state its expected latency/throughput and memory impact.
- Baseline targets (provisional, see TODO(PERF_TARGETS)): p95 < 200 ms for single-resource
  validation against a warm validator; no unbounded memory growth; startup and validator warm-up
  MUST NOT block process liveness, and readiness MUST reflect warm-up state.
- Request body size, upload size (currently 20 MB), bundle size, cache sizes and worker-pool sizes
  MUST be configurable with explicit bounds; no unbounded queue or cache is permitted.
- Changes on the hot validation path MUST include a benchmark or load-test result in the PR before
  merge.

Rationale: capacity must be planned, not discovered in production.

### VII. Observability and Operability

- Logging MUST be structured via Log4j2/SLF4J, and every log line emitted while serving a request
  MUST carry a request/correlation id.
- `/health` and the liveness/readiness probes MUST reflect real dependency state (PostgreSQL
  connectivity, validator warm-up), not a static "UP".
- The service MUST expose Prometheus-compatible metrics: request rate, latency histograms,
  validation error counts, cache hit ratio and worker-pool saturation.
- All configuration MUST come through vertx-config / environment variables (12-factor). Secrets
  MUST NOT live in source code, committed `.env` files or container images.

Rationale: a horizontally scaled fleet is only operable if each instance is observable and
configured identically from the environment.

### VIII. Simplicity and Dependency Discipline

- Prefer the Vert.x ecosystem and HAPI FHIR over new frameworks. Spring and blocking JDBC on
  request paths are prohibited.
- Every new dependency or new infrastructure component (e.g. Redis, a message broker) MUST be
  justified in the feature's `plan.md`, including its concrete scalability benefit and the
  simpler alternative that was rejected.
- Schema changes MUST go only through versioned Flyway migrations (`V<n>__description.sql` in
  `src/main/resources/db/migration`). An applied migration MUST NEVER be edited.

Rationale: every moving part is something to operate, secure and scale; complexity must earn its
place.

## Technology Constraints

- Java 17; Maven build producing a shaded fat jar with Main-Verticle
  `nzi.fhir.validator.web.MainVerticle`.
- Vert.x 4.5.x (web, web-openapi, pg-client, config, junit5); HAPI FHIR 8.x; PostgreSQL + Flyway;
  Caffeine for in-process caching; Log4j2 for logging.
- Container images MUST be built for x86_64 and arm64 and published to GHCR; Maven artifacts are
  published to GitHub Packages.
- Dependency versions MUST be centralized as `pom.xml` properties; no inline version literals in
  dependency declarations.

## Development Workflow & Quality Gates

- Spec-driven flow: specify → clarify → plan → tasks → implement. The plan's "Constitution Check"
  MUST list every violation of this constitution together with its written justification.
- A PR MUST pass before merge:
  - `mvn verify` (unit + Testcontainers integration tests);
  - implemented routes match `openapi.yaml` (no undocumented or unimplemented operations);
  - no new blocked-thread warnings;
  - Flyway migrations apply cleanly both to an empty database and on top of the previous
    release's schema.
- The service artifact follows semantic versioning. User-visible changes MUST be recorded in the
  changelog.

## Security & Compliance

- Validated payloads MUST be treated as potentially containing PHI: full resource bodies MUST NOT
  be logged at INFO level or higher.
- Uploaded IG packages MUST be sanitized: reject path traversal entries, enforce size limits and
  guard against archive bombs (compressed/uncompressed ratio and entry count limits).
- Outbound package downloads MUST be restricted to configured package registries.
- CORS MUST be configurable per environment; wildcard origins MUST NOT be the production default.

## Governance

- This constitution supersedes all other project conventions. Where a guide or README conflicts
  with it, the constitution wins and the other document MUST be corrected.
- Amendments require a PR that updates this file, bumps its version and lists the dependent
  templates or docs that must change.
- Versioning policy: MAJOR — a principle is removed or redefined incompatibly; MINOR — a principle
  or section is added or materially expanded; PATCH — wording and clarification only.
- Compliance review: every `plan.md` Constitution Check and every code review MUST verify
  compliance with the principles above. Any added complexity MUST be justified in writing.

### Known Baseline Deviations

Existing code that predates this constitution and does not yet comply. These are recorded, not
rewritten; touching the affected code SHOULD bring it into compliance, and new code MUST NOT copy
these patterns.

1. `FhirValidationService.validationServicesStorage` and
   `CustomNpmPackageValidationSupport.NPM_PACKAGE_VALIDATION_SUPPORT_CACHE` are static `HashMap`s:
   not thread-safe and invisible to other instances (Principle II).
2. `FhirValidationService` and `DatabaseService` use the deprecated callback form of
   `executeBlocking` (Principle I).
3. CORS in `MainVerticle.setCros` allows all origins via `addRelativeOrigin(".*")` and is not
   configurable (Security & Compliance).
4. No Prometheus metrics endpoint and no request/correlation id in logs (Principle VII).
5. README states Redis is used, but only Caffeine (`CachingService`) exists; README states
   "Java 11 or higher", but `pom.xml` targets Java 17 (Governance: docs must match).
6. Upload limit (`MAX_UPLOAD_SIZE`, 20 MB) is a hard-coded constant, not configuration
   (Principle VI).
7. No changelog file exists yet (Development Workflow & Quality Gates).

**Version**: 1.0.0 | **Ratified**: 2026-10-25 | **Last Amended**: 2026-10-25
