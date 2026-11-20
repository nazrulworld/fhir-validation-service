# Feature Specification: No Spurious Regex Timeout Errors Under Load

**Feature Branch**: `013-regex-timeout-under-load`

**Created**: 2026-11-20

**Status**: Draft

**Input**: User description: "fix spurious regex timeout errors under load"

## Context

The validation engine checks many values against regular expressions (for example, that a `uri` contains no
whitespace). It gives every such check a fixed time limit of half a second, as protection against patterns or values
that would otherwise run for a very long time. The limit counts elapsed time, not the time the check actually runs.
When the instance is busy (many validations at once, garbage collection, a small host), a check can wait past the
limit before it even starts.

The result is a wrong answer. A valid resource can get an extra error-level issue ("Exception evaluating regex ... Regex
evaluation timed out", or "The regex ... could not be executed") and be reported as invalid. Whether it does depends on
how busy the instance happened to be, not on the resource. The project's release gate (the 1.0.0 golden-outcome
comparison, spec 010) showed this on CI runners. Since spec 012 the gate retries such cases, so it no longer catches
the problem in the service.

The time limit itself stays: it is what protects the service from pathological patterns and values. This feature
removes the load-induced false errors while keeping that protection.

## Clarifications

### Session 2026-11-20

- Q: What does a client get when a regex timeout persists after the re-checks? → A: The result as the engine reports
  it, with the timeout as an error-level issue (no 503, no opt-in downgrade).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The same resource gets the same verdict, however busy the service is (Priority: P1)

A client validates a resource. The answer depends only on the resource, the context and the request options, not on
how many other requests the instance is handling at that moment.

**Why this priority**: A validator whose verdict changes with load is unreliable. Clients act on "invalid" (they
reject a submission, raise an alert), and a false "invalid" that disappears on retry undermines trust in every result.

**Independent Test**: Validate a fixed set of resources once with nothing else running, then again while the instance
runs at its configured validation capacity. Every result under load equals the result without load.

**Acceptance Scenarios**:

1. **Given** a valid resource whose validation involves regex checks, **When** it is validated while the instance runs
   at full validation capacity, **Then** the result is valid, with the same issues as when it is validated alone.
2. **Given** an invalid resource, **When** it is validated under load, **Then** it gets the same issues as when it is
   validated alone, and no extra regex-timeout issue.
3. **Given** a resource validated under load where a regex check timed out only because of load, **When** the response
   is returned, **Then** it contains no regex-timeout issue.

---

### User Story 2 - Real runaway patterns are still caught and reported (Priority: P1)

A resource, or a profile it is validated against, contains a pattern or value that makes a regex check run for a very
long time (for example, a deliberately hostile value). The service still stops that check and reports it, and the
request still completes in bounded time.

**Why this priority**: The time limit is a safety feature. Removing false errors must not reopen the door to a request
that ties up a validation worker indefinitely.

**Independent Test**: Validate a resource whose value makes a regex check exceed the limit even with nothing else
running. The response reports the timeout, and the request finishes within the bound in FR-006.

**Acceptance Scenarios**:

1. **Given** a resource with a value that times out a regex check even on an idle instance, **When** it is validated,
   **Then** the response is a normal validation result that contains the regex-timeout issue as an error (FR-005).
2. **Given** many such requests at once, **When** they are validated, **Then** each completes within the FR-006 bound,
   and other requests keep being served.
3. **Given** a long validation of such a resource already running, **When** another request needs a re-check, **Then**
   other requests are not held up for more than about one second, and the re-checking request is answered with its
   last result if it cannot run its re-check.

---

### User Story 3 - Operators can see when re-checks happen (Priority: P3)

An operator sees how often validations needed a re-check because of a regex timeout, and how often a timeout persisted.
A rising rate means the instance is short of CPU or a hostile input is being sent.

**Why this priority**: It turns a silent quality problem into a capacity signal, but the service is correct without it.

**Independent Test**: Cause one load-induced re-check and one persistent timeout, and read both counts from the
service's metrics.

**Acceptance Scenarios**:

1. **Given** a validation that needed a re-check and then had no timeout, **When** the operator reads the metrics,
   **Then** the re-check is counted as resolved.
2. **Given** a validation whose timeout persisted, **When** the operator reads the metrics and the log, **Then** it is
   counted as persistent and logged without the resource's content.

---

### Edge Cases

- **Several timeouts in one resource**: one re-check covers all of them; the result is taken from a validation that
  completed without a load-induced timeout, never assembled from parts of different validations.
- **A timeout in only one of several validations of a bundle entry**: the whole request is re-checked; the response
  never mixes issues from different attempts.
- **Terminology**: a re-check must not call terminology servers again for codes the first attempt already checked
  (cached answers are reused as today), so a re-check does not double terminology traffic.
