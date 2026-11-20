---

description: "Task list for feature 013: no spurious regex timeout errors under load"
---

# Tasks: No Spurious Regex Timeout Errors Under Load

**Input**: Design documents from `/specs/013-regex-timeout-under-load/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/http-api.md, quickstart.md

**Tests**: Required. Constitution Principle V: new behaviour starts with a failing test (JUnit 5 + vertx-junit5);
validation-correctness tests use fixture resources with known outcomes. Write each test task first and see it fail
before the implementation tasks of the same phase.

**Organization**: Tasks are grouped by user story (spec.md US1–US3) so each can be implemented and tested on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: The user story (US1–US3)

## Path Conventions

Single Maven project. `main/` = `src/main/java/nzi/fhir/validator/`, `test/` = `src/test/java/nzi/fhir/validator/`,
`resources/` = `src/main/resources/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The contract first (Constitution IV) and the one fixture every story's tests need.

- [X] T001 Describe the re-check behaviour in the `POST /{version}/validate` description in `resources/openapi.yaml`, with the text of contracts/http-api.md §1 ("Regex checks: the validation engine stops any single regex check after 500 ms. …"); no path, parameter, schema or status change
- [X] T002 Add a runaway-regex fixture to `test/testsupport/TestPackages.java` (research R7): `regexTrapPackage()` = `test.regex.trap#1.0.0` (R4) with a Patient profile `http://example.org/fhir/StructureDefinition/regex-trap-patient` whose `Patient.name.family` has the constraint `trap-1` = `matches('^(.*?,){20}P$')`, and `regexTrapPatient(int values)` = a Patient claiming that profile with `values` names whose `family` is `"a,"` × 30. Done: confirmed with a throw-away run that one value times out once (about 0.5 s), reported as the constraint's error with "Timeout evaluating regex: " (research R2 form 3). The regex extension was not needed; `(a+)+$` does not run away on Java 17

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Recognising a regex-timeout issue, the instance-wide gate, and the re-check loop (plain then quiet).
Every story builds on these.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Tests (write first, must fail)

- [X] T003 [P] `test/core/validator/RegexTimeoutIssueTest.java`: an issue is a regex-timeout issue in the three forms of data-model "Regex-timeout issue" (`REGEX_MATCH_TIMED_OUT`; `TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION` with "Regex evaluation timed out after"; any id with "Timeout evaluating regex: ", e.g. `…#trap-1` "Constraint failed: trap-1: '…' (defined in …)Timeout evaluating regex: ^(.*?,){20}P$"); `TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION` with another exception text is not; a message with a null id is not; a German template text with id `REGEX_MATCH_TIMED_OUT` is (language-independent); `count(ValidationResult)` counts them; severity is never used
- [X] T004 [P] `test/core/validator/ValidationGateTest.java`: shared permits run concurrently; an exclusive request waits until every running shared permit is released; a shared request made while an exclusive request waits queues behind it (fair); the exclusive wait gives up after its timeout and returns empty without running the work; the work's exception is rethrown and the permit released
- [X] T005 [P] `test/core/validator/RegexRecheckTest.java` (attempts injected as a function returning a `ValidationResult`; the gate injected): no timeout → 1 attempt, gate used in shared mode only, the same result object returned; a timeout then none → 2 attempts (`plain`), outcome `resolved`; timeouts on `first` and `plain` then none → 3 attempts, the third under the exclusive permit, outcome `resolved`; timeouts on all three → the third attempt's result object returned unchanged, outcome `persistent`, reason `input`; never more than 3 attempts (FR-004)

### Implementation

