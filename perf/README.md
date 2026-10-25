# Performance checks

Constitution Principle VI: attach these results to any PR that changes the validation hot path.

## Throughput scaling (SC-003: 4 cores ≥ 3× one core, p95 < 200 ms)

```bash
docker compose up -d --wait
# Pin the validator to 1 CPU, then to 4, and run the same load each time.
docker update --cpus 1 "$(docker compose ps -q validator)" && k6 run -e VUS=8 perf/validate.js
docker update --cpus 4 "$(docker compose ps -q validator)" && k6 run -e VUS=16 perf/validate.js
```

Record `http_reqs` (requests/s) and `http_req_duration p(95)` for both runs. Set `FV_VALIDATION_WORKERS` to the CPU count if
it is not detected inside the container.

## Responsiveness during IG loads (SC-004)

```bash
k6 run -e ADMIN_KEY="$FV_ADMIN_API_KEY" perf/validate-during-ig-load.js
```

Pass criteria: probe max < 1 s, and validation p(95) within 20% of the plain `validate.js` run.
