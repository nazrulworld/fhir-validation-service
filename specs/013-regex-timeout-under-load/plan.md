# Implementation Plan: No Spurious Regex Timeout Errors Under Load

**Branch**: `013-regex-timeout-under-load` | **Date**: 2026-11-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-regex-timeout-under-load/spec.md`

## Summary

Stop load from turning a valid resource into an invalid one. HL7 core gives every regex check 500 ms of wall time on a
newly created thread; under load the check can miss the deadline before it runs, and the result gains a false
error-level issue.

Technical approach (see [research.md](./research.md)): keep the engine and its limit unchanged. Inside
`ValidatorSnapshot.validate`, recognise a regex-timeout issue by its message id (language-independent), and re-run the
engine call at most twice: a **plain** re-check at once, then a **quiet** re-check that holds an instance-wide
exclusive permit so no other validation runs meanwhile. Answer with the first attempt's result that has no regex
timeout, or the last attempt's result unchanged (Q1: A). A deadline (the request's HTTP timeout counted from
arrival, and the validation budget `FV_VALIDATION_TIMEOUT_MS`), a 1-second cap on waiting for the exclusive permit, a
"more than 3 timeouts means input" rule and a 6-per-minute quiet re-check limit bound the cost and stop a hostile
input from pausing the instance. Two counters and a WARN log make re-checks visible. No API shape change, no migration, no new
dependency or configuration. Ships as **1.3.0** (new metrics; behaviour change visible to clients).

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Vert.x 4.5.x, HAPI FHIR 8.2.0 with HL7 core 6.9.12 (`RegexTimeout`, message ids), Micrometer
(existing `ServiceMetrics`), Log4j2 — no new dependency

**Storage**: none (no table, column or migration; next migration number stays V18)

**Testing**: JUnit 5 with vertx-junit5; unit tests for detection and the decision rules (attempts injected as a
function); an IT with a real runaway regex against a real context; the existing release-gate differential IT with its
regex retries removed; an opt-in load test (`FV_LOAD_ROUNDS`) for SC-001; k6 via `perf/compare-run.sh` for SC-004

**Target Platform**: Linux containers (`linux/amd64`, `linux/arm64`), JVM 17

**Project Type**: web service (single Maven module)

**Performance Goals**: results without a regex timeout: identical to 1.2.0, p95 within 5 % of 1.2.0 (SC-004); 0
load-induced differences over at least 10,000 validations at full capacity on 4 CPUs (SC-001)

**Constraints**: event loop never blocks (gate waits only on worker threads); a validation stays within
`FV_VALIDATION_TIMEOUT_MS` (no blocked-thread warning) and the HTTP request timeout counted from arrival; a quiet
re-check holds other validations back at most 1 s while it waits; at most 3 engine calls per request; quiet re-checks
at most 6 per minute per instance; no issue filtered or downgraded (Constitution III)

**Scale/Scope**: about 4 new main classes in `core/validator` (`RegexTimeoutIssue`, `RegexRecheck`, `ValidationGate`,
a small token bucket), edits to `ValidatorSnapshot`, `ServiceMetrics`, `ServiceRegistry`/startup wiring,
`openapi.yaml`, README, CHANGELOG; tests as listed under Testing

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | How |
|---|---|---|
| I. Non-Blocking Event Loop | PASS | `ValidatorSnapshot.validate` runs only on worker threads (`fv-validation`, `fv-packages` for the warm-up, test threads). The gate is waited on there only: the exclusive wait at most 1 s, a shared wait at most that second plus one quiet re-check, and every re-check before the deadline, so no blocked-thread warning. |
| II. Stateless, Horizontally Scalable | PASS | No shared state is added. The gate and the rate limiter are per JVM and protect that JVM's CPU; budget and metrics are per service instance (`RegexRecheck` from `ServiceRegistry`); instances stay independent and need no coordination. |
| III. FHIR Conformance and Correctness | PASS | The answer is always one engine call's complete, unmodified result. A re-check replaces a result affected by load with a complete result of the same validation; nothing is filtered, merged or downgraded, and a persistent timeout is reported as the engine reports it (Q1: A). |
| IV. Contract-First API | PASS | No shape change. The behaviour is described in `openapi.yaml` (contracts §1) before the code changes. |
| V. Test-First with Real Dependencies | PASS | Failing tests first: detection and decision rules (unit), a real runaway regex against a real context (IT), the release gate without retries (SC-002), the load IT (SC-001). |
| VI. Performance and Capacity Budgets | PASS | The spec states latency, throughput and memory impact. The hot path gains one uncontended permit and a message scan; k6 comparison with 1.2.0 recorded in `perf/results-1.3.0.md`. Re-check cost is bounded by the budget, the 3-timeout rule and the rate limit. |
| VII. Observability and Operability | PASS | Two counters (contracts §2) and one WARN log per persistent timeout with the request id; no resource content logged. No new configuration (fixed constants; existing `FV_VALIDATION_TIMEOUT_MS` reused). |
| VIII. Simplicity and Dependency Discipline | PASS | JDK `ReentrantReadWriteLock` and a few lines of token bucket; no dependency, no infrastructure, no migration. Shadowing the engine's `RegexTimeout` was rejected (research R1). |

Post-design re-check (after Phase 1): unchanged, all PASS. No Complexity Tracking entries.

## Project Structure

### Documentation (this feature)

```text
specs/013-regex-timeout-under-load/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R8
├── data-model.md        # Phase 1: in-memory entities and the re-check decision
├── quickstart.md        # Phase 1: validation scenarios
├── contracts/
│   └── http-api.md      # Phase 1: openapi description, metrics, log line
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
src/main/java/nzi/fhir/validator/
├── core/validator/
│   ├── RegexTimeoutIssue.java     # NEW: recognises a regex-timeout message (R2)
│   ├── RegexRecheck.java          # NEW: attempts, budget, 3-timeout rule, quiet limit, outcome (R3, R4)
│   ├── ValidationGate.java        # NEW: fair shared/exclusive permit per JVM (R3)
│   ├── RecheckRateLimiter.java    # NEW: quiet re-check token bucket, JVM-wide (R4)
│   ├── SnapshotBuilder.java       # EDIT: hand each snapshot the instance's RegexRecheck (R5)
│   └── ValidatorSnapshot.java     # EDIT: run the engine call through RegexRecheck, with a deadline (R4, R5)
├── core/service/FhirValidationService.java  # EDIT: pass the request deadline (arrival + HTTP timeout − 1 s)
├── core/metrics/ServiceMetrics.java   # EDIT: two counters (contracts §2)
├── ServiceRegistry.java               # EDIT: create the instance's RegexRecheck (budget, metrics listener)
└── (no web/ changes)
src/main/resources/openapi.yaml        # EDIT: validate description (contracts §1)

