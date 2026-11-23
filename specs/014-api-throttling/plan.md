# Implementation Plan: Validation Rate Limits per API Key and per Organisation

**Branch**: `014-api-throttling` | **Date**: 2026-11-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/014-api-throttling/spec.md`

## Summary

`POST /{version}/validate` gets per-minute caps per API key and per organisation, shared by every instance through
PostgreSQL. This ships as **1.4.0** with migration **V18**.

**1. Where it runs.**
- A new root handler `ThrottleHandler` runs right after authentication. It uses the `Caller` that auth already put in
  the routing context.
- It skips system-organisation callers (`Caller.system()`) and anything that is not the validate operation.
- It is not mounted at all when `FV_GLOBAL_DISABLED_ACL_SECURITY=true`.
- A refusal is a 429 `rate-limited` problem with `Retry-After`. Nothing is parsed or validated, and nothing is
  written to `validation_usage`.
- Every response to a limited key carries `RateLimit-Limit`, `RateLimit-Remaining` and `RateLimit-Reset`.

**2. Counting.**
- Each key and organisation has a rolling 60 s window of one-second buckets in a bounded Caffeine cache. A bucket
  holds this instance's count and the peers' count.
- The check and the increment for key and organisation happen together under two short locks. There is no I/O on the
  request path.

**3. Sharing (the clarified "≤ 10 % over" bound).** Once a second, each instance:
- upserts its own per-second counts into an UNLOGGED `throttle_counts` table;
- heartbeats into `throttle_instances`;
- reads its peers' counts for the last 5 s.

An admission rule that reserves room for the peers' not-yet-visible admissions keeps the fleet total ≤ cap +
max(1, ⌈cap/10⌉). On one instance the count is exact. If sharing fails, each instance falls back to local enforcement
(local mode) with a WARN and a gauge.

**4. Caps.**
- Nullable `rate_limit_per_minute` columns on `api_keys` and `organisations`. The two service-wide defaults are
  `service_settings` rows. CHECK constraints keep the system organisation out.
- V18 seeds 600/1200 for the defaults and 60 for the trial organisation, and 30 for the trial key if its row exists.
  Otherwise the insert-only path of `upsertWellKnown` sets 30.
- Every instance reloads one cap snapshot every 10 s.
- A new CLI group `limit set|remove|list` manages the caps.

Design details: [research.md](./research.md) R1–R14.

## Technical Context

**Language/Version**: Java 17 (service 1.3.0 → 1.4.0)

**Primary Dependencies**:
- Vert.x 4.5.x (core, web, web-openapi, pg-client), Caffeine (already a dependency), Micrometer/Prometheus,
  Log4j2.
- **No new Maven dependencies.**

**Storage**: PostgreSQL, one Flyway migration `V18__validation_rate_limits.sql`:
- two columns, four CHECK constraints and one `service_settings` CHECK;
- UNLOGGED `throttle_counts` and `throttle_instances`;
- seeds.

**Testing**:
- JUnit 5 with `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`), `AppHarness`/`CoreHarness`,
  `ContractAssertions` and `LogCapture`.
- New tests:
  - unit: `WindowTest`, `ThrottleDecisionTest`, `ThrottleSimulationTest`, `ThrottleCapsTest`;
  - IT: `ValidationThrottleIT`, `ThrottleMultiInstanceIT`, `ThrottleMigrationIT`, `LimitCommandsIT`.
- Extended: `AclDisabledIT`, `ConfigLoaderTest`, `ConstantMigrationConsistencyTest`, `NoSecretsInLogsTest`.

**Target Platform**: Linux containers (x86_64 and arm64), several instances behind a load balancer

**Project Type**: Web service with an administrative CLI (single Maven module)

**Performance Goals**:
- The throttle check adds ≤ 5 ms to p95 (SC-002). In practice it adds microseconds: two cache lookups, two
  synchronized ring updates and three headers.
- The single-resource p95 stays < 200 ms on a warm validator.
- 429s are answered without touching the worker pool.

**Constraints**:
- No database access on the request path (FR-005).
- No unbounded structure: windows ≤ `FV_THROTTLE_MAX_TRACKED`; refusal-log state lives inside each window.
- Sync interval ≤ 1 s (FR-005), and cap propagation ≤ about 11 s (FR-014: 60 s).
- A sync tick never overlaps the previous one, and a tick never blocks an event loop.

**Scale/Scope**:
- About 3 statements per second per instance, whatever the request rate.
- `throttle_counts` holds at most active subjects × instances × 120 rows of about 60 B.
- Caps are expected for at most a few thousand keys and organisations, so the snapshot is about 100 KB at most.

## Constitution Check

*GATE: checked before Phase 0, and checked again after Phase 1 (results below reflect the final design).*

| Principle | Status | How the design complies |
|---|---|---|
| **I. Non-blocking event loop** | PASS | The request path does in-memory work only, with short `synchronized` sections and no I/O. Sync, cap reload and cleanup are `Future` chains on the async pg client from a Vert.x periodic timer, with no `executeBlocking`, `.get()`, `.join()` or `Thread.sleep`. The CLI uses the existing async `CliContext`. |
| **II. Stateless, horizontally scalable** | PASS | PostgreSQL is the only shared state: caps in `api_keys`, `organisations` and `service_settings`; counts in UNLOGGED tables. In-memory windows and the cap snapshot are derived caches that are rebuilt by the start-up read (60 s of counts) and the snapshot load. Losing them only resets windows briefly. **Cache consistency across instances**, as Principle II requires before implementation: caps are polled every `FV_THROTTLE_CAPS_REFRESH_MS` (≤ 30 s, default 10 s); counts are exchanged every ≤ 1 s through `throttle_counts`, with idempotent absolute-count upserts and set-not-add reads; peers are tracked by heartbeat on database time. The bound and the degraded behaviour are given in research R4 and R5. No Redis and no clustered event bus. |
| **III. FHIR conformance** | PASS | Validation results are untouched. A request is either refused before validation or validated exactly as before. |
| **IV. Contract-first** | PASS | `openapi.yaml` changes first ([contracts/http-api.md](./contracts/http-api.md)): a new `TooManyRequests` response with header components, `429` and `RateLimit-*` headers on the validate operation, and optional `scope`/`limit`/`retryAfterSeconds` problem members. The change is additive, so the release is MINOR. The 429 uses the standard problem format. `ContractAssertions` checks real responses against the spec. |
| **V. Test-first, real dependencies** | PASS | Migration, seeding, the CLI and multi-instance sharing are tested on Testcontainers PostgreSQL, including V18 on top of a 1.3.0 schema with data. The window arithmetic, decision rule and fleet bound are pure unit tests with an injected clock. Every user story has an IT, and the validate endpoint's contract test covers 429. |
| **VI. Performance and capacity budgets** | PASS | Impact is stated above. Every new size is configurable with bounds (data-model §4). The change is on the hot validation path, so the PR carries `perf/results-1.4.0.md` (k6, 1.3.0 against 1.4.0, plus an at-cap run). |
| **VII. Observability** | PASS | Nine `fv_throttle_*` meters, none with per-key, per-organisation or per-instance tags. Refusal logs are bounded to one line per subject per minute and carry the request id. Local mode logs WARN and INFO and sets a gauge. Readiness reports not-ready ("rate limits not loaded") until the first cap snapshot is loaded (T019). All configuration comes from env vars. No secrets or resource content are logged. |
| **VIII. Simplicity and dependency discipline** | PASS | No new dependency: Caffeine and Micrometer are already used. No new infrastructure: rejected alternatives were Redis, Bucket4j and per-request database counters (research R1, R4). Schema changes go only through the new `V18`; V15–V17 are untouched. |
| **Security & compliance** | PASS | A 429 never reveals other keys, organisations or usage. Caps can't be set for the system organisation (CLI check plus CHECK constraints). Usage rows still hold no resource content, and throttle rows hold only ids and counts. |
| **Workflow gate: migrations on top of the previous release** | PASS | `ThrottleMigrationIT` applies V18 to an empty database and on top of a 1.3.0 (V17) schema with data. |

**Post-design re-check**: no violations. Two behaviour changes are recorded for the changelog:
- 429 is a new possible status of validate;
- refused requests are no longer usage-recorded, a narrowing of spec 008's "router refusals still get a usage
  record" (research R9).

## Project Structure

### Documentation (this feature)

```text
specs/014-api-throttling/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R14
├── data-model.md        # Phase 1: columns, settings, UNLOGGED tables, in-memory model, env vars
├── quickstart.md        # Phase 1: runnable validation scenarios
├── contracts/
│   ├── http-api.md      # openapi.yaml changes: 429 rate-limited, RateLimit-* headers, check order
│   └── operations.md    # env vars, `limit` CLI group, metrics, log lines
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── Bootstrap.java                            # CHANGE: warm-up starts ThrottleService after AclModeStartup (enabled
│                                             #   mode only); stop() stops it (deletes its heartbeat row)
├── ServiceRegistry.java                      # CHANGE: builds ThrottleService / ThrottleHandler (enabled mode only); Optional throttle()
├── core/Constant.java                        # CHANGE: initial caps (600, 1200, 60, 30) and settings names
├── core/config/ConfigLoader.java             # CHANGE: FV_THROTTLE_SYNC_INTERVAL_MS, _CAPS_REFRESH_MS, _MAX_TRACKED
├── core/config/ApplicationConfig.java        # CHANGE: getters
├── core/security/ApiKeyRepository.java       # CHANGE: upsertWellKnown inserts rate_limit_per_minute (insert-only)
├── core/settings/ServiceSettingsRepository.java  # CHANGE: THROTTLE_DEFAULT_KEY / _ORG names; get(name), delete(name)
├── core/throttle/                            # NEW package
│   ├── ThrottleCaps.java                     # immutable snapshot + effective-cap resolution
│   ├── ThrottleCapsRepository.java           # one UNION query → snapshot
│   ├── RateLimitRepository.java              # cap reads/writes for the CLI: lock + set a key's or organisation's
│   │                                         #   cap, list organisations and keys (one place for all cap SQL;
│   │                                         #   replaces the setters first planned in ApiKeyRepository and
│   │                                         #   OrganisationRepository)
│   ├── Window.java                           # 61 one-second buckets (local/remote), unconfirmed count, log state;
│   │                                         #   compact layout within SC-008 (T055)
│   ├── ThrottleDecision.java                 # admit/refuse result: scope, limit, remaining, reset, retryAfter
│   ├── Throttle.java                         # check-and-count for (key, org) under ordered locks; admission rule
│   ├── ThrottleSync.java                     # periodic flush/heartbeat/read, cleanup, local mode; drains only
│   │                                         #   changed windows (T054); warm after two ticks (T056)
│   ├── CountStore.java                       # interface of the shared counts, so ThrottleSimulationTest can run
│   │                                         #   a fleet on an in-memory table
│   ├── ThrottleCountsRepository.java         # CountStore on PostgreSQL: unnest upsert, read last N s, heartbeat,
│   │                                         #   cleanup
│   └── ThrottleService.java                  # wiring, start (caps + 60 s read) / stop, metrics binding
├── core/metrics/ServiceMetrics.java          # CHANGE: bindThrottle(...) — fv_throttle_* meters
├── web/ThrottleHandler.java                  # NEW: root handler; headers; 429 problem; bounded refusal log
├── web/HttpVerticle.java                     # CHANGE: mount ThrottleHandler after auth, before usage fallback
├── web/CorsConfig.java                       # CHANGE: expose RateLimit-* headers
├── web/ProblemException.java                 # CHANGE: rateLimited(scope, limit, retryAfter) factory
└── cli/
    ├── LimitCommands.java                    # NEW: limit set | remove | list
    └── AdminCli.java                         # CHANGE: register the 'limit' group; usage text

