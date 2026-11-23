# Spec 014 results (1.4.0)

Rate limits on validation, per API key and per organisation. Constitution Principle VI: every validation request of a
key outside the system organisation now passes the rate-limit check (two window lookups, two short locks, three
headers), so validation is compared with 1.3.0 (SC-002). The refusal path and its effect on other organisations is
measured too (SC-006).

Machine: the development laptop (Apple silicon, 12 cores, macOS), Docker, 2026-11-23. Each run uses a fresh PostgreSQL
16.8 container and a service container limited to 4 CPUs and 4 GB, `FV_VALIDATION_WORKERS=4`, usage sink `db`,
`grafana/k6:0.54.0`, a 15 s warm-up and a 60 s measured run of `perf/validate.js` (single Patient, no `ig`). "1.3.0" is
the published image `ghcr.io/nazrulworld/fhir-validation-service:1.3.0`; "1.4.0" is an image built from the
`014-api-throttling` branch.

## Validation within the caps against 1.3.0 (SC-002) — PASS

| Run | Build | Caps | Requests/s | Median (ms) | p95 (ms) | Failed |
|-----|-------|------|-----------:|------------:|---------:|-------:|
| base | 1.3.0 | none (no rate limits) | 228.1 | 34.3 | 44.0 | 0 |
| caps | 1.4.0 | trial key, organisation and defaults at 1,000,000/min | 224.5 | 34.9 | 44.3 | 0 |

p95 +0.3 ms (budget: 5 ms), within run-to-run spread, and below the 200 ms target. `fv_throttle_checked_total` was
16 097, one per request: every request went through the check, none was refused.

## A key at its cap, and another organisation alongside it (SC-006) — PASS

The trial key is limited to 600 a minute and sent as fast as 8 virtual users allow (no think time). At the same time a
key of another organisation (caps far above its rate) validates with 1 virtual user, compared with that key alone.

| Run | Key | Requests/s | Median (ms) | p95 (ms) | 200 | 429 |
|-----|-----|-----------:|------------:|---------:|----:|----:|
| alone | other organisation, 1 VU | 67.8 | 14.3 | 17.2 | 4 066 | 0 |
| during | other organisation, 1 VU | 54.4 | 17.6 | 21.6 | 2 995 | 0 |
| at cap | trial key, 8 VUs | 44 483.8 | 0.1 | 0.2 | 600 | 2 668 473 |

- The trial key got exactly its cap: 600 validations in the minute, and `validation_usage` holds exactly 600 rows for
  it, all 200: the 2.67 million refusals wrote no usage row (FR-018a). `fv_throttle_refused_total{scope="key",
  reason="cap"}` = 2 668 473.
- A refusal costs about 0.1 ms (p95 0.2 ms): it is answered before the body is parsed or validated.
- The other organisation's key got no 429 and its p95 rose by 4.4 ms (17.2 → 21.6 ms), within the 5 ms bound. The
  increase comes from the refusal flood itself (44 000 requests a second on the same 4 CPUs), not from validation
  work; a real client backing off on `Retry-After` sends far fewer. This is the worst case for the bystander.

## Reproduce

```bash
docker build -t fhir-validation-service:014-api-throttling .
perf/compare-run.sh base-130 ghcr.io/nazrulworld/fhir-validation-service:1.3.0 db target/perf014
THROTTLE_CAP=1000000 perf/compare-run.sh caps-140 fhir-validation-service:014-api-throttling db target/perf014
perf/atcap-014.sh     # the at-cap and bystander runs (two k6 clients at once)
```
