# Performance results (1.0.0)

The load-test and measurement results behind 1.0.0. Constitution Principle VI asks for a benchmark with every change
to the validation path; each section below is one of those measurements, taken while the feature was developed (spec
numbers in brackets). Older development builds are named by what they lacked, for example "without rate limits".

**Machine**: the development laptop (Apple silicon, 12 cores, macOS), Docker. Unless a section says otherwise, a run
uses [`perf/compare-run.sh`](compare-run.sh):

- a fresh PostgreSQL 16.8 container and a service container limited to 4 CPUs and 4 GB, `FV_VALIDATION_WORKERS=4`,
  usage sink `db`;
- `grafana/k6:0.54.0` with 8 virtual users, a 15 s warm-up, then a 60 s measured run of
  [`perf/validate.js`](validate.js) (one Patient, `POST /R4/validate`);
- every k6 check passed and no request failed in any run reported here.

## Summary

| What | Result |
|---|---|
| Single-resource validation, base context, 8 clients | about 230 requests/s, median 34 ms, p95 41–44 ms |
| Same, 20 concurrent clients | 237 requests/s, median 83 ms, p95 97.5 ms (queued, none refused) |
| Validation against DK Core 3.4.0 (with dependencies) | 74–76 requests/s, p95 119–124 ms |
| Validation against a two-IG context (DK Core + IPS) | 67–75 requests/s, p95 122–130 ms |
| Rate-limit check | ≤ 1 ms on p95 (budget 5 ms) |
| A refusal (429) | about 0.1 ms |
| Second context sharing 10 of 12 packages | 16 MB of heap and a 0.2 s build (400 MB and 2 s unshared) |
| Results under sustained load | identical to each resource validated alone (26,475 validations, 0 differences) |

The p95 target for single-resource validation is 200 ms.

## 20 concurrent validations — PASS

20 virtual users, each sending its next request as soon as the last one is answered (no think time), against 4 CPUs
and 4 workers. The default admission limit (`FV_VALIDATION_MAX_PENDING` = 4 × workers = 16) would refuse the extra
requests with 503 `overloaded`, so this run raises it to 32 (`MAX_PENDING=32`): requests beyond the 4 workers wait in
the queue instead. An 8-VU run in the same session is the reference. Caps at 1,000,000/min (`THROTTLE_CAP`),
2026-11-24.

| Run | VUs | Requests/s | Median (ms) | p90 (ms) | p95 (ms) | Max (ms) | Failed |
|-----|----:|-----------:|------------:|---------:|---------:|---------:|-------:|
| reference | 8 | 234.7 | 33.6 | 39.6 | 41.5 | 66.7 | 0 |
| 20 concurrent | 20 | 237.0 | 82.9 | 93.9 | 97.5 | 150.6 | 0 |

- All 14 241 requests got 200: no 503s, no 429s (`fv_throttle_refused_total` 0). Every request, warm-up included, went
  through the rate-limit check (`fv_throttle_checked_total` 17 047) and has a `validation_usage` row (17 047).
- Throughput stays the same: 4 workers are already busy at 8 VUs, so more clients don't add capacity; they wait in the
  queue. Latency grows with the queue: median 33.6 → 82.9 ms (2.5×), about what 20 clients sharing 4 workers predicts
  (20 / 8 × 33.6 ≈ 84 ms).
- To serve 20 concurrent clients faster, add CPUs and workers or instances, not a higher admission limit. A higher
  limit only lengthens the queue.

## Rate limits (spec 014)

Every validation request of a key outside the system organisation passes the rate-limit check: two window lookups,
two short locks and three headers.

### Cost of the check — PASS

The same build with rate limits off and on (caps at 1,000,000/min, so nothing is refused), in the same session. Three
sessions, as the check's request path changed during development (the first build, after the compact window and
changed-window queue, after the code-review fixes):

| Session | Without rate limits: p95 (ms) | With the check: p95 (ms) | Difference | Requests/s with the check |
|---|---:|---:|---:|---:|
| first build | 44.0 | 44.3 | +0.3 | 224.5 |
| compact window | 40.6 | 41.5 | +0.9 | 235.6 |
| review fixes | 50.5 | 46.2 | −4.3 (noise) | 221.4 |

The budget is +5 ms on p95. Every request went through the check (`fv_throttle_checked_total` equals the request
count) and none was refused. The baseline of the last session was high next to the others (40.6–44.0 ms), so read
its negative difference as noise, not a speed-up.

