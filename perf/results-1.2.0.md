# Spec 012 results (1.2.0)

Two-IG validator contexts. Constitution Principle VI: the validation hot path changed (resolving `ig` now goes through
`IgRequest`/`IgPair`, the first-IG alias lookup and the single-IG filter of FR-012a), so single-IG validation is
compared with 1.1.0.

Machine: the development laptop (Apple silicon, macOS), Docker. Each run uses `perf/compare-run.sh`: a fresh
PostgreSQL 16.8 container and a service container limited to 4 CPUs and 4 GB, `FV_VALIDATION_WORKERS=4`, usage sink
`db`, `grafana/k6:0.54.0` with 8 virtual users, a 15 s warm-up and a 60 s measured run of `perf/validate.js`.
"1.1.0" is the published image `ghcr.io/nazrulworld/fhir-validation-service:1.1.0`; "1.2.0" is an image built from
the `012-multi-ig-validator-contexts` branch. The runs alternated between the two builds, one after the other.

## Single-IG and base validation against 1.1.0 — PASS (no regression)

| Run | Build | `ig` | Requests/s | p95 (ms) | Median (ms) | Failed |
|-----|-------|------|-----------:|---------:|------------:|-------:|
| base 1 | 1.1.0 | none | 227.3 | 42.6 | 33.7 | 0 |
| base 1 | 1.2.0 | none | 227.2 | 42.6 | 33.7 | 0 |
| IG | 1.1.0 | `hl7.fhir.dk.core#3.4.0` | 94.9 | 92.5 | 81.7 | 0 |
| IG | 1.2.0 | `hl7.fhir.dk.core#3.4.0` | 97.2 | 90.7 | 80.3 | 0 |
| base 2 | 1.1.0 | none | 221.4 | 43.9 | 34.6 | 0 |
| base 2 | 1.2.0 | none | 228.7 | 41.9 | 33.5 | 0 |

The differences are within the run-to-run spread (1.1.0 itself moved 2.6 % between its two base runs). Every k6 check
passed in every run, and p95 stays below the 200 ms target. The IG runs uploaded DK Core 3.4.0 without its
dependencies (`IG=hl7.fhir.dk.core#3.4.0 IG_TGZ=src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz`), so they measure
the lookup and validation path, not a production-sized context.

Reproduce:

```bash
perf/compare-run.sh base-110 ghcr.io/nazrulworld/fhir-validation-service:1.1.0 db target/perf
perf/compare-run.sh base-120 <branch image> db target/perf
IG='hl7.fhir.dk.core#3.4.0' IG_TGZ=$PWD/src/test/resources/fhir/hl7.fhir.dk.core-3.4.0.tgz \
  perf/compare-run.sh ig-120 <branch image> db target/perf
```

## Correctness

- `SharedDefinitionsDifferentialIT` (the spec 010 golden corpus, sequential and concurrent) passes unchanged: results
  without a two-IG context are identical to 1.0.0/1.1.0 (spec 012 SC-004).
- Full `mvn verify` on the branch: 936 tests, 0 failures, no blocked-thread warnings.

## Not measured here

- Comparison with `validator_cli.jar -ig A -ig B` (spec 012 SC-006, task T044) needs the validator CLI jar, which was
  not available on this machine.
- Build time and heap of a production-sized two-IG context (for example DK Core with MedCom Core). The heap budget
  admits it with the same rule as any include, and packages shared with already included contexts count for their
  indexes only (spec 010).
