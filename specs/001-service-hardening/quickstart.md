# Quickstart: Validating the Service Hardening Feature

This guide runs end-to-end checks for each user story once implementation lands. Contracts:
[http-api](./contracts/http-api.md) · [configuration](./contracts/configuration.md) ·
[container](./contracts/container.md).

## Prerequisites

- Docker 24+ with Compose v2, Java 17, `curl`, `jq`. Optionally `k6` for the load scenarios.
- From the repo root: `cp .env.example .env`, then set `PG_PASSWORD` and `FV_ADMIN_API_KEY` (32+
  characters).

```bash
export ADMIN="Authorization: Bearer $(grep ^FV_ADMIN_API_KEY .env | cut -d= -f2)"
FIX=src/test/resources/fhir
```

## 0. Automated gates (run first)

```bash
./mvnw -B verify          # unit, Testcontainers integration and contract tests
make smoke                # container build + compose + validate + graceful stop
```

Expected: both exit 0. `make smoke` prints `SMOKE OK`.

## 1. US6 / D-series: the stack comes up from a clean checkout (SC-010)

```bash
time docker compose up -d --wait
curl -fsS localhost:8880/health/readiness | jq .
docker compose ps --format '{{.Service}} {{.Ports}}'
```

Expected: ready in under 2 minutes. `versions` lists STU3, R4, R4B and R5. Only `127.0.0.1:8880`
is published, and `postgres` shows no host port. `docker compose config` fails with a clear
message if `PG_PASSWORD` is unset.

## 2. US4: ordinary clients get honest answers

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'Accept: */*' -H 'Content-Type: application/json; charset=utf-8' \
     --data @$FIX/resources/dk/Patient.json localhost:8880/R4/validate                     # 200
curl -s -H 'Accept: application/fhir+xml' --data @$FIX/resources/dk/Patient.json \
     -H 'Content-Type: application/fhir+json' localhost:8880/R4/validate | head -c 80      # <OperationOutcome …
curl -s -w '\n%{http_code}\n' --data '{"resourceType":' -H 'Content-Type: application/json' \
     localhost:8880/R4/validate                                                            # 400 problem+json, type invalid-resource
curl -s -w '\n%{http_code}\n' -H "$ADMIN" -H 'Content-Type: application/json' \
     --data '{"name":"no.such.pkg","version":"9.9.9"}' localhost:8880/igs/register          # 404 problem+json, prompt
curl -s -w '\n%{http_code}\n' localhost:8880/R6/validate --data '{}'                       # 400 unsupported-fhir-version
```

Expected: the status codes shown. Error bodies have `type`, `title`, `status` and `correlationId`,
and contain no Java class names.

## 3. US3: administration is protected and ingestion is safe

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8880/R4/include-ig \
     -H 'Content-Type: application/json' --data '{"igPackageId":"hl7.fhir.dk.core"}'        # 401
curl -s -w '\n%{http_code}\n' -H "$ADMIN" -H 'Content-Type: application/json' \
     --data '{"downloadUrl":"http://169.254.169.254/latest/meta-data"}' localhost:8880/igs/register  # 400 package-source-not-allowed
```

Zip-bomb and path-traversal archives are exercised by the `PackageArchiveGuardTest` unit tests
(they generate the archives in the test). Expected: rejected, and heap growth below 10% (SC-007).

## 4. US1: correct and consistent on every instance (SC-001, SC-002)

```bash
docker compose --profile cluster up -d --wait          # 2 validators behind lb on :8881
curl -s -H "$ADMIN" -F "file=@$FIX/hl7.fhir.dk.core-3.4.0.tgz" localhost:8881/igs/upload
curl -s -H "$ADMIN" -H 'Content-Type: application/json' \
     --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' localhost:8881/R4/include-ig | jq .revision
sleep 10
for i in 1 2 3 4; do   # round-robin hits both instances
  curl -s -H 'Content-Type: application/json' --data @$FIX/resources/dk/Patient.json \
       "localhost:8881/R4/validate?profile=http://hl7.dk/fhir/core/StructureDefinition/dk-core-patient" | jq -c '{valid, n:(.messages|length)}'
done
```

Expected: all four responses are identical, which shows both instances applied the IG with no
restart. Repeat with `register-profile` using `$FIX/profiles/dk/StructureDefinition-dk-core-patient.json`
and validate **immediately**, without the `sleep`. The first response must already reflect the
profile on the receiving instance (B1), and within 10 s on the other one.

Version-upgrade regression (B5) and deterministic resolution (B6) are covered by the integration
tests `ValidatorSnapshotSwapIT` and `DependencyResolverTest`.

## 5. US2: throughput and responsiveness (SC-003, SC-004)

```bash
k6 run -e BASE=http://127.0.0.1:8880 -e VUS=8 perf/validate.js              # baseline p95, req/s
docker compose run --rm -e FV_VALIDATION_WORKERS=1 ...                      # repeat with 1 vs 4 CPU (see perf/README.md)
k6 run -e BASE=http://127.0.0.1:8880 perf/validate-during-ig-load.js        # uploads a 20 MB IG mid-run
```

Expected: 4-core throughput at least 3× the 1-core throughput, and p95 under 200 ms on a warm
validator. During the IG upload, p95 rises by no more than 20% and readiness probes answer within
1 s. When flooded beyond `FV_VALIDATION_MAX_PENDING`, the service returns 503 with `Retry-After`.

## 6. US6: operability

```bash
curl -s localhost:8880/metrics | grep -E '^fv_(validations_total|validation_seconds_count|validation_pending)'
docker compose stop postgres; sleep 12; curl -s -o /dev/null -w '%{http_code}\n' localhost:8880/health/readiness   # 503
curl -s -o /dev/null -w '%{http_code}\n' localhost:8880/health/liveness                                            # 200
docker compose start postgres; sleep 12; curl -s -o /dev/null -w '%{http_code}\n' localhost:8880/health/readiness  # 200
docker compose logs validator | grep -ci password                                                                  # 0
```

Graceful stop under load (SC-008): run `perf/validate.js`, then
`docker compose --profile cluster restart validator`. Expected: k6 reports a failure rate below
0.1%, and `validator` logs show `drain started` → `drain complete`.

## Teardown

```bash
docker compose --profile cluster --profile observability down      # keeps volumes
make clean                                                         # also removes volumes (asks first)
```