- [X] T006 [P] `main/core/validator/RegexTimeoutIssue.java`: `static boolean is(SingleValidationMessage)` and `static int count(ValidationResult)` with the rules quoted in T003; constants for both message ids and the text "Regex evaluation timed out after" (research R2)
- [X] T007 [P] `main/core/validator/ValidationGate.java`: a fair `ReentrantReadWriteLock`; `<T> T shared(Callable<T>)` and `<T> Optional<T> exclusive(Callable<T>, long timeoutNanos)` (`writeLock().tryLock(timeout)`); one JVM-wide `INSTANCE`; never called while holding the other permit (data-model "An attempt never holds a permit while it asks for another")
- [X] T008 `main/core/validator/RegexRecheck.java`, one object per service instance (data-model "RegexRecheck"): constructor `(long validationBudgetMs, Listener listener, ValidationGate gate, RecheckRateLimiter limiter, LongSupplier nanoClock)`, defaults `NONE` listener and the JVM-wide gate and limiter, and `RegexRecheck.DEFAULT` (30,000 ms, no listener) for callers without a service instance; `Result run(Supplier<ValidationResult> engineCall, OptionalLong requestDeadlineNanos)` → `{result, attempts, outcome (none|resolved|persistent), reason}` following the data-model decision flow for the `first → plain → quiet` path; `first` and `plain` under `gate.shared`, `quiet` under `gate.exclusive`; returns one attempt's result object, never a merged or edited one (FR-003); `static resetSharedStateForTests()` refills the JVM-wide limiter (depends on T006, T007)

**Checkpoint**: T003–T005 pass.

---

## Phase 3: User Story 1 - The same resource gets the same verdict, however busy the service is (Priority: P1) 🎯 MVP

**Goal**: A load-induced regex timeout never reaches the client; every validation path re-checks.

**Independent Test**: The release-gate comparison passes with no test-side retry (SC-002), and the opt-in load test
finds 0 differences over at least 10,000 validations (SC-001).

### Tests (write first, must fail)

- [X] T009 [P] [US1] `test/core/validator/ValidatorSnapshotRecheckTest.java`, using `ConcurrencyIT.snapshotWith(IValidatorModule)` with a module that adds a `SingleValidationMessage` with id `REGEX_MATCH_TIMED_OUT` (severity ERROR) on its first N calls: N=1 → the outcome is valid with no timeout issue and the module ran twice; N=2 → ran three times, valid; N=3 → the timeout issue is in the outcome as an error; the body is parsed once and the terminology scope and budget are opened once per `validate` call (FR-002, FR-003, edge case "Terminology"); a Bundle of 3 entries whose module adds the timeout to one entry on the first call only → the whole request is validated again and the outcome holds exactly the second attempt's issues, never a mix (edge case "bundle entry"); call `RegexRecheck.resetSharedStateForTests()` before each case
- [X] T010 [P] [US1] `test/core/validator/SharedDefinitionsDifferentialIT.java`: remove every `RegexTimeouts.retry` call and the deferral of regex-timeout cases in `runConcurrently` (SC-002); it must pass once T012 is done
- [X] T011 [P] [US1] Opt-in load test (SC-001, quickstart §4), `@EnabledIfEnvironmentVariable(named = "FV_LOAD_ROUNDS", matches = "[1-9][0-9]*")`: validate the release-gate corpus `FV_LOAD_ROUNDS` times in random order on 8 threads and assert every result equals the one recorded alone; print the number of validations and of `plain`/`quiet` re-checks. Done as `SharedDefinitionsDifferentialIT.theSameVerdictUnderSustainedLoad` instead of a separate `RegexTimeoutLoadIT`, so it reuses the contexts that class builds (a subclass would re-run the whole release gate)

### Implementation

- [X] T012 [US1] Run the engine call in `main/core/validator/ValidatorSnapshot.java` `validate(...)` through the snapshot's `RegexRecheck` (`regexRecheck.run(() -> validator.validateWithResult(body, options), requestDeadline)`), inside the existing terminology scope and budget, after parsing and profile resolution (research R5); the `Outcome` is built from the returned result only. Add an overload taking `OptionalLong requestDeadlineNanos`; the existing overloads pass empty. The snapshot's `RegexRecheck` comes from its constructor, `RegexRecheck.DEFAULT` when not given; `main/core/validator/SnapshotBuilder.java` passes the instance's one (T019)
- [X] T013 [US1] Remove `test/testsupport/RegexTimeouts.java` once no test uses it, and reword the comment in `test/testsupport/GoldenOutcomes.java` `assertSame` that refers to it; move `RegexTimeoutsTest.aGoldenDifferenceListsEveryIssue` to `test/testsupport/GoldenOutcomesTest.java` and `theTestJvmRunsInEnglish` there too (the surefire `argLine` stays: it keeps test messages stable), and delete `test/testsupport/RegexTimeoutsTest.java`

