# Research: No Spurious Regex Timeout Errors Under Load (spec 013)

All facts below were read from the jars the service builds with (HL7 core `org.hl7.fhir.*` 6.9.12, HAPI FHIR 8.2.0) and
from this repository on 2026-11-20.

## R1. Why load causes the timeout

**Finding**: `org.hl7.fhir.utilities.regex.RegexTimeout` runs every regex check through `executeWithTimeout`, which
creates a **new single-thread executor per call**, submits the match, and waits `Future.get(500, MILLISECONDS)`
(`DEFAULT_TIMEOUT = 500`, `static final`). The 500 ms is wall-clock time and covers creating and scheduling the thread,
the match itself, and any stop-the-world GC pause in between. With the validation workers keeping every CPU busy (and
allocating heavily, so GC runs often), a cheap regex such as `\S*` on a `uri` can miss the deadline before it runs.

**Consequence**: the limit cannot be configured, and the engine calls it statically. The fix has to change the
conditions under which a validation that hit the limit is re-run, not the limit.

**Alternatives considered**:
- *Shadow `RegexTimeout` with our own class on the classpath* (configurable limit, CPU time instead of wall time):
  rejected. It replaces part of the engine silently, breaks on any HL7 upgrade that changes the class, and makes the
  service differ from the reference validator (Constitution III, VIII).
- *Raise the limit by reflection*: impossible (`static final` primitive is inlined at compile time by callers).
- *Reduce validation workers*: lowers throughput for everyone to fix a rare event, and does not remove GC pauses.

## R2. Recognising a regex-timeout issue (FR-001)

**Finding**: the engine reports a timed-out regex with one of two message ids, and HAPI copies the id into
`SingleValidationMessage.getMessageId()` (`ValidatorWrapper`, `BaseValidatorBridge` call `setMessageId`):

| Message id | Text (English) | When |
|---|---|---|
| `REGEX_MATCH_TIMED_OUT` | "The regex '{0}' could not be executed" | a regex in a constraint or FHIRPath `matches()` timed out |
| `TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION` | "Exception evaluating regex '{0}' on type {1}: {2}" | the primitive-type regex threw; `{2}` is the exception message |

The second id also covers other regex exceptions (for example an invalid pattern). For a timeout, `{2}` is the
`TimeoutException` message built in `RegexTimeout` from the hard-coded English string "Regex evaluation timed out
after". That string is not taken from the message bundles, so it is never localised.

A third form was found while building the test fixture (T002): a regex in a profile **constraint**, evaluated by
FHIRPath `matches()`, that times out is reported as the constraint's own failure, with the constraint's id as message
id (for example `…/regex-trap-patient#trap-1`) and the FHIRPath engine's text appended: "Constraint failed: trap-1:
'…' (defined in …)Timeout evaluating regex: ^(.*?,){20}P$". "Timeout evaluating regex: " is concatenated in
`FHIRPathEngine` (a string constant in the class), not taken from the message bundles, so it is never localised either.

**Decision**: an issue is a regex-timeout issue when
1. its message id is `REGEX_MATCH_TIMED_OUT`, or
2. its message id is `TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION` and its message contains "Regex evaluation
   timed out after", or
3. its message contains "Timeout evaluating regex: " (any message id: FHIRPath `matches()` in a constraint).

None of these depends on the message language: (1) is an id, and the texts in (2) and (3) are built in code.

**Alternatives considered**: matching the English template text (what the tests do today): rejected for the service,
because the templates are localised.

## R3. How a re-check escapes load (FR-002)

**Decision**: two re-checks, in increasing cost, each only when the previous attempt reported a regex timeout:

1. **Plain re-check**: validate again at once on the same worker. Most load-induced timeouts are a GC pause or a
   momentary CPU spike; a second attempt a few hundred milliseconds later usually does not meet the same pause. Cheap:
   no other request is affected.
2. **Quiet re-check**: validate again with **no other validation running on the instance**. Every validation holds a
   shared permit of an instance-wide `ValidationGate` (a fair read/write lock) while it runs; the quiet re-check takes
   the exclusive permit. Taking it waits until the validations already running finish, and validations that start
   later wait for the quiet re-check. With the CPUs free and allocation low, a load-induced timeout does not recur; a
   timeout that persists is a property of the input (FR-005).

The validation releases its shared permit before it asks for the exclusive one, so it never upgrades a lock it holds
(no deadlock). A fair lock makes later validations queue behind a waiting quiet re-check. That queue must stay short:
if a long validation is already running (for example a hostile resource with many timeouts), a quiet re-check that
waited for it would hold every new validation back too. The quiet re-check therefore waits for the exclusive permit at
most **1 second** (`QUIET_WAIT_MAX`); if it does not get it, it gives up (reason `busy`), and the validations queued
behind it continue at once. Other requests are held up by at most that second plus the quiet re-check itself.

