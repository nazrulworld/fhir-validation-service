# Quickstart: Validating spec 007 end to end

What to run to show the feature works. The contracts are in [contracts/http-api.md](./contracts/http-api.md) and the
entities in [data-model.md](./data-model.md).

## Prerequisites

- Docker, for the Testcontainers PostgreSQL.
- JDK 17 and the Maven wrapper.
- For the manual run only: outbound internet access to `ecosystem.hl7.org` and `tx.fhir.org`.
- An ADMIN API key (`$KEY`) and an IG package stored for R4, for example `hl7.fhir.uv.ips#2.0.0`.

## 1. Automated checks

```bash
./mvnw verify
```

Must pass, at least:

| Test | Proves |
|---|---|
| `TxServerAllowlistTest` | Recorded discovery answers: entries with an `error`, a non-`open` `security` or another FHIR version are dropped; matching is exact on the normalised URL; STU3 and R4B are empty; the built-in list is used only while no list exists (FR-006 to FR-012) |
| `RegistryResolverTest` | Recorded `/resolve` answers: the choice order and tie-break (R9); `formatVersion` other than `"1"` rejected; URLs missing from the allowlist skipped; failures kept 5 min, successes 24 h |
| `NetworkGuardTest` | Loopback, private, link-local and rebinding addresses refused; redirects not followed (FR-011) |
| `TerminologyRoutingIT` | Stub registry and stub server: a LOINC code routed and checked; the second request answered from the cache (0 extra hits); a code from a complete IG CodeSystem never sent; the IG's ValueSet carried in `valueSet` and its CodeSystems in `tx-resource` (US1, SC-004) |
| `TerminologyFailureIT` | The stub server down, slow or 5xx: a warning, not an error, 200 within the timeout; the request budget stops calls; degraded answers aren't served from the cache afterwards (FR-019, FR-019a, SC-005, SC-005a) |
| `TerminologySettingIT` | Include and PATCH with terminology: 400 `tx-server-not-registered`, 503 `tx-registry-unavailable`, built-in accepted with no list, the owner and system rule, a rebuild on every instance in the propagation window, the setting shown in health (US3, US4, SC-006) |
| `IncludedOnlyValidationIT` | US5 scenarios 1-6: 400 for not included, no build started, `latest` among included, 404 unchanged, exclude → 400 |
| `ZeroOutboundIT` (the existing SC-002 test, extended) | Mode `none` and the base context: 0 outbound connections (SC-003) |
| `MigrationUpgradeIT` | V9 applies to an empty database and on top of the 3.2.1 schema; existing contexts read as `none` (FR-005) |
| `ConfigLoaderTest` | `FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS` give a deprecation warning, not a failure (FR-025) |

## 2. Manual run against the real registry

```bash
./mvnw -q package -DskipTests && docker compose up -d postgres
java -jar target/fhir-validation-service-*.jar &
```

1. **Allowlist loaded:**
   ```bash
   curl -s localhost:8080/health -H "Authorization: Bearer $KEY" | jq .terminology
   ```
   Expect `R4.source = "registry"`, with `servers` > 0.

2. **Unlisted server refused:**
   ```bash
   curl -s -XPOST localhost:8080/R4/include-ig -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
     -d '{"igPackageId":"hl7.fhir.uv.ips","igPackageVersion":"2.0.0","terminology":{"mode":"server","server":"http://10.0.0.5/fhir"}}'
   ```
   Expect 400 `tx-server-not-registered`.

3. **Include with ecosystem routing:**
   ```bash
   curl -s -XPOST localhost:8080/R4/include-ig -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
     -d '{"igPackageId":"hl7.fhir.uv.ips","igPackageVersion":"2.0.0","terminology":{"mode":"ecosystem"}}' | jq .terminology
   ```
   Expect `{"mode":"ecosystem"}`.

4. **Invalid LOINC code:** validate an Observation with LOINC code `9999-9` against the IG.
   ```bash
   curl -s -XPOST 'localhost:8080/R4/validate?ig=hl7.fhir.uv.ips%232.0.0' -H 'Content-Type: application/fhir+json' \
     -d @src/test/resources/fixtures/r4/observation-bad-loinc.json | jq '.issue[] | select(.severity=="error")'
   ```
   Expect an error naming the unknown LOINC code. Compare it with
   `java -jar validator_cli.jar -version 4.0 -ig hl7.fhir.uv.ips#2.0.0 observation-bad-loinc.json` (SC-001).
   Repeat with `src/test/resources/fixtures/r4/condition-snomed-invalid.json` for SNOMED CT.

5. **Repeat within 10 minutes:** expect the same answer and an unchanged `fv_tx_calls_total{outcome="success"}` in
   `/metrics` (SC-004).

6. **Not-included IG refused:**
   ```bash
   curl -s -XPOST 'localhost:8080/R4/validate?ig=hl7.fhir.uv.ips%231.1.0' ...
   ```
   With 1.1.0 stored but not included, expect 400 `invalid-request` "…is not included for R4…".

7. **Back to offline:**
   ```bash
   curl -s -XPATCH 'localhost:8080/R4/include-ig?ig=hl7.fhir.uv.ips%232.0.0' -H "Authorization: Bearer $KEY" \
     -H 'Content-Type: application/json' -d '{"terminology":{"mode":"none"}}'
   ```
   Then re-validate: the LOINC code is reported as not checked by a server, and `fv_tx_calls_total` doesn't move.

## 3. Performance evidence (constitution VI)

```bash
k6 run perf/validate.js                # mode none: before/after p95 within noise of 3.2.1 (SC-008)
k6 run perf/validate-terminology.js    # stub server, 100 ms latency: cached vs. uncached p95, budget never exceeded
```

Attach both results to the PR.
