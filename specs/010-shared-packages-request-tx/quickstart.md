# Quickstart: validating 1.1.0's shared package definitions and per-request terminology

**Feature**: `010-shared-packages-request-tx` | Contract: [http-api.md](./contracts/http-api.md) | Data:
[data-model.md](./data-model.md) | Research: [research.md](./research.md)

## Prerequisites

- Docker (Testcontainers and a local PostgreSQL), JDK 17, Maven.
- `mvn -q -DskipTests package` builds `target/fhir-validation-service-1.1.0.jar`.
- A PostgreSQL and keys as in the 1.0.0 README (`FV_PG_*`, `FV_ADMIN_API_KEY`, `FV_VALIDATOR_API_KEY`).
- Stored and included on R4: `hl7.fhir.dk.core#3.5.0` and `medcom.fhir.dk.core#4.0.0` (MedCom Core depends on DK
  Core; both use `hl7.terminology.r4#7.0.1` and `hl7.fhir.uv.extensions.r4#5.3.0-ballot-tc1`).
- The locked test packages: `scripts/fetch-test-packages.sh` (reads `src/test/resources/ig/packages/010-packages.lock`,
  downloads once into `${FV_TEST_PACKAGES:-$HOME/.cache/fv-test-packages}` and checks every sha256).
- The 1.0.0 golden files: `src/test/resources/corpus/010/golden/` (made once with `scripts/golden-010.sh` from the
  `v1.0.0` tag and committed).

## Automated

```bash
mvn verify                                              # whole suite; the existing spec 003/004/007 ITs stay green
mvn -Dit.test='SharedDefinitionsDifferentialIT,SharedPackageLifecycleIT' verify          # US3, SC-004, SC-008
mvn -Dit.test='TerminologyNoRebuildIT,TerminologyOverrideIT,TerminologyRoutingIT' verify # US1, US2
mvn -Dtest='EffectiveTerminologyTest,TerminologyOverrideParseTest,ScopedChainLoopTest,TerminologyCodeCacheTest,HeapBudgetTest' test
```

`ScopedChainLoopTest` fails if a HAPI upgrade changes the code-check loop that `ScopedValidationSupportChain` copies
(research R3).

## Manual scenarios

Each scenario lists the spec items it proves. `$V` is the validator key and `$A` the admin key.

### A. A setting change does not rebuild (US1, SC-001)

1. `PATCH /R4/validator` with DK Core's terminology set to `{"mode":"server","server":"https://tx.fhir.org/r4"}`.
2. Validate a resource with a LOINC code against `ig=hl7.fhir.dk.core%233.5.0`. Expect
   `X-Tx-Mode: server`, `X-Tx-Server: https://tx.fhir.org/r4` and `X-Tx-Source: context`.
3. `PATCH` again to `{"mode":"none"}`. Within the propagation window, the same request answers `X-Tx-Mode: none`.
4. Check that the log has **no** `Built validator for` line after step 1, that `/validators` shows the new setting,
   and that `fv_validator_context_builds_total` did not change.

### B. Per-request choice (US2, SC-002, SC-003)

On the context set to `server` A:
- No parameters: `X-Tx-Source: context`, and the call reaches A.
- `?tx=<allowed B>`: the call reaches B, `X-Tx-Source: request`.
- `?txMode=none`: `X-Tx-Mode: none`, and no outbound call (`fv_tx_calls_total` stays unchanged).
- `?tx=https://example.org/fhir`: 400 `tx-server-not-registered`, no call.
- `?txMode=none&tx=https://tx.fhir.org/r4`: 400 `invalid-request`.
- `?txMode=fast`: 400 `invalid-request`.
- On the base context: `?txMode=server` gives 400 (no server anywhere). `?txMode=ecosystem` gives
  `X-Tx-Mode: ecosystem`.

Every request uses the same snapshot (no build line in the log).

### C. Results are the same as 1.0.0 (US3, SC-004)

```bash
mvn -Dit.test=SharedDefinitionsDifferentialIT verify
```

It validates the corpus on both contexts, sequentially and with 8 threads, and compares the results with the golden
files. Every case must match.

### D. Memory and build time (US3, US4, SC-005, SC-006)

1. Start 1.0.0 with the two contexts. After warm-up, run
   `jcmd <pid> GC.run && jcmd <pid> GC.class_histogram > h-100.txt`.
2. Start 1.1.0 the same way and take `h-110.txt`.
3. Compare retained heap (total, and the HAPI model classes). The second context must hold at least 20% less memory
   than on 1.0.0 (gate); the target is 40%.
4. Compare the build times in the `Built validator for … in N ms` lines for the second context (target: 50% less).
5. `GET /metrics`: `fv_packages_shared_entries` counts each common package once, and
   `fv_packages_shared_lookups_total{result="hit"}` is at least 2 after the second build.
6. Record the numbers in `perf/results-1.1.0.md`.

### E. Shared memory is released (SC-008)

Exclude both contexts (`DELETE /R4/validator`), run `jcmd <pid> GC.run`, and check that
`fv_packages_shared_entries{fhir_version="R4"}` drops to the number of packages still in use (0 when only the base
context is left).

### F. Hot-path latency (SC-007)

```bash
perf/compare-run.sh v100 ghcr.io/<owner>/fhir-validation-service:1.0.0 - perf/out   # baseline
perf/compare-run.sh v110 fhir-validation-service:local - perf/out                     # this branch
# no terminology server; p95 of v110 within 5% of v100
```
