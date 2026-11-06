# FHIR Validation Service

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)](LICENSE.md)

A horizontally scalable FHIR resource validation service built with Vert.x, HAPI FHIR and PostgreSQL.

It validates FHIR resources (STU3, R4, R4B, R5) against the base specification, or against one Implementation Guide
(IG) package that each request names, the same model as the HL7 validator CLI's `-ig` and `-profile`. Several IGs, and
several versions of one IG, can be offered side by side. Any number of identical instances can run against one
PostgreSQL database; IG changes reach every instance within seconds, without restarts.

> **Upgrading from 2.x?** 3.0.0 validates a request against the core specification only unless it names an IG with
> `?ig=name#version`. Read [docs/upgrade-3.0.md](docs/upgrade-3.0.md) before upgrading.
>
> **Upgrading from 1.x?** 2.0.0 removes custom profile registration and deletes stored custom profiles. Read
> [docs/upgrade-2.0.md](docs/upgrade-2.0.md) **before** upgrading: export first.

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
- Up to 4 GB of memory per instance (the default container limit; the JVM takes 75% as heap). Warmed core-only
  validators for four FHIR versions take about 0.9 GB of heap. Every IG in use has its own validator context (the IG
  plus its dependencies): one per included IG, plus up to `FV_IG_MAX_ON_DEMAND` (default 4) built on demand. A build
  holds about 6 times the context's expanded size. Size the heap as 0.9 GB + the contexts you keep + 64 MB + 6 × the
  largest context's expanded size: 4 GB fits contexts up to about 350 MB expanded (roughly a 30 MB archive), including
  a 20 MB IG such as `hl7.fhir.r4.examples` (180 MB expanded). The cluster profile runs two instances, so the host needs
  room for two such limits. An include that would not fit is refused with 413 `package-too-large` and nothing is
  committed; on-demand contexts are evicted (least recently used first) before a new one is refused with 503
  `insufficient-capacity`. Watch `fv_snapshot_package_size_bytes` and `fv_validator_contexts`.

## Installation

There are three ways to run the service:

