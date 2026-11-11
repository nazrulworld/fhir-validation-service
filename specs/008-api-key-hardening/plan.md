# Implementation Plan: API Key Hardening, Key-Only Validation, Usage Accounting and Well-Known Organisations

**Branch**: `008-api-key-hardening` | **Date**: 2026-11-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/008-api-key-hardening/spec.md`; technical outline
`var/api_key_hardening_plan.md`

## Summary

Four related changes ship together as **4.1.0**. The release contains breaking changes; see Complexity Tracking.

**1. Key hardening.**
- A malformed bearer value gets 401 before any hashing, cache access or lookup.
- One `ClientAddress` resolver applies the trusted-proxy rule (right-most untrusted hop from `Forwarded`, else
  `X-Forwarded-For`, believed only from `FV_AUTH_TRUSTED_PROXIES`). It uses a small hand-written IPv4/IPv6 `Cidr`
  matcher. The admin allowlist, the audit and usage accounting all read the address it stores.
- `FV_AUTH_ADMIN_CIDRS` refuses administrative paths with 403 before the key lookup, for every key.
- Keys gain `expires_at`. The database filters on it, and a cached entry never outlives it.
- Keys gain `last_used_ip`. It is written when the key is used from a new address, or at most once a minute
  otherwise.

**2. Key-only validation.**
- `/{version}/validate` and the package reads need VALIDATE or ADMIN. `FV_AUTH_ANONYMOUS_VALIDATE` is removed: if
  it is still set, a WARN through the existing deprecation list.
- `openapi.yaml` declares `security` on every keyed operation. The router already runs with
  `setRequireSecurityHandlers(false)`, so no OpenAPI security handler is added; enforcement stays in the root handler.

**3. Usage accounting.**
- A per-request `UsageMeter` is started in `ValidationApi`, carried through `FhirValidationService.validate` and
  marked at each phase. Inside the worker task it reads thread CPU time and allocated bytes.
- It emits exactly one `UsageRecord` once both the response and the work are done, so timeouts still get real CPU
  figures.
- The records go to a `UsageWriter` chosen by `FV_USAGE_SINK` (`none` | `log` | `db` | `log,db`). The `db` writer
  is a bounded queue with batched, pipelined inserts into the new `validation_usage` table. It drops and counts on
  overflow and never blocks the request.
- New metrics: CPU and allocation summaries by version and outcome, and usage dropped/written counters.

**4. Well-known organisations.**
- `Constant` becomes the single source of truth, and `Organisations` delegates to it.
- `V11` re-keys the system org `…-000000000001` → `…-100000000001` (drop and re-add the three owner FKs and the
  check constraint, change the defaults), seeds the trial org, and renames the bootstrap key to
  `system administrator` with a fixed id.
- At startup the service ensures both orgs and upserts the seeded keys by fixed id, including
  `FV_VALIDATOR_API_KEY` as the trial org's VALIDATE key.
- Clashes and malformed keys raise a new fatal `StartupException`, and the process exits 1.

## Technical Context

**Language/Version**: Java 17 (service 4.0.0 → 4.1.0)

**Primary Dependencies**:
- Vert.x 4.5.x (core, web, web-openapi, pg-client), Log4j2, Micrometer (Prometheus).
- `java.lang.management.ThreadMXBean` and `com.sun.management.ThreadMXBean` (JDK; no new dependency).
- **No new Maven dependencies.**

**Storage**: PostgreSQL, through Flyway:
- `V10__api_key_expiry_and_audit.sql`: two columns on `api_keys`;
- `V11__well_known_organisations.sql`: system org re-key, trial org seed, bootstrap key rename;
- `V12__validation_usage.sql`: a new table and two indexes.

**Testing**:
- JUnit 5 with `vertx-junit5`, Testcontainers PostgreSQL (`BaseTestContainer`), `AppHarness` / `CoreHarness`, and
  `LogCapture` for the usage logger.
- New ITs: `BootstrapOrganisationsIT`, `UsageIT`. Extended: `ApiKeyAuthIT`, `AdminCliIT`, `ObservabilityIT`.

**Target Platform**: Linux containers (x86_64 and arm64), horizontally scaled behind a load balancer or reverse
proxy

**Project Type**: Web service with an administrative CLI (single Maven module)

**Performance Goals**:
- Usage metering adds < 1 ms to p95 (in practice a few µs), measured as 4.1.0 `db` against 4.1.0 `none`. The single-resource p95 stays < 200 ms on a warm
  validator.
- A malformed token is answered with no I/O.
- The admin network check is O(number of CIDRs) per admin request.

**Constraints**:
- The request path never waits on a usage sink (one `offer`, or one log call).
- No unbounded queue or map: usage queue ≤ 1 000 000, log-sink queue 8 192, touch map ≤ 10 000 (bounded
  `ConcurrentHashMap`), auth cache unchanged.
- No resource content in usage records or logs.
- The V11 upgrade needs every 4.0 instance stopped.

**Scale/Scope**:
- About 250 B per usage row. At 1 M validations a day that is about 250 MB a day before indexes, so the operator
  must handle retention (documented).
- Admin and proxy CIDR lists of up to a few dozen entries.

## Constitution Check

*GATE: checked before Phase 0, and checked again after Phase 1 (results below reflect the final design).*

| Principle | Status | How the design complies |
|---|---|---|
| **I. Non-blocking event loop** | PASS | CPU and allocation reads happen inside the existing `executors.validation().executeBlocking` task. The usage `write` is a non-blocking `offer`; `db` flushes use the async pg client from a Vert.x timer. Seeding and the upsert are `Future` chains in `Bootstrap`. No `.get()` or `.join()`. Logging the usage line from the end handler is the same kind of call as any other log line. |
| **II. Stateless, horizontally scalable** | PASS | New shared state (expiry, last-used address, usage rows, seeded orgs and keys) lives in PostgreSQL. The in-memory parts are rebuildable or loss-tolerant: the auth cache (TTL ≤ `FV_AUTH_CACHE_TTL_MS`, now also bounded by expiry), the touch throttle map, and each instance's usage queue. Cache consistency: expiry is enforced in SQL and by capping the cache entry, so every instance refuses an expired key at its expiry; revocation keeps the existing TTL bound. Seeding is idempotent and safe when several instances start at once (`ON CONFLICT`). |
| **III. FHIR conformance** | PASS | Validation results are unchanged; metering only observes them. |
| **IV. Contract-first** | PASS (documented minor-release break, Principle IV exception, constitution 1.3.0) | `openapi.yaml` changes first ([contracts/http-api.md](./contracts/http-api.md)): `security` on every keyed operation, plus the 401/403 texts. Making validation key-only is a breaking change. Constitution 1.3.0 allows it in a MINOR release because it is recorded under Complexity Tracking with the maintainer's approval, and the release ships a **Breaking** changelog section and `docs/upgrade-4.1.md`. Errors stay problem documents; 401 never reveals why a key was refused. |
| **V. Test-first, real dependencies** | PASS | Migrations, seeding, expiry, audit and the `db` writer are tested on Testcontainers PostgreSQL, including an upgrade from a V9 schema with data. CIDR, client address, meter arithmetic and the JSON shape are pure unit tests. Every endpoint whose auth changes has IT coverage in `ApiKeyAuthIT`. |
| **VI. Performance budgets** | PASS | Impact is stated above. Every new size is bounded in `ConfigLoader` (data-model §6). The hot validation path changes, so the PR carries `perf/validate.js` results for `none` and `db` in `perf/results-4.1.0.md`. This also fixes the latent unbounded `lastTouched` map in `ApiKeyRepository` (research R7). |
| **VII. Observability** | PASS | New metrics are listed in contracts/operations.md §3, with no high-cardinality tags. Usage records carry the request id. Startup logs name the setting, never secrets: `FV_VALIDATOR_API_KEY` is redacted like `FV_ADMIN_API_KEY`. All configuration comes from env vars. |
| **VIII. Simplicity, dependencies** | PASS | There are no new dependencies (the CIDR matcher is about 60 lines, research R4). Schema changes go only through new migrations V10 to V12; V8 is not edited, and V11 replaces its constraint instead. |
| **Security & compliance** | PASS | No PHI in usage. Client addresses are operational data (spec Assumptions). Spoofed forwarded headers are ignored unless the peer is trusted. Admin operations can be limited to internal networks. |
| **Workflow gate: migrations on top of the previous release** | PASS | `BootstrapOrganisationsIT#migratesA40Schema` migrates from V9 with data. A rolling upgrade is impossible and is documented (research R13). |

