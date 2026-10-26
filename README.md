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

- One of:
  - Docker with Compose v2 (published container image, [option A](#option-a-docker-compose-with-the-published-image)), or
  - Java 17 or later and your own PostgreSQL ([option C](#option-c-jar-with-your-own-postgresql)), or
  - Java 17 and Maven, to build and develop
- PostgreSQL 16 (provided by the Compose setup)
- About 2 GB of memory per instance (warmed validators for four FHIR versions). Including an IG rebuilds that
  version's validator while the old one keeps serving, so allow headroom of at least twice the largest validator:
  watch `fv_snapshot_package_size_bytes` (archive size; parsed packages take several times more heap)

## Installation

There are three ways to run the service:

| Option | Use when | You provide |
|--------|----------|-------------|
| [A. Docker Compose, published image](#option-a-docker-compose-with-the-published-image) | you want a running service without the source code | Docker |
| [B. Docker Compose, source checkout](#option-b-docker-compose-from-a-source-checkout) | you want the cluster, observability and pgAdmin profiles, or to build the image yourself | Docker, git |
| [C. Jar with your own PostgreSQL](#option-c-jar-with-your-own-postgresql) | you already run PostgreSQL, or deploy without containers (VM, systemd) | Java 17+, PostgreSQL |

Whichever you choose, you need an **administrator API key**: 32-128 characters of `[A-Za-z0-9_-]`. Generate one with

```bash
openssl rand -hex 32
```

Without `FV_ADMIN_API_KEY` the service still validates, but refuses every administrative call (IG upload, include-ig,
profile registration).

### Option A: Docker Compose with the published image

Images are published to `ghcr.io/nazrulworld/fhir-validation-service` for `linux/amd64` and `linux/arm64`:

| Tag | Meaning |
|-----|---------|
| `1.0.0` | an exact release (recommended for production: pin it) |
| `1.0` | the newest patch release of 1.0 |
| `latest` | the newest release |
| `sha-<commit>` | a build of `main` at that commit (unreleased; for testing) |

**1. Create a directory and an `.env` file**

```bash
mkdir fhir-validation && cd fhir-validation

cat > .env <<EOF
FV_TAG=1.0.0
PG_PASSWORD=$(openssl rand -hex 24)
FV_ADMIN_API_KEY=$(openssl rand -hex 32)
EOF
chmod 600 .env
```

Any other setting from [`.env.example`](.env.example) can be added to `.env`; the validator receives the whole file.

**2. Create `compose.yaml`**

```yaml
name: fhir

services:
  postgres:
    image: postgres:16.8-alpine
    environment:
      POSTGRES_DB: fhir_validator
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${PG_PASSWORD:?set PG_PASSWORD in .env}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d fhir_validator"]
      interval: 5s
      timeout: 3s
      retries: 20
    restart: unless-stopped

  validator:
    image: ghcr.io/nazrulworld/fhir-validation-service:${FV_TAG:-latest}
    env_file: .env
    environment:
      FV_PG_HOST: postgres
      FV_PG_PORT: "5432"
      FV_PG_DATABASE: fhir_validator
      FV_PG_USER: postgres
      FV_PG_PASSWORD: ${PG_PASSWORD:?set PG_PASSWORD in .env}
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "127.0.0.1:8880:8880"      # drop "127.0.0.1:" to listen on every interface
    mem_limit: 2g                  # the JVM uses 75% as heap
    stop_grace_period: 30s
    restart: unless-stopped

volumes:
  pgdata:
```

The image has its own health check (`/health/readiness`, 120 s start period), so `--wait` below waits until the
validators are warmed.

Alternatively, download `compose.yaml` and `default.env.example` (rename it to `.env`) from the
[latest release](https://github.com/nazrulworld/fhir-validation-service/releases/latest). That file also defines the
`cluster`, `observability` and `admin` profiles, but those need the configuration files under `docker/` from a source
checkout ([option B](#option-b-docker-compose-from-a-source-checkout)).

**3. Start and check**

```bash
docker compose pull
docker compose up -d --wait        # first start takes 1-2 minutes while the validators warm up
curl -fsS http://127.0.0.1:8880/health/readiness
```

Then [validate a resource](#validate-a-resource).

**Day-to-day**

| Task | Command |
|------|---------|
| Logs | `docker compose logs -f validator` |
| Stop (keep data) | `docker compose down` |
| Upgrade | set a new `FV_TAG` in `.env`, then `docker compose pull && docker compose up -d --wait` (database migrations run automatically on start; read the [CHANGELOG](CHANGELOG.md) first) |
| Back up | `docker compose exec postgres pg_dump -U postgres -Fc fhir_validator > fhir_validator.dump` |
| Restore | `docker compose exec -T postgres pg_restore -U postgres -d fhir_validator --clean --if-exists < fhir_validator.dump` |
| Delete everything, data included | `docker compose down -v` |

### Option B: Docker Compose from a source checkout

```bash
git clone https://github.com/nazrulworld/fhir-validation-service.git
cd fhir-validation-service
cp .env.example .env      # set PG_PASSWORD (= FV_PG_PASSWORD) and FV_ADMIN_API_KEY
make up                   # = docker compose up -d --wait : PostgreSQL + one validator on 127.0.0.1:8880
curl -fsS http://127.0.0.1:8880/health/readiness
```

`make up` uses the published image named by `FV_TAG` when it is available; `docker compose build` builds it from the
checkout instead.

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

### Option C: Jar with your own PostgreSQL

The release jar is self-contained (all dependencies included). The service creates and migrates its own schema
(`fhir_validator_schema`, with Flyway) on startup; you only provide an empty database and a user.

**1. Install Java 17 or later**

```bash
java -version             # must report 17 or later, e.g. Eclipse Temurin 17/21
```

**2. Prepare PostgreSQL (16 recommended)**

As a PostgreSQL superuser (for example `sudo -u postgres psql`):

```sql
CREATE ROLE fhir_validator LOGIN PASSWORD 'choose-a-strong-password';
CREATE DATABASE fhir_validator OWNER fhir_validator;
```

The user must be able to create a schema in the database (owning it, as above, is enough). If you prefer a user that
does not own the database, create the schema for it instead:

```sql
\c fhir_validator
CREATE SCHEMA fhir_validator_schema AUTHORIZATION fhir_validator;
```

If PostgreSQL runs on another host, allow the connection in `pg_hba.conf` and set `FV_PG_SSL_MODE` to `require`
or `verify-full`.

**3. Download the jar**

```bash
VERSION=1.0.0
curl -fLO "https://github.com/nazrulworld/fhir-validation-service/releases/download/v${VERSION}/fhir-validation-service-${VERSION}.jar"
```

(Or build it: `./mvnw -B package -DskipTests` produces `target/fhir-validation-service-<version>.jar`.)

**4. Configure and run**

Settings are read from environment variables only (the service does not read `.env` files itself):

```bash
export FV_PG_HOST=localhost
export FV_PG_PORT=5432
export FV_PG_DATABASE=fhir_validator
export FV_PG_USER=fhir_validator
export FV_PG_PASSWORD='choose-a-strong-password'
export FV_ADMIN_API_KEY='<output of openssl rand -hex 32>'
export FV_HTTP_PORT=8880

java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xms512m -Xmx1536m \
  -jar fhir-validation-service-1.0.0.jar
```

```bash
curl -fsS http://127.0.0.1:8880/health/readiness   # 200 once every FHIR version is warmed (1-2 minutes)
```

The jar listens on all interfaces at `FV_HTTP_PORT`; put it behind a reverse proxy or firewall rather than exposing it
directly.

**5. Run it as a service (systemd example)**

`/etc/fhir-validator/env` (mode `0600`, owner `root`), one `NAME=value` per line, no `export`:

```ini
FV_PG_HOST=localhost
FV_PG_DATABASE=fhir_validator
FV_PG_USER=fhir_validator
FV_PG_PASSWORD=choose-a-strong-password
FV_ADMIN_API_KEY=replace-with-the-generated-key
FV_LOG_FORMAT=json
```

`/etc/systemd/system/fhir-validator.service`:

```ini
[Unit]
Description=FHIR Validation Service
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=fhir
Group=fhir
EnvironmentFile=/etc/fhir-validator/env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xmx1536m -jar /opt/fhir-validator/fhir-validation-service.jar
# SIGTERM drains in-flight requests; allow FV_SHUTDOWN_PRE_DRAIN_MS + FV_SHUTDOWN_GRACE_MS
TimeoutStopSec=30
Restart=on-failure
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin fhir
sudo install -D -m 0444 fhir-validation-service-1.0.0.jar /opt/fhir-validator/fhir-validation-service.jar
sudo systemctl daemon-reload && sudo systemctl enable --now fhir-validator
journalctl -u fhir-validator -f
```

To run more instances, start more processes (any host) against the same database; put a load balancer in front that
health-checks `/health/readiness`. To upgrade, replace the jar and restart; migrations run on start.

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