Memory per tracked key or organisation, worst case (this instance's and the peers' counts, cache entry included):
545 bytes (`ThrottleFootprintTest`), so about 55 MB at the default `FV_THROTTLE_MAX_TRACKED` of 100 000.

### A key at its cap, and another organisation alongside it — PASS

The trial key is limited to 600 a minute and sent as fast as 8 virtual users allow. At the same time a key of
another organisation (caps far above its rate) validates with 1 virtual user, compared with that key alone
([`perf/atcap-014.sh`](atcap-014.sh)).

| Run | Key | Requests/s | Median (ms) | p95 (ms) | 200 | 429 |
|-----|-----|-----------:|------------:|---------:|----:|----:|
| alone | other organisation, 1 VU | 67.8 | 14.3 | 17.2 | 4 066 | 0 |
| during | other organisation, 1 VU | 54.4 | 17.6 | 21.6 | 2 995 | 0 |
| at cap | trial key, 8 VUs | 44 483.8 | 0.1 | 0.2 | 600 | 2 668 473 |

- The trial key got exactly its cap: 600 validations in the minute, and `validation_usage` holds exactly 600 rows for
  it, all 200. The 2.67 million refusals wrote no usage row. `fv_throttle_refused_total{scope="key",reason="cap"}` =
  2 668 473.
- A refusal costs about 0.1 ms (p95 0.2 ms): it is answered before the body is parsed or validated.
- The other organisation's key got no 429, and its p95 rose by 4.4 ms (17.2 → 21.6 ms), within the 5 ms bound. The
  increase comes from the refusal flood itself (44 000 requests a second on the same 4 CPUs), not from validation
  work; a real client backing off on `Retry-After` sends far fewer. This is the worst case for the bystander.

## Regex re-checks under load (spec 013)

Every validation runs the engine call under a shared permit and scans its result for regex-timeout issues.

### Cost without a regex timeout — PASS

The same build without and with the re-check, alternating. The IG runs fetched DK Core 3.4.0 with its dependencies
from the package registry (`IG_REGISTER`).

| Run | `ig` | Without re-check: req/s, p95 (ms) | With re-check: req/s, p95 (ms) |
|-----|------|---:|---:|
| base | none | 221.4, 43.5 | 221.7, 43.5 |
| IG | `hl7.fhir.dk.core#3.4.0` | 74.1, 124.0 | 76.5, 118.7 |

