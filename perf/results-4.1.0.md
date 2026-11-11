# 4.1.0 release checks (spec 008, task T071)

Run on 2026-11-11 on a 12-core Apple Silicon machine (Docker 29.9.3), with the same method as
[results-4.0.0.md](results-4.0.0.md):
- each service instance ran in a container limited to 4 CPUs and 4 GB, with `FV_VALIDATION_WORKERS=4`, against its own
  fresh PostgreSQL 16.8 container;
- load came from the `grafana/k6:0.54.0` image on the same Docker network.

Versions:
- **4.0.0:** the published image `ghcr.io/nazrulworld/fhir-validation-service:4.0.0`, validating anonymously (no key).
- **4.1.0-dev:** an image built from `008-api-key-hardening` at `a315b23`, validating with the trial validator key
  (`FV_VALIDATOR_API_KEY`), once with `FV_USAGE_SINK=none` and once with `FV_USAGE_SINK=db`.

Each run is reproducible with `perf/compare-run.sh <label> <image> <none|db|-> <out-dir>` (`-` = 4.0.0, no key).

## T071: hot-path performance (SC-007, constitution VI)

### Base context, `perf/validate.js` (8 VUs, 15 s warm-up, 60 s measured)

Six runs, in alternating order:

| Run | Version | Usage sink | Requests/s | p95 | Median | Failed |
|-----|---------|------------|-----------:|----:|-------:|-------:|
| 1 | 4.1.0-dev | `none` | 238.9 | 40.65 ms | 32.88 ms | 0 |
| 2 | 4.1.0-dev | `db` | 236.6 | 40.89 ms | 33.23 ms | 0 |
| 3 | 4.0.0 | – | 244.1 | 40.14 ms | 32.22 ms | 0 |
| 4 | 4.1.0-dev | `db` | 236.5 | 40.72 ms | 33.27 ms | 0 |
| 5 | 4.1.0-dev | `none` | 238.6 | 40.26 ms | 33.07 ms | 0 |
| 6 | 4.0.0 | – | 244.1 | 39.78 ms | 32.31 ms | 0 |

Means of the two runs per configuration:

| Comparison | p95 | Requests/s |
|------------|----:|-----------:|
| (c) `db` − (b) `none`, the usage-recording cost (**SC-007**) | **+0.35 ms** | −0.9 % |
| (b) `none` − (a) 4.0.0, the cost of auth and metering, for information | +0.49 ms | −2.2 % |

**SC-007 holds:** recording usage in the database adds 0.35 ms to p95, under the 1 ms limit, and p95 stays near 41 ms,
well under the 200 ms target. Neither run dropped a usage record.

The second difference is the cost of key-only validation in 4.1.0, a cached key lookup per request plus the
client-address handler, together with the always-on CPU and allocation metering. It is small compared with the
validation itself.

### Usage records (`db` sink)

| Run | Validations (`fv_validations_total`) | `validation_usage` rows | `fv_usage_dropped_total` |
|-----|-------------------------------------:|------------------------:|-------------------------:|
| 2 | 17,079 | 17,079 | 0 |
| 4 | 17,112 | 17,112 | 0 |

The counts include the warm-up. Every validation produced exactly one row, and nothing was dropped (SC-006).

### Per-validation resources (run 1)

From `fv_validation_cpu_seconds` and `fv_validation_allocated_bytes`, over 17,185 validations of the Danish Patient
fixture (outcome `invalid` against the core specification):
- **CPU time:** 11.95 ms per validation on average.
- **Allocated memory:** 13.4 MB per validation on average. This is bytes allocated on the shared heap, not resident
  memory.

## T072: quickstart scenarios §1–§6 by hand

Run on 2026-11-11 against the same 4.1.0-dev image (`a315b23`), with PostgreSQL 16.8 in Docker. Requests came from the
host, so the service saw them from the Docker gateway (`172.20.0.1`).

**§3, upgrade from a real 4.0.0 database.**
- Setup: the published 4.0.0 image ran on a fresh database. With it, the 4.0.0 command line created an `acme`
  organisation, a system key `ci-system` and an `acme` key `acme-ci`. `FV_ADMIN_API_KEY` installed the `bootstrap` key,
  and US Core 7.0.0 was uploaded.
- Then 4.0.0 was stopped and 4.1.0 started on the same database:
  - V10, V11 and V12 were applied (from schema version 9).
  - The system organisation, both system keys and the IG moved to `…-100000000001`. Nothing references the old id.
  - `bootstrap` became `system administrator` (`…-200000000001`, expires 2099-12-30T23:59:59Z).
  - The `trial` organisation and the `trial validator` key were seeded. `acme` and `acme-ci` were unchanged.
  - A second `SYSTEM` organisation is refused by `organisations_system_id_chk`.
  - The `acme-ci` key created under 4.0.0 still validates (200).

| § | Check | Result |
|---|-------|--------|
| 1 | `FV_AUTH_ANONYMOUS_VALIDATE=true` set at start | WARN "deprecated and ignored" |
| 1 | validate without a key | 401, `WWW-Authenticate: Bearer` |
| 1 | validate with `Bearer x` | 401 |
| 1 | validate with the trial key | 200, OperationOutcome, context `base` |
| 1 | `GET /igs/…/dependencies` without a key / `/health/liveness` | 401 / 200 |
| 2 | trial key on `POST /R4/validator` | 403 "This API key does not allow this operation" |
| 2 | `org list` / `key list` | system, trial, acme; both seeded keys with the 2099 expiry |
| 2 | restart | organisations and keys unchanged (hash of both tables before and after) |
| 4 | `FV_AUTH_ADMIN_CIDRS=10.0.0.0/8`: admin key, wrong key, spoofed `X-Forwarded-For` | 403 "not allowed from this network" for all three |
| 4 | same, validation with the trial key | 200 |
| 4 | `FV_AUTH_ADMIN_CIDRS=172.16.0.0/12` | admin call passes the checks (404 for the unknown IG) |
| 4 | trusted proxies `172.16.0.0/12`, admin `10.0.0.0/8`: XFF `10.1.2.3` | passes (404 for the unknown IG) |
| 4 | same: XFF `10.1.2.3, 198.51.100.9`, or a client `Forwarded: for=10.0.0.5` | 403 |
| 4/5 | trial key used through the trusted proxy with XFF `203.0.113.9` | `last_used_ip` = 203.0.113.9 |
| 5 | `--expires` in the past | exit 2, usage error |
| 5 | key expiring in 40 s: before / after | 200 / 401 |
| 5 | key used 11 s before expiry, used again 0.5 s after (30 s cache entry still live) | 401: the cache never outlives the expiry |
| 6 | `FV_USAGE_SINK=log,db`, one validation | one row: trial key and org, `valid`, total 19 ms ≥ validate 17 ms, CPU 17.6 ms, 12.1 MB allocated |
| 6 | the usage log line | the whole line is one JSON object (20 fields), same values and request id as the row |
| 6 | `usage summary --org trial` | totals and `byOutcome` match the rows |
| 6 | graceful stop (`docker stop`) right after a validation | its record is written (FR-018) |

One validation's usage record was lost when its container was force-removed (`docker rm -f`, SIGKILL) within the
1 s flush interval. That is expected: a hard kill loses at most the last `FV_USAGE_FLUSH_MS` of records, and a
graceful stop writes them (checked above).

`./mvnw verify` on the branch head (`370a01d`): 698 tests, 0 failures, no blocked-thread warnings. Applied migrations
V1–V9 are unchanged; only V10–V12 are new.
