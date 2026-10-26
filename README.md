# FHIR Validation Service

A horizontally scalable FHIR resource validation service built with Vert.x, HAPI FHIR and PostgreSQL.

It validates FHIR resources (STU3, R4, R4B, R5) against the base specification, stored Implementation Guide (IG)
packages and custom profiles. Any number of identical instances can run against one PostgreSQL database; changes to
IGs and profiles reach every instance within seconds, without restarts.

- REST API defined in [`src/main/resources/openapi.yaml`](src/main/resources/openapi.yaml)
- Results as a JSON summary or a FHIR `OperationOutcome` (JSON or XML)
- Errors as RFC 9457 `application/problem+json`, with an `X-Request-Id` correlation id on every response
- Liveness, readiness and Prometheus metrics for orchestrators
- Container images for x86_64 and arm64

> Upgrading from 0.1.x? Read [docs/upgrade-1.0.md](docs/upgrade-1.0.md) first: the database is no longer bundled in
> the container, and data must be moved once.

## Requirements

- Docker with Compose v2 (to run), or Java 17 and Maven (to build and develop)
- PostgreSQL 16 (provided by `compose.yaml`)
- About 2 GB of memory per instance (warmed validators for four FHIR versions). Including an IG rebuilds that
  version's validator while the old one keeps serving, so allow headroom of at least twice the largest validator:
  watch `fv_snapshot_package_size_bytes` (archive size; parsed packages take several times more heap)

## Quick start

```bash
cp .env.example .env      # set PG_PASSWORD and FV_ADMIN_API_KEY (32-128 chars of [A-Za-z0-9_-])
make up                   # = docker compose up -d --wait : PostgreSQL + one validator on 127.0.0.1:8880
curl -fsS http://127.0.0.1:8880/health/readiness
```

| Command | Starts | Endpoint |
|---------|--------|----------|
| `make up` | PostgreSQL, one validator | http://127.0.0.1:8880 |
| `make up-cluster` | + a second validator behind nginx (shared database) | http://127.0.0.1:8881 |
| `make up-obs` | + Prometheus and a PostgreSQL exporter | http://127.0.0.1:9090 |
| `docker compose --profile admin up -d` | + pgAdmin (credentials from `.env`) | http://127.0.0.1:5050 |
| `make down` / `make clean` | stop (keep / delete volumes) | |
| `make smoke` | build, start, validate, graceful stop | |

All ports bind to `127.0.0.1`. PostgreSQL is not published; to reach it from the host, add a git-ignored
`compose.override.yaml` with `services: {postgres: {ports: ["127.0.0.1:54329:5432"]}}`.

## Using the API

### Validate a resource

```bash
curl -X POST http://127.0.0.1:8880/R4/validate \
  -H 'Content-Type: application/fhir+json' \
  --data '{"resourceType":"Patient","active":true}'
```

```json
{"valid": true, "messages": [], "fhirVersion": "R4", "appliedProfiles": []}
```

- `Accept: application/fhir+json` or `application/fhir+xml` returns a FHIR `OperationOutcome`.
- `?profile=<canonical>[,<canonical>]` validates against additional profiles.
- A completed validation answers **200**, valid or not. A body that is not FHIR at all answers **400**
  (`invalid-resource`); overload or a validator that is not ready answers **503** with `Retry-After`.

### Manage IGs and profiles (administrator key required)

Administrative calls need `Authorization: Bearer <FV_ADMIN_API_KEY>`. The change applies globally, to every consumer
of that FHIR version, on every instance.

```bash
AUTH="Authorization: Bearer $FV_ADMIN_API_KEY"

# Store an IG package: upload, from a registry, or from an approved https URL
curl -H "$AUTH" -F "file=@hl7.fhir.dk.core-3.4.0.tgz;type=application/gzip" http://127.0.0.1:8880/igs/upload
curl -H "$AUTH" -H 'Content-Type: application/json' --data '{"name":"hl7.fhir.us.core","version":"7.0.0"}' http://127.0.0.1:8880/igs/register

# Include it in the R4 validator (resolves dependencies; replaces another version of the same package)
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' http://127.0.0.1:8880/R4/include-ig

# Register a custom profile (StructureDefinition)
curl -H "$AUTH" -H 'Content-Type: application/json' --data @my-profile.json http://127.0.0.1:8880/R4/register-profile
```

Package downloads are limited to the hosts in `FV_PACKAGE_SOURCES`, over https, to public addresses. Archives are
checked for size, entry count, expansion ratio and unsafe paths before they are unpacked.

### Health and metrics

| Endpoint | Meaning |
|----------|---------|
| `GET /health/liveness` | the process is alive (use for liveness probes) |
| `GET /health/readiness` | 200 when the database is reachable, every FHIR version has a usable validator, and the instance is not draining |
| `GET /health` | detailed report |
| `GET /metrics` | Prometheus metrics (`fv_validations_total`, `fv_validation_seconds`, `fv_validation_pending`, `fv_config_revision`, ...) |

On SIGTERM an instance reports not-ready, waits `FV_SHUTDOWN_PRE_DRAIN_MS`, finishes in-flight requests (up to
`FV_SHUTDOWN_GRACE_MS`), then exits.

## Configuration

All settings come from environment variables (optionally a JSON file named by `FV_CONFIG_FILE`); invalid values stop
startup with a message naming the variable. The full list, with defaults and bounds, is in
[`specs/001-service-hardening/contracts/configuration.md`](specs/001-service-hardening/contracts/configuration.md)
and [`.env.example`](.env.example). The most important ones:

| Variable | Default | Purpose |
|----------|---------|---------|
| `FV_PG_HOST`, `FV_PG_PORT`, `FV_PG_DATABASE`, `FV_PG_USER`, `FV_PG_PASSWORD` | `localhost`, `5432`, `fhir_validator`, `postgres`, *(required)* | database |
| `FV_ADMIN_API_KEY` | *(unset: admin operations refused)* | bootstrap administrator key |
| `FV_AUTH_ANONYMOUS_VALIDATE` | `true` | `false` requires a key for validation and reads too |
| `FV_HTTP_PORT` | `8880` | HTTP port |
| `FV_VALIDATION_WORKERS` / `FV_VALIDATION_MAX_PENDING` | cores / 4 × workers | parallel validations / admission limit |
| `FV_ADMIN_MAX_PENDING` | 4 | administrative operations in progress; beyond it 503 `overloaded` |
| `FV_PACKAGE_SOURCES` | `packages.fhir.org,packages2.fhir.org` | registries and approved download hosts |
| `FV_CORS_ALLOWED_ORIGINS` | *(none)* | browser origins allowed to call the API |

## Development

```bash
./mvnw verify        # unit, Testcontainers integration and contract tests (needs Docker)
make run-postgres    # PostgreSQL on 127.0.0.1:54329 for IDE runs (export FV_PG_PASSWORD first)
FV_PROFILE=dev FV_PG_PORT=54329 FV_PG_PASSWORD=... java -jar target/fhir-validation-service-*.jar
```

- Load tests: [`perf/README.md`](perf/README.md)
- Upgrade rehearsal: `sh scripts/upgrade-rehearsal.sh`
- Design and decisions: [`specs/001-service-hardening/`](specs/001-service-hardening/)
- Project principles: [`.specify/memory/constitution.md`](.specify/memory/constitution.md)
- Changes: [`CHANGELOG.md`](CHANGELOG.md)
