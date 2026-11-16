# Spec 010 results (1.1.0)

Machine: the development laptop (Apple silicon, macOS, Docker 29.9), JDK 17.0.21 (Temurin), `-Xms3g -Xmx3g
-XX:+UseG1GC`, PostgreSQL 16 in Docker. 1.0.0 is the `v1.0.0` tag built from source; "1.1.0" is the
`010-shared-packages-request-tx` branch. Both ran the same scripts, one after the other, on the same machine.

## SC-004: results identical to 1.0.0 (release gate) — PASS

`SharedDefinitionsDifferentialIT` validates the corpus (`src/test/resources/corpus/010`: 84 examples of DK Core 3.5.0
and MedCom Core 4.0.0, 268 invalid variants, 1 duplicate-canonical case) in three R4 contexts built on one
instance's shared definitions, sequentially and with 8 threads × 3 rounds in random order. Every OperationOutcome
equals the golden file recorded from `v1.0.0` by `scripts/golden-010.sh` (per issue and in order: severity, code,
expression, location, diagnostics, details; Java object identities printed in a few HAPI messages are normalised, see
the corpus README). 13 packages back 33 package uses.

## SC-005 / SC-006: the second context — PASS

`perf/measure-010.sh`: store the 12 locked packages, include DK Core 3.5.0, take the live heap after a full collection
(`jcmd GC.run` twice, then `GC.heap_info`), include MedCom Core 4.0.0 (which shares 10 of its 12 packages with DK
Core), take it again. The second build's time is the `Built validator for … in N ms` log line.

| Run | Memory held by the second context | First build | Second build |
|---|---|---|---|
| 1.0.0, run 1 | 406.9 MB | 2990 ms | 2037 ms |
| 1.0.0, run 2 | 400.5 MB | 2762 ms | 1819 ms |
| 1.1.0, run 1 | 16.0 MB | 2417 ms | 203 ms |
| 1.1.0, run 2 | 16.0 MB | 2360 ms | 197 ms |

- Memory of the second context: **−96%** (gate ≥ 20%, target 40%).
- Second build time: **−90%** (target 50%).

### Factors derived from the measurement (research R9)

- Packages of MedCom Core: 2.0 MB unpacked of its own, 110.3 MB unpacked shared with DK Core.
- 1.0.0 held 404 MB for 112.3 MB unpacked: **3.6 bytes of heap per unpacked byte**. This is
  `HeapBudget.SHARED_RETAINED_PER_EXPANDED_BYTE`, used only by the `fv_packages_shared_retained_estimate_bytes` gauge.
- 1.1.0 held 16 MB: about 7 MB for its own two packages (2 MB × 3.6) and about 9 MB for its own indexes, chain caches
  and private copies over the 110 MB it shares, i.e. **≈ 0.08 bytes per shared unpacked byte**.
  `HeapBudget.SHARED_HEAP_PER_EXPANDED_BYTE` stays at **1** (FR-024: never below the measurement), which is
  conservative.

## SC-007: hot-path latency — PASS (mean of three runs)

`perf/latency-010.sh`: one jar against PostgreSQL, DK Core 3.5.0 included, no terminology server; warm-up, then
`perf/validate.js` with the `grafana/k6` image, 4 VUs, on the base context and on the DK Core context. Runs alternated
between the two releases. Every run: 0 failed requests.

| Context | Run | 1.0.0 p95 | 1.1.0 p95 |
|---|---|---|---|
| base | 1 (60 s) | 20.02 ms | 19.29 ms |
| base | 2 (60 s) | 19.83 ms | 23.15 ms |
| base | 3 (90 s) | 21.47 ms | 21.26 ms |
| base | **mean** | **20.44 ms** | **21.23 ms (+3.9%)** |
| DK Core 3.5.0 | 1 (60 s) | 40.97 ms | 37.94 ms |
| DK Core 3.5.0 | 2 (60 s) | 37.85 ms | 37.43 ms |
| DK Core 3.5.0 | 3 (90 s) | 39.60 ms | 38.20 ms |
| DK Core 3.5.0 | **mean** | **39.47 ms** | **37.86 ms (−4.1%)** |

Both means are within 5% of 1.0.0. The run-to-run spread on the base context (19.3–23.2 ms for 1.1.0) is larger than
the difference between releases; a run on CI hardware with longer durations would narrow it.

## Quickstart scenarios (T075)

`perf/quickstart-010.sh` against the 1.1.0 jar, PostgreSQL 16, the official HL7 terminology registry (list fetched at
start, source `registry`) and `http://tx.fhir.org/r4`:

| Scenario | Checks | Result |
|---|---|---|
| A: a setting change does not rebuild | default server reported in `X-Tx-*`; PATCH to `none` used by the next request; no `Built validator` line after the change | PASS |
| B: per-request choice | no parameters → context's server; `txMode=none` → offline; unlisted `tx` → 400 `tx-server-not-registered`; `txMode=none&tx=…` → 400; `txMode=fast` → 400; base context `txMode=server` → 400, `txMode=ecosystem` → 200; still no build | PASS |
| C: results identical to 1.0.0 | `SharedDefinitionsDifferentialIT` (offline and with a terminology server) | PASS |
| D: memory and build time | `perf/measure-010.sh`, above | PASS |
| E: shared memory released | 12 shared entries with both contexts (the second build reused 10 packages); 0 within 2 s of excluding both | PASS |
| F: hot-path latency | `perf/latency-010.sh`, above | PASS |