**Checkpoint**: T009 and T010 pass; T011 passes with `FV_LOAD_ROUNDS=75` on a 4-CPU machine.

---

## Phase 4: User Story 2 - Real runaway patterns are still caught and reported (Priority: P1)

**Goal**: A genuine timeout is reported as the engine reports it, in bounded time, and cannot pause the instance.

**Independent Test**: The runaway-regex fixture is answered 200 with the timeout as an error within 3 × its
single-validation time, and a stream of them leaves other requests served (SC-003).

### Tests (write first, must fail)

- [X] T014 [P] [US2] Extend `test/core/validator/RegexRecheckTest.java` (clock and limiter injected): more than 3 timeouts after `plain` → no quiet re-check, outcome `persistent`, reason `many-timeouts`; the 7th quiet re-check within a minute → skipped, reason `quiet-limit`, and allowed again after the bucket refills ("capacity 6, refilled at 6 per minute"); "time spent in this validation so far + 2 × the last attempt's duration ≤ the validation budget" false before `plain` or before `quiet` → reason `budget`; the deadline is "the earlier of (a) request arrival + `FV_HTTP_REQUEST_TIMEOUT_MS` − 1,000 ms … and (b) validation start on the worker + `FV_VALIDATION_TIMEOUT_MS`" (data-model), so a request deadline nearly passed stops a re-check even with validation budget left; the exclusive permit not granted within "`QUIET_WAIT_MAX` = 1,000 ms" (or before the deadline, if earlier) → reason `busy`, and shared requests queued behind it then proceed; a token is taken only when the quiet re-check starts waiting
- [X] T015 [P] [US2] `test/core/validator/RegexTimeoutIT.java` (`BaseTestContainer`, `CoreHarness`; fixture from T002 stored and included for R4): `regexTrapPatient(1)` through the validation service → a completed result (not a 503), the timeout issue present with the engine's severity, 3 engine calls, elapsed ≤ 3 × the single-validation time + 1 s; `regexTrapPatient(5)` → 2 engine calls (reason `many-timeouts`); 4 trap requests in parallel with 8 `minimalPatient` requests (within the harness's admission limit) → every normal request answered valid, every trap answered with its timeout (never 503; edge case "Admission", also 6 traps at once); with a `regexTrapPatient(30)` validation in flight (about 15 s of timeouts), a request needing a quiet re-check is answered within 3 s with reason `busy`, and `minimalPatient` requests sent meanwhile are each answered within 2 s (US2 scenario 3); call `RegexRecheck.resetSharedStateForTests()` before each case
- [X] T016 [P] [US2] `test/core/validator/ValidationGateTest.java`: a quiet re-check that waits for the exclusive permit never holds a shared permit meanwhile (a thread holding a shared permit and asking for the exclusive one is refused or impossible by API)

### Implementation

- [X] T017 [US2] `main/core/validator/RecheckRateLimiter.java` (one JVM-wide instance, with a reset for tests): a token bucket, "capacity 6, refilled at 6 per minute", with an injectable `LongSupplier` clock; `boolean tryTake()`
- [X] T018 [US2] Add the safeguards to `main/core/validator/RegexRecheck.java` in the data-model order: the deadline (earlier of the request deadline passed in and validation start + `validationBudgetMs`) and "now + 2 × the last attempt's duration ≤ the deadline" before each re-check, "skipped when the last attempt had more than 3 regex-timeout issues", the rate limiter, and `exclusive(..., min(QUIET_WAIT_MAX = 1,000 ms, time to deadline))`; reasons `budget`, `many-timeouts`, `quiet-limit`, `busy`, `input`
- [X] T019 [US2] Wire the deadline and budget: `main/ServiceRegistry.java` creates the instance's `RegexRecheck(config.validationTimeoutMs(), listener, ...)` and gives it to `SnapshotBuilder`; `main/core/service/FhirValidationService.java` gets `config.httpRequestTimeoutMs()` and passes `requestDeadlineNanos = started + httpRequestTimeoutMs − 1,000 ms` (`started` is taken when the request reaches `validate`, before admission) to `ValidatorSnapshot.validate`