| Option                                                                                  | Use when                                                                                 | You provide          |
|-----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|----------------------|
| [A. Docker Compose, published image](#option-a-docker-compose-with-the-published-image) | you want a running service without the source code                                       | Docker               |
| [B. Docker Compose, source checkout](#option-b-docker-compose-from-a-source-checkout)   | you want the cluster, observability and pgAdmin profiles, or to build the image yourself | Docker, git          |
| [C. Jar with your own PostgreSQL](#option-c-jar-with-your-own-postgresql)               | you already run PostgreSQL, or deploy without containers (VM, systemd)                   | Java 17+, PostgreSQL |

Whichever you choose, you need an **administrator API key**: 32-128 characters of `[A-Za-z0-9_-]`. Generate one with

```bash
openssl rand -hex 32
```

Without `FV_ADMIN_API_KEY` the service still validates, but refuses every administrative call (IG upload, register,
include-ig).

### Option A: Docker Compose with the published image

Images are published to `ghcr.io/nazrulworld/fhir-validation-service` for `linux/amd64` and `linux/arm64`:

| Tag            | Meaning                                                    |
|----------------|------------------------------------------------------------|
| `3.0.0`        | an exact release (recommended for production: pin it)      |
| `3.0`          | the newest patch release of 3.0                            |
| `latest`       | the newest release                                         |
| `sha-<commit>` | a build of `main` at that commit (unreleased; for testing) |

`latest` is now 3.0.0, a breaking release. A 2.x deployment that tracks `latest` (the default `FV_TAG` in
`compose.yaml`) would upgrade on its next pull, and requests without `ig` would stop using included IGs. Pin `FV_TAG`
to your current release and follow [docs/upgrade-3.0.md](docs/upgrade-3.0.md) before moving to 3.0.0 (from 1.x, follow
[docs/upgrade-2.0.md](docs/upgrade-2.0.md) first).

**1. Create a directory and an `.env` file**

```bash
mkdir fhir-validation && cd fhir-validation

cat > .env <<EOF
FV_TAG=3.0.0
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
    mem_limit: 4g                  # the JVM uses 75% as heap
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

| Task                             | Command                                                                                                                                                                            |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Logs                             | `docker compose logs -f validator`                                                                                                                                                 |
| Stop (keep data)                 | `docker compose down`                                                                                                                                                              |
| Upgrade                          | set a new `FV_TAG` in `.env`, then `docker compose pull && docker compose up -d --wait` (database migrations run automatically on start; read the [CHANGELOG](CHANGELOG.md) first) |
| Back up                          | `docker compose exec postgres pg_dump -U postgres -Fc fhir_validator > fhir_validator.dump`                                                                                        |
| Restore                          | `docker compose exec -T postgres pg_restore -U postgres -d fhir_validator --clean --if-exists < fhir_validator.dump`                                                               |
| Delete everything, data included | `docker compose down -v`                                                                                                                                                           |

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

| Command                                | Starts                                              | Endpoint              |
|----------------------------------------|-----------------------------------------------------|-----------------------|
| `make up`                              | PostgreSQL, one validator                           | http://127.0.0.1:8880 |
| `make up-cluster`                      | + a second validator behind nginx (shared database) | http://127.0.0.1:8881 |
| `make up-obs`                          | + Prometheus and a PostgreSQL exporter              | http://127.0.0.1:9090 |
| `docker compose --profile admin up -d` | + pgAdmin (credentials from `.env`)                 | http://127.0.0.1:5050 |
| `make down` / `make clean`             | stop (keep / delete volumes)                        |                       |
| `make smoke`                           | build, start, validate, graceful stop               |                       |

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
VERSION=3.0.0
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

java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xms512m -Xmx3g \
  -jar fhir-validation-service-3.0.0.jar
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
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xmx3g -jar /opt/fhir-validator/fhir-validation-service.jar
# SIGTERM drains in-flight requests; allow FV_SHUTDOWN_PRE_DRAIN_MS + FV_SHUTDOWN_GRACE_MS
TimeoutStopSec=30
Restart=on-failure
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd --system --no-create-home --shell /usr/sbin/nologin fhir
sudo install -D -m 0444 fhir-validation-service-3.0.0.jar /opt/fhir-validator/fhir-validation-service.jar
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
{"valid": true, "messages": [], "fhirVersion": "R4", "appliedProfiles": [], "igPackage": null}
```

- `?ig=<name>#<version>` validates against that one IG package, its dependencies and the core specification, like
  `validator_cli.jar -ig`. URL-encode `#` as `%23`: `?ig=hl7.fhir.us.core%237.0.0`. `?ig=<name>` or `#latest` uses the
  highest stored version. Without `ig`, only the core specification applies. Any **stored** package that supports the
  FHIR version can be named: an included one is pre-built, any other is built on the first request that names it, from
  stored packages only (nothing is downloaded); later requests reuse it. The response names the context used in the
  `X-Validation-Context` header (`name#version` or `base`) and in `igPackage`.
- `Accept: application/fhir+json` or `application/fhir+xml` returns a FHIR `OperationOutcome` (unchanged body; read the
  header for the context).
- `?profile=<canonical>[,<canonical>]` validates against additional profiles (at most 10, each at most 1024
  characters). Each must be defined by the chosen IG, its dependencies or the core specification (the core
  specification alone without `ig`), matched exactly by `url` or `url|version`. Profiles are **never downloaded** from
  their canonical URL. If any of them cannot be resolved, the answer is **400 `unknown-profile`** with
  `unresolvedProfiles`, and nothing is validated. `appliedProfiles` lists only profiles that were actually applied.
- A completed validation answers **200**, valid or not. A body that is not FHIR at all answers **400**
  (`invalid-resource`). An `ig` package that is not stored answers **404** `package-not-found`, one whose dependency is
  not stored **424** `dependency-not-found`, and a malformed `ig` or a package for another FHIR version **400**
  `invalid-request`. Overload, a validator that is not ready (including an `ig` context still being built, for up to
  `FV_IG_BUILD_WAIT_MS`) or no memory left for another context answers **503** with `Retry-After`.

### Manage IGs (administrator key required)

Administrative calls need `Authorization: Bearer <FV_ADMIN_API_KEY>`. Changes apply on every instance within seconds.
Including an IG pre-builds and keeps its validator context; it does not change what requests without `ig`, or requests
naming another IG, validate against.

```bash
AUTH="Authorization: Bearer $FV_ADMIN_API_KEY"

# Store an IG package: upload, from a registry, or from an approved https URL
curl -H "$AUTH" -F "file=@hl7.fhir.dk.core-3.4.0.tgz;type=application/gzip" http://127.0.0.1:8880/igs/upload
curl -H "$AUTH" -H 'Content-Type: application/json' --data '{"name":"hl7.fhir.us.core","version":"7.0.0"}' http://127.0.0.1:8880/igs/register
# Without a version (or "latest") the registries are asked for the newest release; the answer says which version was
# chosen and whether it was downloaded ("downloaded": true) or already stored.
curl -H "$AUTH" -H 'Content-Type: application/json' --data '{"name":"hl7.fhir.us.core"}' http://127.0.0.1:8880/igs/register

# Pre-build it for R4 on every instance. Other versions of the same package stay included.
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' http://127.0.0.1:8880/R4/include-ig

# Stop pre-building it (the package stays stored and can still be named in ?ig=)
curl -H "$AUTH" -X DELETE 'http://127.0.0.1:8880/R4/include-ig?ig=hl7.fhir.dk.core%233.4.0'
```

A request validates against the IG it names, its dependencies and the core specification; no other stored or included
package contributes. `/igs/register` stores the dependencies too (unless `"includeDependency": false`); a package
uploaded through `/igs/upload` needs its dependencies stored before it can be built on demand. To use your own
profiles, put the StructureDefinitions in an IG package (`package/package.json` with `name`, `version`, `fhirVersions`
and `dependencies`, plus the `.json` resources under `package/`), then upload it and name it in `ig`, or include it to
have it pre-built.

Package downloads are limited to the hosts in `FV_PACKAGE_SOURCES`, over https, to public addresses. Archives are
checked for size, entry count, expansion ratio and unsafe paths before they are unpacked.

### API keys

`FV_ADMIN_API_KEY` installs the bootstrap administrator key at startup. There is no API for other keys in 1.x: manage
them in the `fhir_validator_schema.api_keys` table, which stores only the SHA-256 of each key. Roles are `ADMIN` and
`VALIDATE` (validation and the package read endpoints, needed when `FV_AUTH_ANONYMOUS_VALIDATE=false`).

```bash
KEY=$(openssl rand -hex 32)      # give this to the client; it is not stored
psql "$DB_URL" -v key="$KEY" <<'SQL'
-- add (or replace) a key
INSERT INTO fhir_validator_schema.api_keys (name, key_hash, role)
VALUES ('reporting-service', sha256(convert_to(:'key', 'UTF8')), 'VALIDATE')
ON CONFLICT (name) DO UPDATE SET key_hash = EXCLUDED.key_hash, role = EXCLUDED.role, revoked_at = NULL;
SQL

# revoke
psql "$DB_URL" -c "UPDATE fhir_validator_schema.api_keys SET revoked_at = now() WHERE name = 'reporting-service'"
```

To rotate a key without downtime, add the new one under a second name, switch the client over, then revoke the old one.
Instances cache key lookups for `FV_AUTH_CACHE_TTL_MS` (30 s by default), so a revoked key keeps working for up to that
long. Don't use the name `bootstrap`: that row is overwritten from `FV_ADMIN_API_KEY` at every start.

### Database connection security

`FV_PG_SSL_MODE` defaults to `prefer`, which falls back to an unencrypted connection when the server offers no TLS. The
bundled compose database runs on a private compose network. When the database is reached over any other network, set
`FV_PG_SSL_MODE=require`, or `verify-full` to also check the server certificate.

### Health and metrics

| Endpoint                | Meaning                                                                                                                  |
|-------------------------|--------------------------------------------------------------------------------------------------------------------------|
| `GET /health/liveness`  | the process is alive (use for liveness probes)                                                                           |
| `GET /health/readiness` | 200 when the database is reachable, every core and included-IG validator is usable, and the instance is not draining      |
| `GET /health`           | detailed report                                                                                                          |
| `GET /metrics`          | Prometheus metrics (`fv_validations_total`, `fv_validation_seconds`, `fv_validation_pending`, `fv_config_revision`, ...) |

On SIGTERM an instance reports not-ready, waits `FV_SHUTDOWN_PRE_DRAIN_MS`, finishes in-flight requests (up to
`FV_SHUTDOWN_GRACE_MS`), then exits.

## Configuration

All settings come from environment variables (optionally a JSON file named by `FV_CONFIG_FILE`); invalid values stop
startup with a message naming the variable. The full list, with defaults and bounds, is in
[`specs/001-service-hardening/contracts/configuration.md`](specs/001-service-hardening/contracts/configuration.md)
and [`.env.example`](.env.example). The most important ones:

| Variable                                                                     | Default                                                         | Purpose                                                           |
|------------------------------------------------------------------------------|-----------------------------------------------------------------|-------------------------------------------------------------------|
| `FV_PG_HOST`, `FV_PG_PORT`, `FV_PG_DATABASE`, `FV_PG_USER`, `FV_PG_PASSWORD` | `localhost`, `5432`, `fhir_validator`, `postgres`, *(required)* | database                                                          |
| `FV_ADMIN_API_KEY`                                                           | *(unset: admin operations refused)*                             | bootstrap administrator key                                       |
| `FV_AUTH_ANONYMOUS_VALIDATE`                                                 | `true`                                                          | `false` requires a key for validation and reads too               |
| `FV_HTTP_PORT`                                                               | `8880`                                                          | HTTP port                                                         |
| `FV_VALIDATION_WORKERS` / `FV_VALIDATION_MAX_PENDING`                        | cores / 4 × workers                                             | parallel validations / admission limit                            |
| `FV_ADMIN_MAX_PENDING`                                                       | 4                                                               | administrative operations and on-demand IG builds in progress; beyond it 503 `overloaded` |
| `FV_IG_MAX_ON_DEMAND`                                                        | 4                                                               | IG validator contexts built on demand kept per instance (LRU)     |
| `FV_IG_BUILD_WAIT_MS`                                                        | min(10000, request timeout / 2)                                 | how long a request waits for an on-demand IG build before 503     |
| `FV_PACKAGE_SOURCES`                                                         | `packages.fhir.org,packages2.fhir.org`                          | registries and approved download hosts                            |
| `FV_CORS_ALLOWED_ORIGINS`                                                    | *(none)*                                                        | browser origins allowed to call the API                           |

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
- Contributing: [`CONTRIBUTING.md`](CONTRIBUTING.md)

## License

This project is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE.md)
([official text](https://polyformproject.org/licenses/noncommercial/1.0.0)). This summary is for convenience only;
[`LICENSE.md`](LICENSE.md) is what applies.

- **Noncommercial use is free.** You may use, copy, modify and share the software for any noncommercial purpose:
  personal use, research, experiment and testing, hobby projects, and use by charities, educational institutions,
  public research organisations, public safety or health organisations, environmental protection organisations and
  government institutions.
- **Commercial use needs a separate license.** Any other use, including use inside a for-profit company, requires a
  commercial license. Contact **Md Nazrul Islam** at [email2nazrul@gmail.com](mailto:email2nazrul@gmail.com).
- **Third-party components keep their own licenses.** The libraries bundled in the service are not covered by
  PolyForm Noncommercial. Their licenses, notices and clarifications ship inside each package:
  - jar: `META-INF/THIRD-PARTY.txt`, `META-INF/NOTICE`, `META-INF/THIRD-PARTY-CLARIFICATIONS.md` and
    `META-INF/third-party-licenses/`;
  - container image: `/app/LICENSE.md` and `/app/licenses/` (which also points to the licenses of the Java runtime
    and OS packages in the image).
