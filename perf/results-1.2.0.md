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

## Comparison with the validator CLI (SC-006) — PASS

The US2 fixture packages (`TestPackages.multiA()` = `test.multi.a#1.0.0`, a Patient profile requiring `birthDate`;
`TestPackages.multiB()` = `test.multi.b#2.0.0`, a Patient profile requiring `gender`) were written to `.tgz` files
and five Patients validated with both reference CLI releases: 6.9.12 (the core version this service ships) and 7.0.3
(the latest release), downloaded from the `hapifhir/org.hl7.fhir.core` GitHub releases.

```bash
java -jar validator_cli.jar <patient>.json -version 4.0.1 \
  -ig test.multi.a-1.0.0.tgz -ig test.multi.b-2.0.0.tgz -tx n/a -jurisdiction uv -output out.json
```

The service (an image built from commit `eca8fa3` of this branch, with PostgreSQL 16.8) stored both packages with
`POST /igs/upload`, included them with `{"igPackages":[A#1.0.0, B#2.0.0], "terminology":{"mode":"none"}}`, and
validated each Patient three times: by the key `ig=A#1.0.0|B#2.0.0`, the reversed pair and the first-IG alias
`ig=A#1.0.0`. Every request was served by the two-IG context (`X-Validation-Context`).

The projection compared is the sorted set of (severity, location, message text) without `information` issues, plus
`valid` against "the CLI reported no error".

| Patient | CLI errors | 6.9.12 = 7.0.3 | key | reversed pair | first-IG alias |
|---------|-----------:|:--------------:|:---:|:-------------:|:--------------:|
| claims A and B, no `birthDate`, no `gender` | 2 | yes | match | match | match |
| claims A and B, no `gender` | 1 | yes | match | match | match |
| claims A and B, both present | 0 | yes | match | match | match |
| claims A only, no `birthDate` | 1 | yes | match | match | match |
| claims B only, no `gender` | 1 | yes | match | match | match |

Each run also has the `dom-6` best-practice warning, identical on both sides. Terminology was off on both sides, so
the comparison covers profile and structure rules, not code validation.

## Two-IG context: latency, build time and memory (T050) — PASS

DK Core 3.4.0 on its own against DK Core 3.4.0 with IPS 1.1.0 (`hl7.fhir.uv.ips#1.1.0`) as one two-IG context, on
the image built from this branch. Both IGs were fetched from the package registry with their dependencies, so the
contexts are production-sized: 7 packages for DK Core alone, 9 for the pair. The runs alternated
(single, pair, single, pair), with the same settings as above; requests named `ig=hl7.fhir.dk.core#3.4.0` or
`ig=hl7.fhir.dk.core#3.4.0|hl7.fhir.uv.ips#1.1.0` and validated the DK Core Patient sample.

| Run | Context | Packages | Build (ms) | Requests/s | Median (ms) | p95 (ms) | Failed |
|-----|---------|---------:|-----------:|-----------:|------------:|---------:|-------:|
| 1 | DK Core | 7 | 3875 | 71.7 | 104.7 | 134.5 | 0 |
| 1 | DK Core + IPS | 9 | 3604 | 74.8 | 102.8 | 122.2 | 0 |
| 2 | DK Core | 7 | 3412 | 75.9 | 101.1 | 120.1 | 0 |
| 2 | DK Core + IPS | 9 | 3904 | 66.7 | 104.7 | 129.7 | 0 |

- **Latency**: validation against the two-IG context stays below the 200 ms p95 target and within the spread of the
  single-IG runs. Run 2 of the pair had one slow request (5.6 s maximum) that lowered its requests/s; its median and
  p95 are in line with the others.
- **Build time**: 3.6–3.9 s for the pair against 3.4–3.9 s for DK Core alone, once per instance at include time.
- **Memory**: the service's heap estimate for the R4 package sets (`fv_packages_shared_retained_estimate_bytes`, the
  figure the 413 admission uses) is 415 MB for DK Core alone and 460 MB for the pair: IPS and its two extra packages
  add about 44 MB. The container's memory grew by 0.5–1.1 GB over the include in every run. That figure includes heap
  the JVM reserves and hasn't collected, so it doesn't measure the context.
- **FR-006a on real packages**: DK Core needs `hl7.terminology.r4#6.2.0` and IPS `#5.0.0`. The pair was included with
  6.2.0 and the warning "Package hl7.terminology.r4 is required in versions 6.2.0 (hl7.fhir.dk.core#3.4.0) and 5.0.0
  (hl7.fhir.uv.ips#1.1.0); using 6.2.0".

Reproduce (each run needs network access to the package registry):

```bash
R='hl7.fhir.dk.core#3.4.0 hl7.fhir.uv.ips#1.1.0'
IG='hl7.fhir.dk.core#3.4.0' IG_REGISTER="$R" perf/compare-run.sh single <branch image> db target/perf
IG='hl7.fhir.dk.core#3.4.0' IG2='hl7.fhir.uv.ips#1.1.0' IG_REGISTER="$R" perf/compare-run.sh pair <branch image> db target/perf
```