src/main/resources/
├── openapi.yaml                              # CHANGE: 429 + RateLimit-* headers on validate; Problem members
└── db/migration/V18__validation_rate_limits.sql   # NEW

src/test/java/nzi/fhir/validator/
├── core/throttle/WindowTest.java, ThrottleDecisionTest.java, ThrottleCapsTest.java     # NEW (unit)
├── core/throttle/ThrottleSimulationTest.java # NEW: deterministic multi-instance bound (SC-001)
├── core/throttle/ThrottleMultiInstanceIT.java    # NEW: two services, one PostgreSQL; local mode via container pause
├── core/throttle/ThrottleMigrationIT.java    # NEW: V18 on empty and on a V17 schema with data; seeding rules
├── web/ValidationThrottleIT.java             # NEW: US1, US2, US5, headers, contract, bounded log
├── web/AclDisabledIT.java                    # CHANGE: not throttled, no headers (US4)
├── cli/LimitCommandsIT.java                  # NEW: US3 and US5 AC3/AC4; exit codes; audit line
├── core/ConstantMigrationConsistencyTest.java    # CHANGE: V18 literals == Constant
└── core/config/ConfigLoaderTest.java         # CHANGE: bounds of the three new variables

docs/operations.md, README.md, CHANGELOG.md   # CHANGE: rate limits section, settings table, 1.4.0 entry
perf/compare-run.sh                           # CHANGE: optional env to set the default caps before the run
perf/results-1.4.0.md                         # NEW
specs/008-api-key-hardening/spec.md           # CHANGE: amendment note (429s are not usage-recorded, research R9)
```

**Structure Decision**: the existing single-module layout. Throttling gets its own package, `core/throttle`, so the
window arithmetic, the sharing protocol and the cap snapshot can be reviewed and unit-tested apart from HTTP. The web
layer only adds one root handler and the response shape. Persistence follows the existing repository pattern of
static or instance methods that take a `SqlClient`.

## Implementation Order (for /speckit-tasks)

1. **Contract and schema first** (Principle IV):
   - `openapi.yaml`;
   - `V18`;
   - `Constant` values;
   - `ConstantMigrationConsistencyTest` and `ThrottleMigrationIT`.
2. **Pure core, test-first**: `Window`, `ThrottleCaps` and `Throttle`, with `WindowTest`, `ThrottleDecisionTest` and
   `ThrottleSimulationTest`. At this step the 10 % bound is proven without a database.
3. **Single-instance HTTP slice (MVP, US1 + US2)**:
   - `ThrottleCapsRepository`;
   - `ThrottleService` with sync disabled (p = 0);
   - `ThrottleHandler`, the `HttpVerticle` mount, `ProblemException.rateLimited` and CORS;
   - `ValidationThrottleIT`.
4. **Exemptions (US4, US5)**: the disabled-mode non-mount and the system-caller skip. `AclDisabledIT`; the
   system-organisation cases of `ValidationThrottleIT`.
5. **Operator surface (US3)**: the repository setters, the insert-only seed in `upsertWellKnown`, `LimitCommands`
   and `AdminCli`. `LimitCommandsIT`.
6. **Sharing**:
   - `ThrottleCountsRepository`;
   - `ThrottleSync` (flush, heartbeat, read, start-up read, cleanup, local mode);
   - `ThrottleMultiInstanceIT`.
7. **Observability**: `ServiceMetrics.bindThrottle`, the bounded refusal log and the local-mode logs. Log and metric
   assertions in the ITs.
8. **Docs, release and performance**:
   - `docs/operations.md`, README settings, `CHANGELOG` 1.4.0 and the spec 008 amendment note;
   - `perf/results-1.4.0.md`.

## Complexity Tracking

No constitution violations to justify.

Two deliberate trade-offs are recorded here for review. Neither is a constitution violation:

| Trade-off | Why | Simpler alternative rejected because |
|---|---|---|
| Approximate fleet-wide counting with a reservation rule (research R4) instead of an exact shared counter | Clarification Q1: keep the database off the request path and stay within the 5 ms p95 budget | An exact per-request `UPDATE … RETURNING` adds a database round trip to every validation, and its load grows with the request rate |
| 429s are not written to `validation_usage` (research R9) | Under abuse, recording every refusal would make database writes grow with the attack the throttle exists to absorb | Keeping spec 008's "every authorised refusal gets a usage row" would make the throttle a write amplifier. The refusals stay visible through `fv_throttle_refused_total` and the bounded log |