- **Usage records**: a re-checked request counts as one validation, not one per attempt (spec 008). Its recorded
  duration includes the re-check.
- **Admission**: a re-check uses the request's existing admission slot; it is not refused with 503 `overloaded`
  halfway through.
- **Draining** (shutdown): a request already being re-checked completes or is answered within the shutdown grace
  period like any other.
- **Persistent timeout and caching**: a result that still contains a timeout is never cached or reused for another
  request (results are not cached today; this stays so).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST recognise a regex-timeout issue in a validation result: the two messages the validation
  engine uses for a regex check that ran past its time limit, independent of the message language.
- **FR-002**: When a validation result contains a regex-timeout issue, the service MUST validate the same resource
  again, with the same context, profiles and options, before answering. The re-check MUST be arranged so that a
  timeout caused only by load does not happen again (for example, with less competition for the CPU); simply repeating
  the same validation under the same load is not enough.
- **FR-003**: If a re-check completes without a regex-timeout issue, the service MUST answer with that re-check's
  result, unchanged and complete. Issues MUST NOT be removed from or added to it.
- **FR-004**: The service MUST re-check at most twice per request.
- **FR-005**: If the regex-timeout issue is still present after the last re-check, the service MUST answer with the
  last re-check's result as the engine reports it: the timeout stays an error-level issue, and the status, headers and
  body are those of any other validation result. The timeout is never dropped or downgraded (Constitution III), and the
  request is never answered with 503 because of it.
- **FR-006**: A request MUST complete within its existing request timeout, counted from when the request arrived
  (time spent waiting for admission included), and a validation MUST stay within the validation time budget.
  Re-checks MUST NOT extend either; when too little time is left for another re-check, the service answers with the
  last result as in FR-005. A re-check MUST NOT make other requests wait more than about one second.
- **FR-007**: Results without a regex-timeout issue MUST be unchanged: same issues, same status and same headers, with
  no extra validation work; their latency stays within SC-004.
- **FR-008**: The service MUST count re-checks by outcome (resolved, persistent) per FHIR version, with the reason a
  timeout persisted, and log each persistent timeout with the context key and the request id, never the resource
  content.
- **FR-009**: A re-check MUST NOT change what is recorded about the request: one usage record, one validation in the
  existing metrics, with its total duration.
- **FR-010**: The API contract (`openapi.yaml`) MUST describe the behaviour: that the service re-checks a result
  affected by a regex timeout, and what a client gets when the timeout persists.

### Key Entities

- **Regex-timeout issue**: an issue in a validation result that reports a regex check stopped by the engine's time
  limit. Recognised by the engine's message, not by its severity.
- **Re-check**: a further, complete validation of the same request, made only because the previous result contained a
  regex-timeout issue. Its outcome is *resolved* (no timeout) or *persistent* (the timeout is still there).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Validating the project's golden corpus (spec 010) under sustained full load — at least 10,000
  validations on 8 concurrent threads on a 4-CPU machine, twice the default validation capacity — gives 0 results that
  differ from the same resource validated alone.
- **SC-002**: The project's release-gate comparison passes on CI without any retry for regex timeouts in the test code.
- **SC-003**: A resource whose regex check times out even on an idle instance still gets the timeout as an error (FR-005), and the
  request completes in at most 3 times its single-validation time, within the request timeout.
- **SC-004**: For requests without a regex timeout, results are identical to 1.2.0 and p95 latency stays within 5 % of
  1.2.0 for the same load.
- **SC-005**: An operator can tell from the metrics alone how many validations needed a re-check in the last hour, and
  how many of those still timed out.

## Performance and Capacity

Constitution Principle VI.

- **Latency**: unchanged for results without a regex timeout (no extra work). A re-checked request costs up to two more
  validations of the same resource, bounded by the request timeout (FR-006).
- **Throughput**: re-checks use the request's own validation slot. Under heavy load, more re-checks mean slightly less
  throughput; the re-check counter (FR-008) shows how much.
- **Memory**: no new caches. A re-check holds one more result in memory at a time, for the duration of the request.

## Assumptions

- The validation engine's half-second limit cannot be changed in the engine itself (it is fixed in the version the
  service uses); the fix lives in how the service runs and re-runs validations.
- Re-checking the whole request is acceptable: a timeout is rare when the instance has spare capacity, so the extra
  cost is small and bounded (FR-004, FR-006).
- Only the regex time limit is in scope. Other load effects (terminology server timeouts, admission refusals) are
  handled as today.
- No API path, payload or status code changes, and no new configuration setting is required.
- No database migration is needed.
- The project releases in the 1.x.x line; this ships in the next minor or patch release. No deployments exist yet, so
  no rolling-upgrade or rollback guidance is needed.
