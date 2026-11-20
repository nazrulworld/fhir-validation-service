# Quickstart: No Spurious Regex Timeout Errors Under Load (spec 013)

Validation scenarios that prove the feature end to end. Contract: [contracts/http-api.md](./contracts/http-api.md);
decision rules: [data-model.md](./data-model.md).

## Prerequisites

- Docker (Testcontainers PostgreSQL) and the locked test packages (`sh scripts/fetch-test-packages.sh`).
- A service built from this branch (`./mvnw -B package`, or `docker build -t fv:013 .`).

## 1. Detection and decision rules (unit)

```bash
./mvnw -B test -Dtest='RegexRecheckTest,RegexTimeoutIssueTest'
```

Expected: a result with one regex-timeout issue gets a plain re-check; a second timeout gets a quiet re-check; more than
3 timeouts, an empty quiet-token bucket, a deadline too close (request or validation budget) or an exclusive permit
not granted within 1 s answer with the last result and the right `reason` (`many-timeouts`, `quiet-limit`, `budget`,
`busy`); a result without a timeout is returned after one attempt; both message ids are recognised, and a regex
exception that is not a timeout is not.

## 2. A real runaway regex is still reported (User Story 2, SC-003)

```bash
./mvnw -B test -Dtest='RegexTimeoutIT'
```

Expected: a resource whose value makes a profile's regex backtrack catastrophically is answered 200 with the regex-
timeout issue as an error after 3 attempts, within 3 × its single-validation time; `fv_validation_regex_timeouts_total
{outcome="persistent",reason="input"}` increases by 1; one WARN line names the context key and no resource content.
With a long hostile validation in flight, a request needing a quiet re-check gives up within about 1 s (reason
`busy`) and normal requests keep being answered.

## 3. The release gate needs no retry (SC-002)

```bash
./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='SharedDefinitionsDifferentialIT'
```

Expected: passes with `RegexTimeouts.retry` removed from its sequential, terminology and concurrent phases.

## 4. Same verdict under full load (SC-001)

```bash
FV_LOAD_ROUNDS=75 ./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='SharedDefinitionsDifferentialIT#theSameVerdictUnderSustainedLoad'
```

Expected: the golden corpus (about 134 resources) validated 75 times on 8 threads (at least 10,000 validations), every
result equal to the same resource's result validated alone; the test prints how many plain and quiet re-checks were
needed. Run it on a 4-CPU machine; without `FV_LOAD_ROUNDS` it is skipped.

## 5. No regression on the common path (SC-004, Principle VI)

```bash
perf/compare-run.sh base-120 ghcr.io/nazrulworld/fhir-validation-service:1.2.0 db target/perf
perf/compare-run.sh base-013 fv:013 db target/perf
IG='hl7.fhir.dk.core#3.4.0' IG_REGISTER='hl7.fhir.dk.core#3.4.0' perf/compare-run.sh ig-013 fv:013 db target/perf
```

Expected: p95 within 5 % of 1.2.0, 0 failed requests; `fv_validation_regex_rechecks_total` stays 0 or near 0 at this
load. Results go to `perf/results-1.3.0.md`.

## 6. Full build

```bash
./mvnw -B verify
```

Expected: every test passes, no blocked-thread warnings.