src/test/java/nzi/fhir/validator/
├── core/validator/
│   ├── RegexTimeoutIssueTest.java     # NEW
│   ├── RegexRecheckTest.java          # NEW: decision rules with injected attempts
│   ├── ValidationGateTest.java        # NEW: exclusive waits for shared, no upgrade, bounded wait
│   ├── RegexTimeoutIT.java            # NEW: real runaway regex, persistent, metrics, log
│   └── SharedDefinitionsDifferentialIT.java  # EDIT: remove the regex retries (SC-002); opt-in SC-001 load test
│                                              #       theSameVerdictUnderSustainedLoad (FV_LOAD_ROUNDS)
└── testsupport/RegexTimeouts.java     # REMOVED (its golden and locale tests move to GoldenOutcomesTest)
perf/results-1.3.0.md                  # NEW: k6 comparison with 1.2.0
```

**Structure Decision**: single Maven module as today. The new classes sit next to `ValidatorSnapshot` in
`core/validator`, because the re-check wraps the engine call there and is shared by every validation path (research
R5).

## Release

1.3.0 in the 1.x.x line (new metrics and a client-visible fix; no breaking change). CHANGELOG `[Unreleased]` entry under
Fixed and Added. No upgrade or rollback notes beyond "no migration" (no deployments exist yet).

## Complexity Tracking

None.