**Checkpoint**: T014–T016 pass.

---

## Phase 5: User Story 3 - Operators can see when re-checks happen (Priority: P3)

**Goal**: Counters and a log line show how often re-checks happen and how they end.

**Independent Test**: One resolved and one persistent timeout are visible in `/metrics`, and the persistent one in the
log without resource content (SC-005).

### Tests (write first, must fail)

- [X] T020 [P] [US3] `test/core/validator/RegexTimeoutIT.java`: after a persistent timeout, `fv_validation_regex_timeouts_total{fhir_version="R4",outcome="persistent",reason="input"}` is 1 and `fv_validation_regex_rechecks_total{fhir_version="R4",kind="plain"}` and `{kind="quiet"}` are 1; exactly one WARN line "Regex timeout persisted for R4:test.regex.trap#1.0.0 after 3 attempt(s) (reason input)" that contains neither the value nor the pattern; usage records and `fv_validations_total` count the request once (FR-009), and the usage record's duration is at least the sum of the three attempts (edge case "Usage records"); call `RegexRecheck.resetSharedStateForTests()` first
- [X] T021 [P] [US3] `test/core/validator/ValidatorSnapshotRecheckTest.java`: a resolved re-check (module N=1) increments `fv_validation_regex_timeouts_total{outcome="resolved",reason=""}` and `fv_validation_regex_rechecks_total{kind="plain"}` once, through a `SimpleMeterRegistry`

### Implementation

- [X] T022 [US3] `main/core/metrics/ServiceMetrics.java`: `recordRegexRecheck(SupportedFhirVersion, String kind)` and `recordRegexTimeout(SupportedFhirVersion, String outcome, String reason)` registering `fv.validation.regex.rechecks` and `fv.validation.regex.timeouts` with the labels of contracts/http-api.md §2
- [X] T023 [US3] The `RegexRecheck.Listener` (`onRecheck(version, kind)`, `onOutcome(version, contextKey, outcome, reason, attempts)`) of each service instance: `ServiceRegistry` builds it from its own `ServiceMetrics` and passes it in the instance's `RegexRecheck` (T019), never through JVM-wide state, so several registries in one JVM count separately; on `persistent` `RegexRecheck` (which is given the version and context key) logs once at WARN "Regex timeout persisted for <fhirVersion>:<context key> after <n> attempt(s) (reason <reason>)" (contracts §3)

**Checkpoint**: T020 and T021 pass.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T024 [P] README: a "Regex checks under load" paragraph in the validation section (re-checks, what a persistent timeout looks like) and the two metrics in the `GET /metrics` row and the metrics list, in `README.md`
- [X] T025 [P] `CHANGELOG.md` `[Unreleased]`: Fixed (load-induced regex timeouts no longer reach clients; how a persistent one is answered) and Added (the two metrics); bump `pom.xml` `<version>` and `resources/openapi.yaml` `info.version` to `1.3.0`
- [X] T026 [P] k6 comparison with 1.2.0 (quickstart §5): `base` and `ig` runs of `perf/compare-run.sh` against `ghcr.io/nazrulworld/fhir-validation-service:1.2.0` and this branch; record p95, requests/s, failed requests and the re-check counters in `perf/results-1.3.0.md` (SC-004, Principle VI)
- [X] T027 Run `SharedDefinitionsDifferentialIT#theSameVerdictUnderSustainedLoad` with `FV_LOAD_ROUNDS=75` and record the validation count, differences (expected 0) and re-check counts in `perf/results-1.3.0.md` (SC-001)
- [X] T028 Run every quickstart scenario and `./mvnw -B verify`; no blocked-thread warnings (Principle I); mark the done tasks here — done: quickstart §1–§5 run individually; final `mvn verify` 980 tests, 0 failures, 1 skipped (the opt-in load test), no blocked-thread warnings (2026-11-20)

