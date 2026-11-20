# Contract changes: No Spurious Regex Timeout Errors Under Load (spec 013)

No path, parameter, request body, response body, status code or header changes. The contract changes are
descriptive (FR-010) plus two new metrics.

## 1. `POST /{version}/validate` — description (openapi.yaml)

Append to the operation's `description`:

> Regex checks: the validation engine stops any single regex check after 500 ms. When a result reports such a timeout,
> the service validates the resource again before answering: once at once, and if the timeout is still there, once
> more with no other validation running on the instance (for at most one second; a longer re-check goes on beside
> them). The response is the complete result of the first attempt
> without a regex timeout. When the timeout persists (the input makes the regex run too long, the re-checks would not
> fit in the request's time, or the instance is too busy to run the quiet re-check within one second), the response is
> the last attempt's result as the engine reports it: the timeout stays an issue with the engine's severity, the status
> is 200 and the headers are those of any validation.
> A re-check never changes the status code and is never answered with 503.

Applies to every `ig` form and every terminology setting. `X-Validation-Context`, `X-Tx-*` and the usage record
describe the request once, whatever the number of attempts.

## 2. `GET /metrics` (README "Metrics" row and operations guide)

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `fv_validation_regex_rechecks_total` | counter | `fhir_version`, `kind` (`plain`, `quiet`) | re-checks started because the previous attempt reported a regex timeout |
| `fv_validation_regex_timeouts_total` | counter | `fhir_version`, `outcome` (`resolved`, `persistent`), `reason` (`""`, `input`, `many-timeouts`, `quiet-limit`, `budget`, `busy`) | requests whose first attempt reported a regex timeout, by how they ended |

Reading them (SC-005): `increase(fv_validation_regex_timeouts_total[1h])` by `outcome` gives how many validations
needed a re-check in the last hour and how many still timed out. A rising `quiet` re-check rate means the instance is
short of CPU; persistent timeouts with reason `input` mean a resource or profile with a regex that runs too long.

## 3. Logs

A persistent timeout is logged once per request at WARN:
`Regex timeout persisted for <fhirVersion>:<context key> after <n> attempt(s) (reason <reason>)`.
The request id is on the line (contextual logging). No resource content, value or pattern is logged.

## 4. Unchanged

- The issues reported: always one attempt's complete result (no filtering, merging or downgrading; Constitution III).
- Usage records: one per request (spec 008). `fv_validations_total` and `fv_validation_seconds`: once per request,
  with the total duration.
- `FV_VALIDATION_TIMEOUT_MS`, `FV_HTTP_REQUEST_TIMEOUT_MS`, admission (`FV_VALIDATION_MAX_PENDING`): unchanged meaning;
  both timeouts also bound the re-checks (the request timeout counted from arrival, including admission wait). A
  re-check runs inside the request's admission slot and is never refused with 503 `overloaded`.
