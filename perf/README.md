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

Pass criteria: probe max < 1 s, and validation p(95) within 20% of the baseline run. Compare the baseline's
`http_req_duration p(95)` with the IG run's `{ name:validate }` line (the IG run's overall figure also counts probes).

By default the script uploads the 1.6 MB US Core fixture, a quick check only. SC-004 names a **20 MB** IG. A published
package of that size is `hl7.fhir.r4.examples#4.0.1` (20.5 MB archive, 180 MiB expanded, 5,311 entries, within the
default `FV_PACKAGE_MAX_*` limits; raise `FV_PACKAGE_MAX_COMPRESSED_MB` / `FV_PACKAGE_MAX_EXPANDED_MB` for anything
larger):

```bash
mkdir -p /tmp/igs && curl -fL -o /tmp/igs/examples.tgz https://packages.fhir.org/hl7.fhir.r4.examples/4.0.1
docker run --rm --network fhir_default -v "$PWD":/work:ro -v /tmp/igs:/igs:ro -e ADMIN_KEY="$FV_ADMIN_API_KEY" \
  grafana/k6:0.54.0 run -e BASE=http://validator:8880 -e PACKAGE=/igs/examples.tgz \
  -e PACKAGE_NAME=hl7.fhir.r4.examples -e PACKAGE_VERSION=4.0.1 /work/perf/validate-during-ig-load.js
```

Heap: this package needs more than the default `FV_MEMORY_LIMIT=2g` (1.5 GB heap). Before T131 the instance ran out
of heap after the rebuild and, since the include was committed, kept running out on every restart; now the include
is refused with 413 `package-too-large` (needs about 1144 MB, 657 MB free) and nothing is committed. Use 3g or more
(sizing rule under `FV_MEMORY_LIMIT` in `specs/001-service-hardening/contracts/configuration.md`). Measured with
`FV_MEMORY_LIMIT=4g` (2026-10-26): validation p95 +14.3% over baseline, probes max 68 ms, 0% failed. Use a separate
compose project (`COMPOSE_PROJECT_NAME=fvperf`) and `docker compose down -v` afterwards, so the test package does not
stay in your configuration.

## Rolling restart under load (SC-008)

```bash
sh scripts/rolling-restart.sh      # or: make rolling-restart
```

It starts the `cluster` profile, runs `perf/validate.js` (k6 in Docker, 8 VUs for 150 s by default; `ROLLING_VUS`,
`ROLLING_DURATION`) through the load balancer, restarts `validator` and then `validator-2`, each only after the
previous one is ready again, and fails if 0.1% or more of the requests failed. Set `COMPOSE_PROJECT_NAME` to run it
on a separate stack. Result on 2026-10-26 (Apple-silicon host, image built from the working tree): 34,113 requests,
0.035% failed (12), p95 60 ms.

The few failures were not analysed request by request. The likely source is validation POSTs that reached an
instance just as it began refusing new requests: nginx does not retry a non-idempotent request on another upstream
once it has sent it (`proxy_next_upstream` without `non_idempotent`).

## Without a local k6

Run the pinned k6 image on the compose network instead (the repository is mounted so the scripts find their fixtures):

```bash
docker run --rm --network fhir_default -v "$PWD":/work:ro grafana/k6:0.54.0 \
  run -e BASE=http://validator:8880 -e VUS=4 /work/perf/validate.js
```