**Post-design re-check**: no violations. The minor-release break uses the documented Principle IV exception (constitution 1.3.0).

## Project Structure

### Documentation (this feature)

```text
specs/008-api-key-hardening/
├── plan.md              # this file
├── research.md          # Phase 0: R1–R19
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
├── contracts/
│   ├── http-api.md      # openapi.yaml changes, 401/403 rules, check order
│   └── operations.md    # env vars, CLI, metrics, usage log line, upgrade steps
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks; not created here)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── Bootstrap.java                            # CHANGE: ensureWellKnownOrganisations, installTrialValidatorKey,
│                                             #   installBootstrapKey by fixed id; StartupException → exit 1;
│                                             #   stop() closes the usage writer after the drain
├── ServiceRegistry.java                      # CHANGE: auth without anonymousValidate, UsageWriter wiring, usage()
├── web/HttpVerticle.java                     # CHANGE: mount ClientAddress root handler before the auth handler
├── core/Constant.java                        # CHANGE: trial validator key constants, parsed UUIDs/Instant, ISO expiry
├── core/StartupException.java                # NEW: fatal warm-up error
├── core/org/Organisations.java               # CHANGE: SYSTEM_ID / DEFAULT_SYSTEM_SHORT_NAME delegate to Constant
├── core/org/OrganisationRepository.java      # CHANGE: ensureWellKnown() (insert-if-missing, clash detection)
├── core/security/
│   ├── ApiKeyAuthHandler.java                # CHANGE: well-formed check, admin CIDRs, key-only validation,
│   │                                         #   cache capped by expiry, touch(id, ip)
│   ├── ApiKeyRepository.java                 # CHANGE: expires_at filter, KeyInfo/KeySummary fields, touch(id, ip)
│   │                                         #   with a bounded lock-free throttle, upsertWellKnown, create(..., expiresAt)
│   ├── Caller.java                           # CHANGE: apiKeyId, apiKeyName
│   ├── Cidr.java, CidrList.java              # NEW: IPv4/IPv6 CIDR parse + match
│   └── ClientAddress.java                    # NEW: trusted-proxy resolution + root handler
├── core/usage/                               # NEW package
│   ├── UsageRecord.java                      # record + toJson()
│   ├── UsageMeter.java                       # phase marks, CPU/alloc capture, two-latch emit
│   ├── UsageWriter.java                      # interface + factory from config
│   ├── NoopUsageWriter.java, LogUsageWriter.java, DbUsageWriter.java, CompositeUsageWriter.java
├── core/service/FhirValidationService.java   # CHANGE: validate(..., UsageMeter, finisher) overload; marks;
│                                             #   issue counts captured on the worker
├── core/metrics/ServiceMetrics.java          # CHANGE: cpu/allocated summaries, usage counters, queue gauge
├── core/config/ConfigLoader.java             # CHANGE: new FV_AUTH_* / FV_VALIDATOR_API_KEY / FV_USAGE_* entries;
│                                             #   DEPRECATED becomes name → reason, adds FV_AUTH_ANONYMOUS_VALIDATE
├── core/config/ApplicationConfig.java        # CHANGE: getters; remove anonymousValidate(); redact validatorKey
├── web/endpoint/ValidationApi.java           # CHANGE: start meter, end handler, pass meter to the service
└── cli/
    ├── KeyCommands.java                      # CHANGE: --expires, list fields, reserved names
    ├── UsageCommands.java                    # NEW (optional, FR-022): usage summary
    └── AdminCli.java                         # CHANGE: register the 'usage' group (optional)

src/main/resources/
├── openapi.yaml                              # CHANGE: security on keyed ops, 401/403 texts, scheme description
├── log4j2.xml                                # CHANGE: nzi.fhir.validator.usage logger (additivity=false)
└── db/migration/V10__…sql, V11__…sql, V12__…sql   # NEW

src/test/java/nzi/fhir/validator/
├── core/security/CidrTest.java, ClientAddressTest.java, ApiKeyAuthHandlerTest.java (malformed → no lookup)
├── core/usage/UsageMeterTest.java, LogUsageWriterTest.java, DbUsageWriterTest.java
├── core/ConstantMigrationConsistencyTest.java   # V11 literals == Constant
├── core/config/ConfigLoaderTest.java         # CHANGE: deprecation WARN, CIDR/sink validation
├── web/ApiKeyAuthIT.java                     # CHANGE: anonymous → 401, CIDRs, proxies, expiry, last_used_ip
├── web/UsageIT.java                          # NEW
├── BootstrapOrganisationsIT.java             # NEW (incl. migration from V9 with data)
├── testsupport/AppHarness.java               # CHANGE: VALIDATOR_KEY + validatorAuth(); existing ITs use it
└── cli/AdminCliIT.java                       # CHANGE: --expires, list fields, reserved names

docs/upgrade-4.1.md, CHANGELOG.md, README.md  # CHANGE: breaks, settings table, retention note
perf/results-4.1.0.md                         # NEW: before/after k6
```

