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
