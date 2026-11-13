# 4.2.0 release checks (spec 009, task T056)

Run on 2026-11-13 on a 12-core Apple Silicon machine (Docker 29.9.3).

Method:
- Each service ran as a jar on the host with `-Xmx4g -XX:ActiveProcessorCount=4` and `FV_VALIDATION_WORKERS=4`,
  against its own fresh PostgreSQL 16.8 container.
- Load came from the `grafana/k6:0.54.0` image: `perf/validate.js`, 8 VUs, 15 s warm-up, 60 s measured, with the
  trial validator key and `FV_USAGE_SINK=none`.
- This differs from [results-4.1.0.md](results-4.1.0.md), which used containers limited to 4 CPUs. Only the
  comparisons within this file are meaningful.

Versions:
- **4.1.0:** built from tag `v4.1.0`.
- **4.2.0:** built from `009-fhir-version-acl-toggles` (working tree), with `FV_ENABLED_FHIR_VERSIONS` unset ("all") or
  set to `R4`.

The scripts used are `hostperf.sh` (one load run) and `heap.sh` (one heap measurement). They follow
`perf/compare-run.sh`, but take an optional `FV_ENABLED_FHIR_VERSIONS` value and record time to readiness and heap.

## Load runs (`perf/validate.js`), in alternating order

| Run | Version | `FV_ENABLED_FHIR_VERSIONS` | Requests/s | p95 | Median | Failed | Ready after |
|-----|---------|----------------------------|-----------:|----:|-------:|-------:|------------:|
| 1 | 4.1.0 | – | 273.3 | 34.31 ms | 28.58 ms | 0 | 8.79 s |
| 2 | 4.2.0 | unset | 274.3 | 33.84 ms | 28.54 ms | 0 | 8.79 s |
| 3 | 4.2.0 | `R4` | 273.0 | 33.92 ms | 28.75 ms | 0 | 2.65 s |
| 4 | 4.2.0 | unset | 281.3 | 33.18 ms | 27.54 ms | 0 | 8.79 s |
| 5 | 4.1.0 | – | 279.6 | 33.10 ms | 27.84 ms | 0 | 8.66 s |
| 6 | 4.2.0 | `R4` | 273.7 | 33.67 ms | 28.60 ms | 0 | 2.78 s |

## Heap at rest

Measured after readiness, with no load, after two full GCs (`jcmd GC.run`, `jcmd GC.heap_info`, used heap):

| Run | Version | `FV_ENABLED_FHIR_VERSIONS` | Ready after | Heap used |
|-----|---------|----------------------------|------------:|----------:|
| h1 | 4.1.0 | – | 8.46 s | 878 MB |
| h1 | 4.2.0 | unset | 8.90 s | 878 MB |
| h1 | 4.2.0 | `R4` | 2.52 s | 150 MB |
| h2 | 4.1.0 | – | 8.72 s | 878 MB |
| h2 | 4.2.0 | unset | 8.54 s | 878 MB |
| h2 | 4.2.0 | `R4` | 2.55 s | 151 MB |

## SC-001: no regression with the settings unset

Means of the two runs per configuration, 4.2.0 (unset) compared with 4.1.0:

| Figure | 4.1.0 | 4.2.0 unset | Difference | Limit |
|--------|------:|------------:|-----------:|------:|
| p95 | 33.71 ms | 33.51 ms | −0.6 % | ±5 % |
| Requests/s | 276.5 | 277.8 | +0.5 % | ±5 % |
| Ready after (load runs) | 8.73 s | 8.79 s | +0.7 % | ±5 % |
| Ready after (heap runs) | 8.59 s | 8.72 s | +1.5 % | ±5 % |
| Heap at rest | 878 MB | 878 MB | 0 % | ±5 % |

**SC-001 holds.** Every figure is within 5 % of 4.1.0, which is within run-to-run noise. The full test suite passes
unchanged.

## SC-002: one FHIR version served

4.2.0 with `R4` compared with 4.2.0 unset:

| Figure | All versions | `R4` only | Saved |
|--------|-------------:|----------:|------:|
| Heap at rest | 878 MB | 150.5 MB | **727 MB (−83 %)** |
| Ready after (all four runs) | 8.70 s | 2.63 s | **6.1 s (−70 %)** |

The core packages that are no longer loaded are STU3 (10.5 MB), R4B (14.4 MB) and R5 (17.1 MB): 41.9 MB as stored
archives. The heap saved (727 MB) is far larger than that, because loading a core package also parses it and builds
its base validator.

**SC-002 holds.** The `R4`-only instance becomes ready faster and uses less memory at rest, and the memory saved is at
least the size of the core specifications it no longer loads. Validation latency for R4 doesn't change: p95 is
33.80 ms against 33.51 ms.