**Rationale**: the plain re-check alone does not satisfy FR-002 ("simply repeating under the same load is not
enough"); the quiet re-check alone would pause the instance for every timeout, including the many a plain re-check
fixes. Together they keep the pause rare.

**Alternatives considered**:
- *Back off for a fixed time before re-checking*: does not remove the competing load.
- *A separate low-concurrency executor for re-checks*: other validations still run and still compete for the CPU.
- *Answer 503 and let the client retry*: rejected in Clarifications (Q1: A).

## R4. Bounding the cost and abuse (FR-006, User Story 2)

A hostile resource can contain many values that time out every time (each costs 500 ms per attempt). Without bounds, a
quiet re-check of such a resource would pause the whole instance for many seconds, and a stream of them would pause it
repeatedly.

**Decision** (fixed constants; no new configuration, as the spec assumes):
- **Deadline**: each validation has a deadline, the earlier of (a) when the request arrived plus the HTTP request
  timeout (`FV_HTTP_REQUEST_TIMEOUT_MS`, default 60 s) minus a 1 s margin for writing the response, and (b) when the
  validation started on its worker plus the validation budget (`FV_VALIDATION_TIMEOUT_MS`, default 30 s). (a) counts
  time spent waiting for admission, so a request that waited long never gets re-checked past its HTTP timeout; (b)
  keeps the worker under the point where Vert.x reports a blocked thread. `FhirValidationService` knows when the
  request was admitted and passes (a) to `ValidatorSnapshot.validate`; callers without a request (warm-up, tests) pass
  none, and only (b) applies.
- **Time budget**: a re-check starts only if now plus twice the duration of the last attempt is before the deadline.
- **Exclusive wait**: at most `QUIET_WAIT_MAX` = 1 s, and never past the deadline (`tryLock` with the smaller of the
  two). Not granted → reason `busy` (R3).
- **Many timeouts mean input, not load**: the quiet re-check is skipped when the last attempt had more than 3
  regex-timeout issues. Load-induced timeouts are one or two per resource; dozens point at the input.
- **Rate**: at most 6 quiet re-checks per minute per instance (token bucket). Beyond that, the request is answered with
  its last result (FR-005), counted as persistent with reason `quiet-limit`.

**Rationale**: a legitimate instance under load needs a quiet re-check only occasionally (SC-001 measures it); the
limits cap the pause a hostile client can cause at a few seconds per minute.

## R5. Where the re-check lives

**Decision**: inside `ValidatorSnapshot.validate`, around the single `validator.validateWithResult(body, options)`
call. Parsing, the Bundle size check and profile resolution happen once. All attempts run inside the same
`TerminologyScope` and `TerminologyBudget`, so a re-check reuses cached code results and does not get a second
terminology budget (spec edge case "Terminology").

**Rationale**: every validation path calls this method: the HTTP service (`FhirValidationService`), the warm-up after a
build (`SnapshotBuilder`), and the release-gate test (`SharedDefinitionsDifferentialIT`), which needs the behaviour
without test-side retries (SC-002). The method already runs on worker threads only (Constitution I).

**Shared state**: one `ValidationGate` and one quiet-re-check rate limiter per JVM, since the CPU they protect is per
JVM. Everything else is per service instance: each `ValidatorSnapshot` gets a `RegexRecheck` from its
`SnapshotBuilder`, carrying the validation budget and the metrics listener of the `ServiceRegistry` that built it.
Several registries in one test JVM therefore report to their own metrics. Tests that count re-checks or quiet tokens
reset the JVM-wide limiter first (`RegexRecheck.resetSharedStateForTests()`), so the tokens other tests used never
leak into their counts.

## R6. What is reported (FR-003, FR-005, FR-008, FR-009)

- The response is always one attempt's complete `ValidationResult`: the first attempt without a regex timeout, or the
  last attempt. Nothing is merged or filtered.
- Metrics (new): `fv_validation_regex_rechecks_total{fhir_version, kind="plain"|"quiet"}` counts re-checks made;
  `fv_validation_regex_timeouts_total{fhir_version, outcome="resolved"|"persistent", reason}` counts requests whose
  first attempt timed out, by final outcome. `reason` is empty for resolved and one of `input` (still timed out after
  the quiet re-check), `many-timeouts`, `quiet-limit`, `budget`, `busy` (the exclusive permit was not granted within
  `QUIET_WAIT_MAX`) for persistent.
- A persistent timeout is logged at WARN with the context key; the request id is on the line already (contextual
  logging, Principle VII). The resource content is never logged.
- Usage records and `fv_validations_total` count the request once. The worker's CPU and allocation figures (spec 008)
  include all attempts, because they are measured around the whole worker task.

## R7. Testing a timeout on demand (Constitution V)

- **Persistent (real input)**: confirmed in T002. Java 17 memoises simple nested loops, so `(a+)+$` and `(a|aa)+$`
  finish in under a millisecond; `^(.*?,){20}P$` on `"a,"` × 30 runs for seconds with both `matches()` and `find()`. The
  fixture is a Patient profile constraint `matches('^(.*?,){20}P$')` on `Patient.name.family`: each such family times
  out once (about 0.5 s) and is reported as form (3) of R2, as the constraint's error.
- **Load-induced (resolved)**: cannot be produced deterministically by load. `RegexRecheck` takes the attempt as a
  function, so unit tests feed it results with and without timeout issues and assert attempts, gate use, budget and
  rate limits.
- **SC-001/SC-002**: the release-gate test runs its concurrent phase without `RegexTimeouts.retry`, and a load test
  validates the golden corpus at least 10,000 times at full capacity and compares each result with the result alone.

## R8. Performance of the common path (FR-007, Principle VI)

The only work added for a result without a timeout is one uncontended shared-permit acquire and release per validation
(nanoseconds) and one scan of the result's messages for two message ids. The k6 comparison with 1.2.0 (as in spec 012)
checks that p95 stays within 5 %.
