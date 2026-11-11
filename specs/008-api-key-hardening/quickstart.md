# Quickstart: validating feature 008

**Feature**: `008-api-key-hardening`. These scenarios prove the spec's user stories end to end. Contracts:
[http-api.md](./contracts/http-api.md) and [operations.md](./contracts/operations.md). Schema:
[data-model.md](./data-model.md).

## 0. Prerequisites

- Docker (Testcontainers PostgreSQL), JDK 17, Maven.
- `mvn verify` must pass. It covers every scenario below as unit tests or ITs.

Manual run (local PostgreSQL from `compose.yaml`). The service listens on port 8880:

```bash
export FV_ADMIN_API_KEY=adm_0123456789abcdefghijklmnopqrstuv
export FV_VALIDATOR_API_KEY=trial_0123456789abcdefghijklmnopqrstu
export FV_USAGE_SINK=log,db
mvn -q package -DskipTests && java -jar target/fhir-validation-service-*.jar
```

## 1. Validation requires a key (US1, US7)

| Request | Expected |
|---|---|
| `POST /R4/validate` with no `Authorization` | 401, `WWW-Authenticate: Bearer` |
| the same request with `Authorization: Bearer x` (malformed) | 401, no key lookup (unit test: repository not called) |
| the same request with `Bearer $FV_VALIDATOR_API_KEY` | 200 with an OperationOutcome |
| `GET /igs/hl7.fhir.r4.core/4.0.1/dependencies` with no key | 401 |
| start with `FV_AUTH_ANONYMOUS_VALIDATE=true` | WARN "deprecated and ignored"; no-key validate still gets 401 |

Tests: `ApiKeyAuthIT` (updated: the old "anonymous validate → 200" expectations become 401), `ApiKeyAuthHandlerTest`
(malformed token) and `ConfigLoaderTest` (deprecation warning).

## 2. Well-known organisations and the trial key (US2)

```bash
java -jar target/fhir-validation-service-*.jar org list             # system …-100000000001, trial …-100000000002
java -jar target/fhir-validation-service-*.jar key list             # 'system administrator', 'trial validator' (expiresAt 2099-12-30T23:59:59Z)
curl -H "Authorization: Bearer $FV_VALIDATOR_API_KEY" -H 'Content-Type: application/json' \
  -X POST localhost:8880/R4/validator -d '{}'                         # 403
```

Restarting the service leaves both lists unchanged.

Tests: the new `BootstrapOrganisationsIT`:
- the orgs have the `Constant` ids;
- the trial key validates (200) and gets 403 on admin paths;
- restarting is idempotent;
- a clashing org named `trial` → `warmUp()` fails with `StartupException`;
- a malformed `FV_VALIDATOR_API_KEY` → a configuration error naming the variable;
- the safe config dump doesn't contain the key.

## 3. Upgrade from 4.0 (US3)

`MigrationUpgradeIT#v11ReKeysTheSystemOrganisation` (and, by hand, start the published 4.0.0 image on a fresh database,
create data, stop it, then start 4.1.0 on the same database):
1. Flyway migrates to V10 only (`target("10")`).
2. Insert keys, IG packages and snapshots owned by `…-000000000001`, and a `bootstrap` key.
3. Migrate to the latest version.

Expected:
- every owner is `…-100000000001`;
- no row references the old id;
- the column defaults use the new id;
- inserting a second `SYSTEM` org fails;
- the `bootstrap` row is now `system administrator` with id `…-200000000001`.

## 4. Admin networks and trusted proxies (US4)

`ApiKeyAuthIT` with overrides:
- `adminCidrs=10.0.0.0/8`: an admin request from `127.0.0.1` with the admin key gets 403, and the repository isn't
  called.
- `adminCidrs=127.0.0.0/8`: the same request gets 200.
- `trustedProxies=127.0.0.1/32, adminCidrs=10.0.0.0/8`: the header `X-Forwarded-For: 10.1.2.3` gets 200, and
  `X-Forwarded-For: 10.1.2.3, 198.51.100.9` gets 403 (the right-most untrusted address wins).
- `trustedProxies` unset: `X-Forwarded-For: 10.1.2.3` is ignored, and the request gets 403 under
  `adminCidrs=10.0.0.0/8`.
- With trusted proxies, a client-supplied `Forwarded: for=10.0.0.5` is ignored too: only the header named by
  `FV_AUTH_FORWARDED_HEADER` (default `x-forwarded-for`) is read.

Unit tests: `CidrTest` (v4/v6, `/0`, `/32`, `/128`, mapped v4, invalid input) and `ClientAddressTest` (trusted and
untrusted peer, spoofed XFF, only the configured header is read, all hops trusted, an `unknown` entry stops the walk,
an unparseable peer has no address, IPv6 zones).

## 5. Expiry and last-used address (US5)

```bash
java -jar … key create --org system --name short-lived --role VALIDATE --expires 2026-11-11T12:00:05Z
```

- Before the expiry, validate gets 200. After it, validate gets 401, even within `FV_AUTH_CACHE_TTL_MS`.
- `key list` shows `expiresAt` and `lastUsedIp` (the client address the service saw, e.g. a Docker gateway).
- `--expires` in the past is a usage error.

Tests: `ApiKeyAuthIT#expiredKey…` and `#lastUsedIpIsRecorded`, plus an `AdminCliIT` update.

## 6. Usage accounting (US6)

With `FV_USAGE_SINK=db`, validate once with the trial key, then:

```sql
SELECT api_key_id, org_id, outcome, total_ms, validate_ms, cpu_time_ns, allocated_bytes
FROM fhir_validator_schema.validation_usage ORDER BY id DESC LIMIT 1;
-- api_key_id = …-200000000002, org_id = …-100000000002, total_ms >= validate_ms, cpu_time_ns > 0
```

Tests:
- **`UsageIT`**:
  - db sink: one row per request; an unparseable body gives one row with outcome `unparseable`; a timeout gives one
    row with outcome `timeout`;
  - log sink: one JSON line captured by `LogCapture` on `nzi.fhir.validator.usage`;
  - none: no rows, no lines;
  - deleting the key leaves the rows with `api_key_id = NULL`.
- **`UsageMeterTest`**: phase arithmetic, exactly-once emission when the response ends before or after the work, -1
  for unsupported counters.
- **`DbUsageWriterTest`** (Testcontainers): flush on size, flush on time, drop-and-count on overflow, a failed write
  is counted and not retried, `close()` flushes.
- **`LogUsageWriterTest`**: JSON shape.
- **`ObservabilityIT`**: `fv_validation_cpu_seconds`, `fv_validation_allocated_bytes` and `fv_usage_dropped_total`
  are present, and no metric has a `key` or `org` tag.

## 7. Performance (Principle VI, SC-007)

```bash
k6 run perf/validate.js   # (a) 4.0.0 without a key, (b) 4.1.0 FV_USAGE_SINK=none, (c) 4.1.0 FV_USAGE_SINK=db
```

Record p50/p95/p99 in `perf/results-4.1.0.md`. Pass condition: p95(`db`) − p95(`none`) < 1 ms on 4.1.0, and the p95
stays < 200 ms for a single resource on a warm validator. Report (b) − (a), the cost of auth and the client-address
handler, separately for information.

## 8. Existing tests

`AppHarness` gains `VALIDATOR_KEY` (set through `auth.validatorKey`) and a `validatorAuth(request)` helper. Every IT
that validated or read packages anonymously sends that key. `mvn verify` is green.
