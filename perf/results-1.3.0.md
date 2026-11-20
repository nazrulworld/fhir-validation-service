# Spec 013 results (1.3.0)

No spurious regex timeout errors under load. Constitution Principle VI: every validation now runs the engine call
under a shared permit and scans its result for regex-timeout issues, so validation without a regex timeout is compared
with 1.2.0. The re-check itself is checked by tests (see "Correctness").

Machine: the development laptop (Apple silicon, 12 cores, macOS), Docker. Each k6 run uses `perf/compare-run.sh`: a
fresh PostgreSQL 16.8 container and a service container limited to 4 CPUs and 4 GB, `FV_VALIDATION_WORKERS=4`, usage
sink `db`, `grafana/k6:0.54.0` with 8 virtual users, a 15 s warm-up and a 60 s measured run of `perf/validate.js`.
"1.2.0" is the published image `ghcr.io/nazrulworld/fhir-validation-service:1.2.0`; "1.3.0" is an image built from
the `013-regex-timeout-under-load` branch.

## Validation without a regex timeout against 1.2.0 (SC-004) — PASS (no regression)

| Run | Build | `ig` | Requests/s | Median (ms) | p95 (ms) | Failed |
|-----|-------|------|-----------:|------------:|---------:|-------:|
| base | 1.2.0 | none | 221.4 | 34.6 | 43.5 | 0 |
| base | 1.3.0 | none | 221.7 | 34.6 | 43.5 | 0 |
| IG | 1.2.0 | `hl7.fhir.dk.core#3.4.0` | 74.1 | 102.3 | 124.0 | 0 |
| IG | 1.3.0 | `hl7.fhir.dk.core#3.4.0` | 76.5 | 101.1 | 118.7 | 0 |

The differences are within run-to-run spread, and p95 stays below the 200 ms target. The IG runs fetched DK Core 3.4.0
with its dependencies from the package registry (`IG_REGISTER`). No regex timeout happened in any run: the
`fv_validation_regex_*` counters were never incremented, so they do not appear in `/metrics` yet (Micrometer registers a
counter on its first increment).

Reproduce:

```bash
perf/compare-run.sh base-120 ghcr.io/nazrulworld/fhir-validation-service:1.2.0 db target/perf
perf/compare-run.sh base-130 <branch image> db target/perf
IG='hl7.fhir.dk.core#3.4.0' IG_REGISTER='hl7.fhir.dk.core#3.4.0' perf/compare-run.sh ig-120 ghcr.io/nazrulworld/fhir-validation-service:1.2.0 db target/perf
IG='hl7.fhir.dk.core#3.4.0' IG_REGISTER='hl7.fhir.dk.core#3.4.0' perf/compare-run.sh ig-130 <branch image> db target/perf
```

## Same verdict under sustained load (SC-001) — PASS, with a caveat

`FV_LOAD_ROUNDS=75 ./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
-Dit.test='SharedDefinitionsDifferentialIT#theSameVerdictUnderSustainedLoad'` (the release gate runs under failsafe since
#94, in its own JVM with a fixed 3 GB heap) validates the
release-gate corpus 75 times in random order on 8 threads and compares every result with the golden file recorded from
1.0.0 (the result each resource gives alone):

| Where | Validations | Time | Differences | Re-checks needed |
|-------|------------:|-----:|------------:|-----------------:|
| laptop, 12 cores | 26,475 | 86 s | 0 | 0 |
| Docker, `--cpus 2`, 4.5 GB heap | 26,475 | 380 s | 0 | 0 |

**Caveat**: neither machine produced a single load-induced regex timeout, so these runs show that nothing differs
under load, but they did not exercise the re-check. The timeouts seen on CI (PR #87) came from GitHub's runners. The
re-check path is covered by:

- `RegexRecheckTest` (11 cases): plain and quiet re-checks, the answer being one attempt's result object, at most three
  attempts, and the safeguards: more than 3 timeouts, the 6-per-minute quiet limit, the validation budget, the request
  deadline, and the 1-second exclusive wait (`busy`) with the validations queued behind it continuing.
- `ValidatorSnapshotRecheckTest` (6 cases): the re-check inside a real `ValidatorSnapshot`, with timeouts injected by
  a validator module; one terminology scope for all attempts; a Bundle re-checked whole; counters through
  `ServiceMetrics`.
- `RegexTimeoutIT` (6 cases): a real runaway regex (`matches('^(.*?,){20}P$')`, about 0.5 s per value) through the
  validation service: answered with the timeout as an error after 3 attempts in bounded time, 2 attempts for more than 3
  timeouts, never 503, normal requests never held up, a request needing a quiet re-check behind a 15 s hostile
  validation answered within about 2 s (`busy`), the counters, the log line and one usage record.
- `SharedDefinitionsDifferentialIT` (release gate) without any test-side retry for regex timeouts (SC-002); CI is the
  place where it meets real timeouts.