---

## Dependencies & Execution Order

- **Setup (T001–T002)**: no dependencies. T002 decides the fixture used by T015 and T020.
- **Foundational (T003–T008)**: tests T003–T005 first; T006 and T007 in parallel; T008 needs both. Blocks every story.
- **US1 (T009–T013)**: needs Phase 2. T012 makes T009 and T010 pass. T013 after T010.
- **US2 (T014–T019)**: needs Phase 2 and T002; T018 needs T017; T019 needs T012 (the snapshot takes its
  `RegexRecheck` and the deadline). Independent of US1's tests, but T015 exercises the re-check through
  `ValidatorSnapshot`, so it needs T012.
- **US3 (T020–T023)**: needs T012 and T018; T023 needs T022.
- **Polish (T024–T028)**: after the stories; T027 needs T011 and T012.

### Parallel opportunities

- Phase 2 tests: T003, T004, T005 together; then T006 with T007.
- US1 tests: T009, T010, T011 together.
- US2 tests: T014, T015, T016 together.
- US3 tests: T020 with T021.
- Polish: T024, T025, T026 together.

### Parallel example (Phase 2)

```text
Task: "RegexTimeoutIssueTest in test/core/validator/RegexTimeoutIssueTest.java"
Task: "ValidationGateTest in test/core/validator/ValidationGateTest.java"
Task: "RegexRecheckTest in test/core/validator/RegexRecheckTest.java"
```

## Implementation Strategy

### MVP (US1)

1. Phase 1, Phase 2.
2. US1: every validation path re-checks; the release gate passes with no retry.
3. Stop and validate: T009, T010 pass; the load test finds no difference.

US1 alone is not releasable: without US2's safeguards a hostile input could pause the instance. Release after US2.

### Incremental delivery

4. US2: budget, many-timeouts rule and rate limit; the runaway fixture is answered in bounded time.
5. US3: counters and the log line.
6. Polish: README, CHANGELOG, 1.3.0, perf results, full verify.

---

## Phase 7: Convergence

- [X] T029 [US2] Bound how long a quiet re-check holds every other validation back, not only how long it waits for the exclusive permit: in `main/core/validator/RegexRecheck.java` skip the quiet re-check (outcome `persistent`, an existing reason such as `busy`, or a new reason documented in contracts/http-api.md §2, README and `openapi.yaml`) when the previous attempt's duration shows the exclusive engine call would hold other validations for more than about one second; extend `test/core/validator/RegexRecheckTest.java` (a slow injected attempt → no exclusive permit taken) and `test/core/validator/RegexTimeoutIT.java` (normal requests sent while a quiet re-check of a slow resource with ≤ 3 timeouts would run are answered within about 1 s plus their own time) per FR-006, US2/AC3 (partial)
- [X] T030 [US2] Keep every attempt within `FV_VALIDATION_TIMEOUT_MS` counted from validation start on the worker: pass the worker start (taken at `meter.workerStart()` in `main/core/service/FhirValidationService.java`, or at the start of `ValidatorSnapshot.validate`) into `RegexRecheck.run` instead of using the time `run` is called (after parsing and profile resolution), and include the exclusive wait (`min(QUIET_WAIT_MAX, …)`) in the quiet re-check's "fits before the deadline" check so wait + attempt cannot pass the deadline; add `RegexRecheckTest` cases for both per FR-006, plan: constraints "a validation stays within FV_VALIDATION_TIMEOUT_MS", data-model "Deadline" (partial)
