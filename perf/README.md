# Performance checks

Constitution Principle VI: attach these results to any PR that changes the validation hot path.

## Throughput scaling (SC-003: 4 cores ≥ 3× one core, p95 < 200 ms)

Keep the number of virtual users at or below the admission limit (`FV_VALIDATION_MAX_PENDING`, default 4 × workers,
so 4 at 1 CPU and 16 at 4 CPUs). Above it, the extra requests are refused with 503 `overloaded` (FR-010) and the run
measures rejections instead of validations.

```bash
docker compose up -d --wait
# Pin the validator to 1 CPU, then to 4. Restart after each change so the JVM sizes its worker pools for the new
# CPU count, and run a short warm-up before the measured run.
docker update --cpus 1 "$(docker compose ps -q validator)" && docker compose restart validator
k6 run -e VUS=4 perf/validate.js
docker update --cpus 4 "$(docker compose ps -q validator)" && docker compose restart validator
k6 run -e VUS=16 perf/validate.js
```

Record `http_reqs` (requests/s) and `http_req_duration p(95)` for both runs, and check that `http_req_failed` is 0.
Set `FV_VALIDATION_WORKERS` to the CPU count if it is not detected inside the container.

## Responsiveness during IG loads (SC-004)

```bash
# Baseline at the same load (the IG script uses 8 VUs for 90 s by default).
k6 run -e VUS=8 -e DURATION=90s perf/validate.js
k6 run -e ADMIN_KEY="$FV_ADMIN_API_KEY" perf/validate-during-ig-load.js
```

Pass criteria: probe max < 1 s, and validation p(95) within 20% of the baseline run.

## Without a local k6

Run the pinned k6 image on the compose network instead (the repository is mounted so the scripts find their fixtures):

```bash
docker run --rm --network fhir_default -v "$PWD":/work:ro grafana/k6:0.54.0 \
  run -e BASE=http://validator:8880 -e VUS=4 /work/perf/validate.js
```