No regex timeout happened in any run: the `fv_validation_regex_*` counters were never incremented (Micrometer
registers a counter on its first increment, so they don't appear in `/metrics` until then).

### Same verdict under sustained load — PASS, with a caveat

`FV_LOAD_ROUNDS=75 ./mvnw -B verify -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
-Dit.test='SharedDefinitionsDifferentialIT#theSameVerdictUnderSustainedLoad'` (the release gate, under failsafe in its
own JVM with a fixed 3 GB heap) validates the release-gate corpus 75 times in random order on 8 threads and compares
every result with the golden file (the result each resource gives alone):

| Where | Validations | Time | Differences | Re-checks needed |
|-------|------------:|-----:|------------:|-----------------:|
| laptop, 12 cores | 26,475 | 86 s | 0 | 0 |
| Docker, `--cpus 2`, 4.5 GB heap | 26,475 | 380 s | 0 | 0 |

**Caveat**: neither machine produced a load-induced regex timeout, so these runs show that nothing differs under load
but did not exercise the re-check. The timeouts seen on CI came from GitHub's runners. The re-check path is covered by
`RegexRecheckTest`, `ValidatorSnapshotRecheckTest`, `RegexTimeoutIT` (a real runaway regex through the validation
service) and the release gate itself, which has no test-side retry for regex timeouts.

## Two-IG validator contexts (spec 012)

### Single-IG and base validation — PASS

Resolving `ig` goes through the two-IG lookup and the first-IG alias. The same runs without and with that lookup,
alternating; the IG runs uploaded DK Core 3.4.0 without its dependencies, so they measure the lookup and validation
path, not a production-sized context.

| Run | `ig` | Without two-IG lookup: req/s, p95 (ms) | With it: req/s, p95 (ms) |
|-----|------|---:|---:|
| base 1 | none | 227.3, 42.6 | 227.2, 42.6 |
| IG | `hl7.fhir.dk.core#3.4.0` | 94.9, 92.5 | 97.2, 90.7 |
| base 2 | none | 221.4, 43.9 | 228.7, 41.9 |

The differences are within the run-to-run spread.

### Latency, build time and memory of a two-IG context — PASS

DK Core 3.4.0 alone against DK Core 3.4.0 with IPS 1.1.0 (`hl7.fhir.uv.ips#1.1.0`) as one two-IG context. Both IGs
were fetched from the package registry with their dependencies (7 packages for DK Core alone, 9 for the pair); the
runs alternated, and requests validated the DK Core Patient sample.

| Run | Context | Packages | Build (ms) | Requests/s | Median (ms) | p95 (ms) | Failed |
|-----|---------|---------:|-----------:|-----------:|------------:|---------:|-------:|
| 1 | DK Core | 7 | 3875 | 71.7 | 104.7 | 134.5 | 0 |
| 1 | DK Core + IPS | 9 | 3604 | 74.8 | 102.8 | 122.2 | 0 |
| 2 | DK Core | 7 | 3412 | 75.9 | 101.1 | 120.1 | 0 |
| 2 | DK Core + IPS | 9 | 3904 | 66.7 | 104.7 | 129.7 | 0 |

- **Latency**: the two-IG context stays below the 200 ms p95 target and within the spread of the single-IG runs. Run
  2 of the pair had one slow request (5.6 s maximum) that lowered its requests/s; its median and p95 are in line.
- **Build time**: 3.6–3.9 s for the pair against 3.4–3.9 s for DK Core alone, once per instance at include time.
- **Memory**: the heap estimate for the R4 package sets (`fv_packages_shared_retained_estimate_bytes`, the figure the
  413 admission uses) is 415 MB for DK Core alone and 460 MB for the pair: IPS and its two extra packages add about
  44 MB.
- **Dependency versions**: DK Core needs `hl7.terminology.r4#6.2.0` and IPS `#5.0.0`. The pair was included with
  6.2.0 and the warning "Package hl7.terminology.r4 is required in versions 6.2.0 (hl7.fhir.dk.core#3.4.0) and 5.0.0
  (hl7.fhir.uv.ips#1.1.0); using 6.2.0".

### Comparison with the validator CLI — PASS

Two fixture IGs (`test.multi.a#1.0.0`, a Patient profile requiring `birthDate`; `test.multi.b#2.0.0`, one requiring
`gender`) and five Patients, validated by the HL7 validator CLI 6.9.12 (the core version this service ships) and
7.0.3 with `-ig a -ig b -tx n/a`, and by the service through one two-IG context, by its key, the reversed pair and the
first-IG alias. Compared: the sorted (severity, location, message) issues without `information`, and `valid`.

| Patient | CLI errors | 6.9.12 = 7.0.3 | key | reversed pair | first-IG alias |
|---------|-----------:|:--------------:|:---:|:-------------:|:--------------:|
| claims A and B, no `birthDate`, no `gender` | 2 | yes | match | match | match |
| claims A and B, no `gender` | 1 | yes | match | match | match |
| claims A and B, both present | 0 | yes | match | match | match |
| claims A only, no `birthDate` | 1 | yes | match | match | match |
| claims B only, no `gender` | 1 | yes | match | match | match |

Terminology was off on both sides, so the comparison covers profile and structure rules, not code validation.

## Shared package definitions (spec 010)

JDK 17.0.21 (Temurin), `-Xms3g -Xmx3g -XX:+UseG1GC`, PostgreSQL 16 in Docker; the jar run directly, not in a container.

### Results identical with shared definitions — PASS

`SharedDefinitionsDifferentialIT` validates the corpus (`src/test/resources/corpus/010`: 84 examples of DK Core 3.5.0
and MedCom Core 4.0.0, 268 invalid variants, 1 duplicate-canonical case) in three R4 contexts built on one instance's
shared definitions, sequentially and with 8 threads × 3 rounds in random order. Every OperationOutcome equals the
golden file recorded from a build without sharing (`scripts/golden-010.sh`), per issue and in order. 13 packages back
33 package uses.

### Memory and build time of a second context — PASS

[`perf/measure-010.sh`](measure-010.sh): store the 12 locked packages, include DK Core 3.5.0, take the live heap after
a full collection, include MedCom Core 4.0.0 (which shares 10 of its 12 packages with DK Core), take it again.

| Run | Memory held by the second context | First build | Second build |
|---|---|---|---|
| without sharing, run 1 | 406.9 MB | 2990 ms | 2037 ms |
| without sharing, run 2 | 400.5 MB | 2762 ms | 1819 ms |
| shared, run 1 | 16.0 MB | 2417 ms | 203 ms |
| shared, run 2 | 16.0 MB | 2360 ms | 197 ms |

Memory of the second context −96 %, its build time −90 %.

### Heap factors (used by `HeapBudget`)

- Packages of MedCom Core: 2.0 MB unpacked of its own, 110.3 MB unpacked shared with DK Core.
- Without sharing, the context held 404 MB for 112.3 MB unpacked: **3.6 bytes of heap per unpacked byte**. This is
  `HeapBudget.SHARED_RETAINED_PER_EXPANDED_BYTE`, used only by the `fv_packages_shared_retained_estimate_bytes` gauge.
- Shared, it held 16 MB: about 7 MB for its own two packages (2 MB × 3.6) and about 9 MB for its own indexes, chain
  caches and private copies over the 110 MB it shares, i.e. **≈ 0.08 bytes per shared unpacked byte**.
  `HeapBudget.SHARED_HEAP_PER_EXPANDED_BYTE` stays at **1** (never below the measurement), which is conservative.

### Hot-path latency — PASS

[`perf/latency-010.sh`](latency-010.sh): one jar against PostgreSQL, DK Core 3.5.0 included, no terminology server;
4 VUs on the base context and on the DK Core context, alternating between builds without and with sharing.

| Context | Without sharing: mean p95 | Shared: mean p95 |
|---|---:|---:|
| base | 20.44 ms | 21.23 ms (+3.9 %) |
| DK Core 3.5.0 | 39.47 ms | 37.86 ms (−4.1 %) |

Means of three runs (60, 60 and 90 s). Both are within 5 %; the run-to-run spread on the base context (19.3–23.2 ms)
is larger than the difference.

### Terminology scenarios

[`perf/quickstart-010.sh`](quickstart-010.sh) against the jar, PostgreSQL 16, the official HL7 terminology registry
and `http://tx.fhir.org/r4`:

| Scenario | Checks | Result |
|---|---|---|
| A setting change does not rebuild | default server reported in `X-Tx-*`; PATCH to `none` used by the next request; no `Built validator` line after the change | PASS |
| Per-request choice | no parameters → context's server; `txMode=none` → offline; unlisted `tx` → 400 `tx-server-not-registered`; `txMode=none&tx=…` → 400; `txMode=fast` → 400; base context `txMode=server` → 400, `txMode=ecosystem` → 200; still no build | PASS |
| Shared memory released | 12 shared entries with both contexts; 0 within 2 s of excluding both | PASS |

## Reproduce

```bash
docker build -t fhir-validation-service:local .
IMAGE=fhir-validation-service:local

# Base validation, and the cost of the rate-limit check (caps high enough that nothing is refused)
THROTTLE_CAP=1000000 perf/compare-run.sh base $IMAGE db target/perf
# 20 concurrent validations, with the 8-VU reference
THROTTLE_CAP=1000000 perf/compare-run.sh vu8 $IMAGE db target/perf
THROTTLE_CAP=1000000 VUS=20 MAX_PENDING=32 perf/compare-run.sh vu20 $IMAGE db target/perf
# A key at its cap and a bystander organisation (two k6 clients at once)
IMAGE=$IMAGE perf/atcap-014.sh

# Against an IG, fetched with its dependencies from the package registry (needs network access)
IG='hl7.fhir.dk.core#3.4.0' IG_REGISTER='hl7.fhir.dk.core#3.4.0' THROTTLE_CAP=1000000 \
  perf/compare-run.sh ig $IMAGE db target/perf
# A two-IG context
R='hl7.fhir.dk.core#3.4.0 hl7.fhir.uv.ips#1.1.0'
IG='hl7.fhir.dk.core#3.4.0' IG2='hl7.fhir.uv.ips#1.1.0' IG_REGISTER="$R" THROTTLE_CAP=1000000 \
  perf/compare-run.sh pair $IMAGE db target/perf

# Shared package definitions, against a jar (run scripts/fetch-test-packages.sh first)
./mvnw -B package -DskipTests
perf/measure-010.sh target/fhir-validation-service-1.0.0.jar shared
perf/latency-010.sh target/fhir-validation-service-1.0.0.jar shared
```