**Structure Decision**: this is the existing single-module layout. Security additions stay in `core/security`,
next to the auth handler they serve. Usage gets its own package `core/usage`, so the sinks and the meter can be
reviewed and tested apart from validation. Only the meter's hooks touch `FhirValidationService` and
`ValidationApi`.

## Implementation Order (for /speckit-tasks)

1. **Constants and schema first:**
   - `Constant` / `Organisations` delegation;
   - `V10`, `V11`, `V12`;
   - `ConstantMigrationConsistencyTest`;
   - the `BootstrapOrganisationsIT` migration test from V9 with data.
2. **Well-known orgs and seeded keys (US2, US3, P1):**
   - `StartupException`;
   - `ensureWellKnownOrganisations`;
   - `upsertWellKnown`;
   - the bootstrap key by fixed id;
   - `FV_VALIDATOR_API_KEY`;
   - reserved names in the CLI;
   - `AppHarness.VALIDATOR_KEY`.
3. **Key-only validation (US1, P1):**
   - the `openapi.yaml` security declarations;
   - remove `anonymousValidate`;
   - the deprecation map;
   - update every IT that validated anonymously, using step 2's validator key.
4. **Malformed-token short-circuit (US7).** Small; can be done together with step 3.
5. **Client address and admin CIDRs (US4, P2):** `Cidr`, `ClientAddress`, the root handler, the allowlist check,
   and the config validation.
