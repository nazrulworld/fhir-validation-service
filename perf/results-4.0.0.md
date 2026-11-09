# 4.0.0 release checks (spec 007, tasks T071 and T077)

Run on 2026-11-09 on a 12-core Apple Silicon machine (Docker 29.5.2). Each service instance ran in a container limited to
4 CPUs and 4 GB, with `FV_VALIDATION_WORKERS=4`, against its own fresh PostgreSQL 16.8 container. Load came from the
`grafana/k6:0.54.0` image on the same Docker network.

- **3.2.1:** the published image `ghcr.io/nazrulworld/fhir-validation-service:3.2.1`.
- **4.0.0-dev:** an image built from this checkout (`main` after #67, plus the `ValidatorApi` handler rename).

## T071: hot-path performance (SC-008, constitution VI)

### Base context, `perf/validate.js` (8 VUs, 15 s warm-up, 60 s measured)

Two runs per version, in alternating order:

| Run | Version | Requests/s | p95 | Median | Failed |
|-----|---------|-----------:|----:|-------:|-------:|
| 1 | 3.2.1 | 216.0 | 45.3 ms | 36.4 ms | 0 |
| 2 | 4.0.0-dev | 220.3 | 44.6 ms | 35.6 ms | 0 |
| 3 | 4.0.0-dev | 216.7 | 47.0 ms | 35.8 ms | 0 |
| 4 | 3.2.1 | 217.0 | 46.2 ms | 36.0 ms | 0 |

The difference between 3.2.1 and 4.0.0-dev is within run-to-run noise, and p95 is well under the 200 ms target.
SC-008 holds for mode `none` and the base context.

### Terminology context, `perf/validate-terminology.js`

Setup:
- the test IG `test.tx.ig#1.0.0` (a complete local CodeSystem, a ValueSet with that system plus LOINC, and an
  Observation profile binding `code` to it, required);
- included with `{"terminology": {"mode": "ecosystem"}}`;
- the registry list fetched live from `https://ecosystem.hl7.org/tx-reg` (14 open R4 servers), so LOINC was routed
  to `http://tx.fhir.org/r4`.

The uncached phase was kept small (1 VU, 10 s) so the run never load-tests a public server.

| Phase | Load | Requests | Requests/s | p95 | Median | Max |
|-------|------|---------:|-----------:|----:|-------:|----:|
| cached (same LOINC code) | 8 VUs, 30 s | 11,344 | ~280 | 38.9 ms | 17.9 ms | 78.9 ms |
| uncached (a new code each time) | 1 VU, 10 s | 34 | 0.8 | 309.5 ms | 302.3 ms | 320.4 ms |

0 failed requests.
- **Cached:** a code answered from the code cache adds no measurable latency; p95 stays under 200 ms.
- **Uncached:** each request costs one tx.fhir.org round trip (about 300 ms), well inside `FV_TX_TIMEOUT_MS` (5 s) and
  the 10 s request budget.

A single cold request took 1.34 s, which included the registry `/resolve` lookup and connection set-up. The same code
again took 0.02 s.

**SC-009 (memory)** was not measured from the heap: the runtime image has no `jcmd`. It rests on the code-cache bound
checked by `HeapBudgetTest`: `FV_TX_CACHE_SIZE` × 1 KB, about 5 MB per terminology context at the default.

## T077: comparison with the official validator (SC-001) and the quickstart §2 run

- **Official validator:** `validator_cli.jar` 7.0.5 (Git 17db3661806b, built 2026-11-05), run with
  `-version 4.0.1 -ig test.tx.ig-1.0.0.tgz` (plus `-profile …/tx-observation` for the Observations). Its default
  terminology server is `https://tx.fhir.org`.
- **Service:** 4.0.0-dev, with `test.tx.ig#1.0.0` included in mode `ecosystem` (so tx.fhir.org via the registry).
  Called as `POST /R4/validate?ig=test.tx.ig%231.0.0` (plus `profile=` for the Observations).

Code-related errors and fatal issues:

| Fixture | Official validator 7.0.5 | Service 4.0.0-dev |
|---|---|---|
| `observation-loinc-valid.json` (LOINC 8867-4) | none | none |
| `observation-loinc-invalid.json` (LOINC 9999-9) | "Unknown code '9999-9' in the CodeSystem 'http://loinc.org' version '2.83'" and "None of the codings provided are in the value set 'Mixed' …" | the same two errors |
| `condition-snomed-valid.json` (SNOMED CT 38341003) | none | none |
| `condition-snomed-invalid.json` (SNOMED CT 99999999999) | "Unknown code '99999999999' in the CodeSystem 'http://snomed.info/sct' version '…/20250201' (International Edition)" | the same error |

The code errors are identical, so **SC-001 holds** for LOINC and SNOMED CT.

The differences below come from CLI features outside spec 007, not from terminology:
- The CLI reports the error location as `Observation.code.coding[0].code`; the service reports `Observation.code`.
- The CLI adds information messages about display names for the machine's language (`da`).
- For LOINC 8867-4 the CLI also applies the core heart-rate vital-signs profile, so it reports missing
  category/subject/effective[x].

**Quickstart §2, run against the same 4.0.0-dev instance:**
- §2.1: the allowlist was loaded from the registry (`source: registry`, 14 R4 servers).
- §2.2: an unlisted server (`http://10.0.0.5/fhir`) was refused with 400 `tx-server-not-registered`.
- §2.6: `?ig=` naming the stored but not-included IG got 400 "…is not included for R4; include it first (POST
  /R4/validator)".
- §2.3: including with `{"mode":"ecosystem"}` succeeded.
- §2.4: the fixtures above were validated.
- §2.5: `/metrics` showed `fv_tx_calls_total{outcome="success",server="tx.fhir.org"}`,
  `fv_tx_code_cache_hits_total` and `fv_tx_routing_lookups_total{result="hit"|"resolved"}`.
- §2.7: `PATCH /R4/validator` back to `{"mode":"none"}` succeeded.
