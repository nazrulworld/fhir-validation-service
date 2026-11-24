# Quickstart: validating feature 014

**Feature**: `014-api-throttling`. These scenarios prove the spec's user stories end to end. Contracts:
[http-api.md](./contracts/http-api.md) and [operations.md](./contracts/operations.md). Schema and in-memory model:
[data-model.md](./data-model.md). Design decisions: [research.md](./research.md).

> **Release reset (2026-11-24, PR #106)**: the feature ships in **1.0.0**. In §1, "V18" is the baseline
> `V18__baseline_1_0.sql`, and the upgrade from a 1.3.0 schema is covered by `SquashedBaselineIT`. In §8, the 1.3.0
> image no longer exists: compare an image built from a commit before spec 014 (no rate limits) with the image under
> test at `THROTTLE_CAP=1000000` (checked, nothing refused), and record the results in `perf/results-1.0.0.md`.

## 0. Prerequisites

- Docker (Testcontainers PostgreSQL), JDK 17, Maven.
- `mvn verify` must pass. It covers every scenario below as unit tests or ITs.

Manual run (local PostgreSQL from `compose.yaml`). The service listens on port 8880:

```bash
export FV_ADMIN_API_KEY=adm_0123456789abcdefghijklmnopqrstuv
export FV_VALIDATOR_API_KEY=trial_0123456789abcdefghijklmnopqrstu
mvn -q package -DskipTests && java -jar target/fhir-validation-service-*.jar
CLI="java -jar target/fhir-validation-service-*.jar"
v() { curl -s -o /dev/null -D - -H "Authorization: Bearer $1" -H 'Content-Type: application/fhir+json' \
        -X POST localhost:8880/R4/validate -d '{"resourceType":"Patient"}' | grep -iE '^(HTTP|ratelimit|retry-after)'; }
```

## 1. Initial caps on a fresh database (US3 AC1, AC7; FR-015)

```bash
$CLI limit list
```

Expected:
- `defaults` = `{"key": 600, "organisation": 1200}`;
- the `trial` organisation has `perMinute: 60`, and its `trial validator` key has `perMinute: 30`;
- the `system` organisation and its `system administrator` key show `source: "not-throttled"`.

Restart the service: the values are unchanged.

Tests:
- `ThrottleMigrationIT`: V18 on an empty database and on top of a 1.3.0 schema with data. The trial key is inserted
  after V18 with 30, and a removed cap survives a restart and a key rotation;
- `ConstantMigrationConsistencyTest`: the V18 literals match `Constant`.

## 2. Key cap (US1)

```bash
$CLI limit set --key "trial validator" --per-minute 5
sleep 11                                        # FV_THROTTLE_CAPS_REFRESH_MS + one tick
for i in 1 2 3 4 5 6; do v "$FV_VALIDATOR_API_KEY"; done
```

Expected:
- requests 1–5: `HTTP/1.1 200`, with `RateLimit-Limit: 5` and `RateLimit-Remaining` going 4, 3, 2, 1, 0;
- request 6: `HTTP/1.1 429`, `Retry-After: <s>`, and a problem body with `type: rate-limited`, `scope: key`,
  `limit: 5`;
- after waiting `<s>` seconds, one more request gets 200 (AC3).

Tests:
- `ValidationThrottleIT#keyCap`, `#retryAfterIsSufficient` and `#refusedRequestsDoNotCount`;
- `WindowTest`: ring arithmetic, Reset/Retry-After at bucket edges, and a burst across a minute boundary (spec Edge
  Cases).

## 3. Organisation cap across keys (US2)

```bash
$CLI org create --name-short acme --name-long "Acme" --type COMMERCIAL
$CLI key create --org acme --name acme-a --role VALIDATE    # note the printed key → $A
$CLI key create --org acme --name acme-b --role VALIDATE    # → $B
$CLI limit set --org acme --per-minute 6
$CLI limit set --key acme-a --per-minute 5 && $CLI limit set --key acme-b --per-minute 5
sleep 11
for i in 1 2 3 4; do v "$A"; v "$B"; done
```

Expected:
- exactly 6 requests get 200, and 2 get 429 with `scope: organisation`;
- the 429 body names no other key (AC2).

Tests: `ValidationThrottleIT#organisationCap`, `#keyRefusalDoesNotConsumeOrganisation` (AC3) and
`#defaultOrganisationCapApplies` (AC4).

## 4. Defaults and removal (US3 AC5–AC7)

```bash
$CLI limit remove --key acme-a          # effective → {perMinute: 600, source: default}
$CLI limit set --default-key --per-minute 3
$CLI limit remove --default-org         # organisations without their own cap → not limited at that level
$CLI limit set --key acme-a --per-minute 0                  # exit 2
$CLI limit set --key no-such-key --per-minute 5             # exit 3
```

Tests: `LimitCommandsIT` covers the output shapes, exit codes, audit log line and `service_settings.updated_by`.

## 5. System organisation is never throttled (US5)

```bash
$CLI limit set --default-key --per-minute 1 && $CLI limit set --default-org --per-minute 1
sleep 11
for i in $(seq 20); do v "$FV_ADMIN_API_KEY"; done      # all 200, no RateLimit-* headers
$CLI limit set --org system --per-minute 5                 # exit 4, "never rate limited"
```

Tests: `ValidationThrottleIT#systemOrganisationKeysAreNotThrottled` (ADMIN and VALIDATE keys of the system
organisation), `#adminKeyOfOtherOrganisationIsThrottled` (US5 AC2) and `LimitCommandsIT#systemOrganisationRefused`.

## 6. Access control disabled (US4)

```bash
FV_GLOBAL_DISABLED_ACL_SECURITY=true FV_ACL_MODE_CHANGE_CONFIRMED=true java -jar target/fhir-validation-service-*.jar
for i in $(seq 20); do v anything; done                    # all 200, no RateLimit-* headers
```

Startup logs `Rate limits are not applied: access control is disabled`. Restart with access control enabled again:
`limit list` shows the caps unchanged (AC3).

Tests: `AclDisabledIT#notThrottled` (extended).

## 7. Several instances (SC-001, FR-005) and local mode (FR-019)

**Simulation** (unit test, no database): `ThrottleSimulationTest` covers 1–3 instances, caps {1, 5, 10, 30, 60, 600,
1200} and sticky, round-robin and random balancing. It asserts:
- the admitted count in every rolling 60 s is ≤ cap on 1 instance and ≤ cap + max(1, ⌈cap/10⌉) on 2–3 instances.

**Real database**: `ThrottleMultiInstanceIT` runs two `ThrottleService`s on one Testcontainers PostgreSQL with
`FV_THROTTLE_SYNC_INTERVAL_MS=200`:
- a cap of 30 with alternating requests → at most 33 admitted in 60 s;
- `throttle_instances` shows 2 live rows, and `fv_throttle_peers` is 1 on each instance;
- the PostgreSQL container is paused for 5 s → both instances log the local-mode WARN and `fv_throttle_local_mode`
  is 1. Requests are still admitted up to the cap per instance, and none fails. After unpausing, the INFO line
  appears and the gauge is 0.

**Manual check** (two jars on ports 8880 and 8881, same database):

```bash
psql -c "select instance_name, seen_at from fhir_validator_schema.throttle_instances"   # two rows
curl -s localhost:8880/metrics | grep fv_throttle_peers                                   # 1.0
```

## 8. Performance (SC-002, Principle VI)

```bash
# existing k6 single-resource scenario (perf/validate.js); usage: <label> <image> <sink> <out-dir>
perf/compare-run.sh base   fhir-validation-service:1.3.0 none perf/out/014
THROTTLE_CAP=1000000 perf/compare-run.sh caps   fhir-validation-service:1.4.0 none perf/out/014   # nothing refused
THROTTLE_CAP=600     perf/compare-run.sh at-cap fhir-validation-service:1.4.0 none perf/out/014   # 429 throughput
```

Record the results in `perf/results-1.4.0.md`. The p95 difference must be ≤ 5 ms, and there must be no new
blocked-thread warnings.

## 9. Observability (FR-017, FR-018)

After scenario 2:
- `/metrics` shows `fv_throttle_refused_total{scope="key",reason="cap"} 1.0` and `fv_throttle_checked_total`;
- the log has one `throttle: key … refused 1 request(s)` line carrying the request id;
- sending 100 more refused requests within the minute adds at most one more line.

Tests: `ValidationThrottleIT#refusalLogIsBounded`, which uses `LogCapture`.