6. **Expiry and last-used address (US5, P2):** repository, cache cap, `touch(id, ip)` with a bounded map, and the
   CLI `--expires` and list fields.
7. **Usage accounting (US6, P2):**
   - `Caller` key fields;
   - `UsageRecord`, `UsageMeter` and the service and handler hooks;
   - the writers and the factory;
   - metrics;
   - `log4j2.xml`;
   - the `Bootstrap.stop()` close;
   - `UsageIT`.
8. **Performance:** k6 before and after (`none` vs `db`) → `perf/results-4.1.0.md`.
9. **Docs:** README (settings table, auth section, retention), `CHANGELOG.md` 4.1.0, `docs/upgrade-4.1.md`, and
   the release workflow attaching the upgrade doc (as done for 4.0).
10. **Optional (US8):** `usage summary` CLI.

## Complexity Tracking

| Item | Why needed | Simpler alternative rejected because |
|---|---|---|
| Breaking changes (key-only validation, removed `FV_AUTH_ANONYMOUS_VALIDATE`, stop-the-world V11) released as **4.1.0** instead of 5.0.0 (Principle IV exception, constitution 1.3.0) | The maintainer's explicit decision: 5.0 is reserved for a larger planned set of breaks, and these changes are needed now for the trial offering and cost attribution. The breaks are small for clients (add one header) and fully documented in `CHANGELOG.md` and `docs/upgrade-4.1.md`. | Releasing 5.0.0 now was rejected by the user. Keeping anonymous validation behind a flag would defeat US1 and leave usage unattributable. |
| Fatal `StartupException` path (exit 1 during warm-up) | US2 needs "fail loudly" when a well-known org or seeded key clashes; today a warm-up failure only leaves the instance unready. | Only logging an ERROR and staying unready: operators would see a probe timeout without the cause, and a misconfigured fleet could look healthy at the load balancer until readiness gating. |
| Two-latch usage emission | Exactly one record per request (SC-006), with real CPU figures even when the response ends (timeout, client abort) before the worker finishes. | Emitting at response end only would record -1 CPU for every timeout. Emitting at work end only would miss requests whose work never ran (rejected, aborted) and mis-time `totalMs`. |
