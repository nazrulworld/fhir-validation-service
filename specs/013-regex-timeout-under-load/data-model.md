# Data Model: No Spurious Regex Timeout Errors Under Load (spec 013)

Nothing is stored: no table, column or migration (the next migration number stays V18). Everything below lives in
memory, per JVM, for the duration of one validation or for the life of the instance.

## Regex-timeout issue

A message in a HAPI `ValidationResult` that reports a regex check stopped by the engine's 500 ms limit (research R2).

Any one of:

1. `messageId` is `REGEX_MATCH_TIMED_OUT`;
2. `messageId` is `TYPE_SPECIFIC_CHECKS_DT_PRIMITIVE_REGEX_EXCEPTION` and `message` contains "Regex evaluation timed
   out after";
3. `message` contains "Timeout evaluating regex: " (FHIRPath `matches()` in a constraint; the message id is the
   constraint's id).

Severity is not used: the engine decides it and it is reported unchanged.

## Attempt

One complete call of the validation engine for a request.

| Field | Meaning |
|---|---|
| `kind` | `first`, `plain` or `quiet` |
| `result` | the engine's `ValidationResult`, complete |
| `timeouts` | number of regex-timeout issues in `result` |
| `durationNanos` | wall time of the attempt (for the budget) |

## Re-check decision

After each attempt with `timeouts > 0`, in this order (research R3, R4):

```text
first ──timeouts>0──▶ budget allows? ──no──▶ answer (persistent, reason=budget)
                          │yes
                          ▼
                       plain ──timeouts=0──▶ answer (resolved)
                          │timeouts>0
                          ▼
              timeouts > 3 ? ──yes──▶ answer (persistent, reason=many-timeouts)
                          │no
              quiet token available ? ──no──▶ answer (persistent, reason=quiet-limit)
                          │yes
              budget allows ? ──no──▶ answer (persistent, reason=budget)
                          │yes
              exclusive permit within min(1 s, deadline) ? ──no──▶ answer (persistent, reason=busy)
                          │yes
                          ▼
                       quiet ──timeouts=0──▶ answer (resolved)
                          │timeouts>0
                          ▼
                       answer (persistent, reason=input)
```

- **Deadline**: the earlier of (a) request arrival + `FV_HTTP_REQUEST_TIMEOUT_MS` (default 60,000 ms) − 1,000 ms,
  passed in by `FhirValidationService` (absent for warm-up and tests), and (b) validation start on the worker +
  `FV_VALIDATION_TIMEOUT_MS` (default 30,000 ms).
- **Budget allows**: now + 2 × the last attempt's duration ≤ the deadline.
- **Answer**: the last attempt's result, complete and unchanged (FR-003, FR-005). At most three attempts (FR-004).
- A token is taken only when the quiet re-check actually starts waiting for the exclusive permit.

## ValidationGate (one per JVM)

A fair read/write lock.

| Permit | Held by | While |
|---|---|---|
| shared | every attempt (`first`, `plain`) | the engine call |
| exclusive | a `quiet` attempt | the engine call |

- An attempt never holds a permit while it asks for another (no upgrade).
- Asking for the exclusive permit waits at most `QUIET_WAIT_MAX` = 1,000 ms and never past the deadline. While it
  waits, new shared requests queue behind it (fair), so this cap bounds how long other validations are held up.

## Quiet re-check rate limiter (one per JVM)

A token bucket: capacity 6, refilled at 6 per minute. Fixed constants (no configuration). Tests reset it, and the
gate's state, with `RegexRecheck.resetSharedStateForTests()`.

## RegexRecheck (one per service instance)

Created by `ServiceRegistry` and handed to every `ValidatorSnapshot` through `SnapshotBuilder`.

| Field | Meaning |
|---|---|
| `validationBudgetMs` | `FV_VALIDATION_TIMEOUT_MS` |
| `listener` | reports re-checks and outcomes to this instance's `ServiceMetrics`; a no-op when absent |
| `gate`, `limiter` | the JVM-wide ones |

## Counters (ServiceMetrics)

| Metric | Labels | Counts |
|---|---|---|
| `fv_validation_regex_rechecks_total` | `fhir_version`, `kind` = `plain` \| `quiet` | re-checks started |
| `fv_validation_regex_timeouts_total` | `fhir_version`, `outcome` = `resolved` \| `persistent`, `reason` = `""` \| `input` \| `many-timeouts` \| `quiet-limit` \| `budget` \| `busy` | requests whose first attempt had a regex timeout, by final outcome |
