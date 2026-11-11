# FHIR Validation Service

[![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)](LICENSE.md)

A horizontally scalable FHIR resource validation service built with Vert.x, HAPI FHIR and PostgreSQL.

It validates FHIR resources (STU3, R4, R4B, R5) against the base specification, or against one Implementation Guide
(IG) package that each request names, the same model as the HL7 validator CLI's `-ig` and `-profile`. Several IGs, and
several versions of one IG, can be offered side by side. Any number of identical instances can run against one
PostgreSQL database; IG changes reach every instance within seconds, without restarts.

> **Upgrading from 4.0?** 4.1.0 requires a key for every validation (`FV_AUTH_ANONYMOUS_VALIDATE` is gone), re-keys
> the system organisation (stop every 4.0 instance first) and adds usage accounting. Read
> [docs/upgrade-4.1.md](docs/upgrade-4.1.md) before upgrading.
>
> **Upgrading from 3.x?** 4.0.0 serves `?ig=` only from **included** IGs (a stored IG that isn't included answers
> 400), renames `/{version}/include-ig` to `/{version}/validator`, and adds optional terminology servers per included
> IG. Read [docs/upgrade-4.0.md](docs/upgrade-4.0.md) before upgrading.
>
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
  plus its dependencies): one per included IG (since 4.0.0 nothing is built on demand). A build
  holds about 6 times the context's expanded size. Size the heap as 0.9 GB + the contexts you keep + 64 MB + 6 × the
  largest context's expanded size: 4 GB fits contexts up to about 350 MB expanded (roughly a 30 MB archive), including
  a 20 MB IG such as `hl7.fhir.r4.examples` (180 MB expanded). The cluster profile runs two instances, so the host needs
  room for two such limits. An include that would not fit is refused with 413 `package-too-large` and nothing is
  committed. A context with a terminology mode adds at most about 5 MB of cached code results at the default
  `FV_TX_CACHE_SIZE`. Watch `fv_snapshot_package_size_bytes` and `fv_validator_contexts`.

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
and including, changing or excluding IGs at `/{version}/validator`).

### Option A: Docker Compose with the published image

Images are published to `ghcr.io/nazrulworld/fhir-validation-service` for `linux/amd64` and `linux/arm64`:

| Tag            | Meaning                                                    |
|----------------|------------------------------------------------------------|
| `4.1.0`        | an exact release (recommended for production: pin it)      |
| `4.1`          | the newest patch release of 4.1                            |
| `latest`       | the newest release                                         |
| `sha-<commit>` | a build of `main` at that commit (unreleased; for testing) |

`latest` is now 4.1.0, a minor release with breaking changes. A 4.0 deployment that tracks `latest` (the default
`FV_TAG` in `compose.yaml`) would upgrade on its next pull: every validation without an API key would answer 401, and
the database upgrade needs every 4.0 instance stopped first. Pin `FV_TAG` to your current release and follow
[docs/upgrade-4.1.md](docs/upgrade-4.1.md) before moving to 4.1.0 (from 3.x, follow
[docs/upgrade-4.0.md](docs/upgrade-4.0.md) first; from 2.x, [docs/upgrade-3.0.md](docs/upgrade-3.0.md) before that;
from 1.x, [docs/upgrade-2.0.md](docs/upgrade-2.0.md) before that).

**1. Create a directory and an `.env` file**

```bash
mkdir fhir-validation && cd fhir-validation

cat > .env <<EOF
FV_TAG=4.1.0
PG_PASSWORD=$(openssl rand -hex 24)
FV_ADMIN_API_KEY=$(openssl rand -hex 32)
# Every validation needs a key (4.1.0): this shared trial key, or keys created per client with 'fv-admin key create'.
FV_VALIDATOR_API_KEY=$(openssl rand -hex 32)
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

Every validation needs a VALIDATE or ADMIN key (4.1.0): a key created with `fv-admin key create`, or the shared trial
key set in `FV_VALIDATOR_API_KEY` (see [Organisations and API keys](#organisations-and-api-keys)).

```bash
curl -X POST http://127.0.0.1:8880/R4/validate \
  -H "Authorization: Bearer $FV_VALIDATOR_API_KEY" \
  -H 'Content-Type: application/fhir+json' \
  --data '{"resourceType":"Patient","active":true}'
```

```json
{"valid": true, "messages": [], "fhirVersion": "R4", "appliedProfiles": [], "igPackage": null}
```

- `?ig=<name>#<version>` validates against that one IG package, its dependencies and the core specification, like
  `validator_cli.jar -ig`. URL-encode `#` as `%23`: `?ig=hl7.fhir.us.core%237.0.0`. `?ig=<name>` or `#latest` uses the
  highest **included** version. Without `ig`, only the core specification applies. Since 4.0.0 only IG versions
  **included** for the FHIR version (see below) can be named; a stored version that isn't included is refused with
  **400** `invalid-request` ("IG package name#version is not included for R4; include it first"). Validation never
  builds a context. The response names the context used in the `X-Validation-Context` header (`name#version` or
  `base`) and in `igPackage`.
- `Accept: application/fhir+json` or `application/fhir+xml` returns a FHIR `OperationOutcome` (unchanged body; read the
  header for the context).
- `?profile=<canonical>[,<canonical>]` validates against additional profiles (at most 10, each at most 1024
  characters). Each must be defined by the chosen IG, its dependencies or the core specification (the core
  specification alone without `ig`), matched exactly by `url` or `url|version`. Profiles are **never downloaded** from
  their canonical URL. If any of them cannot be resolved, the answer is **400 `unknown-profile`** with
  `unresolvedProfiles`, and nothing is validated. `appliedProfiles` lists only profiles that were actually applied.
- A completed validation answers **200**, valid or not. A body that is not FHIR at all answers **400**
  (`invalid-resource`). An `ig` package that is not stored answers **404** `package-not-found`, and a malformed `ig`,
  a package for another FHIR version or one that isn't included **400** `invalid-request`. Overload or a validator
  that is not ready (an included context still being built or rebuilt) answers **503** with `Retry-After`.
- For an IG included with a terminology mode (see below), codes that can't be decided from its packages are checked
  by a terminology server. A code that could not be checked (server unavailable, the request's terminology time used
  up, no permitted server) is reported as a **warning**, never an error; the answer is still 200.

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
# A version series (3.2.0): the newest 6.1.x release the registries list. The answer reports the concrete "version"
# and the "requestedVersion" you sent.
curl -H "$AUTH" -H 'Content-Type: application/json' --data '{"name":"hl7.fhir.us.core","version":"6.1.x"}' http://127.0.0.1:8880/igs/register

# Pre-build it for R4 on every instance. Other versions of the same package stay included.
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.dk.core","igPackageVersion":"3.4.0"}' http://127.0.0.1:8880/R4/validator
# A series here picks the newest *stored* match you may use, without contacting a registry, and pins that version.
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.us.core","igPackageVersion":"6.x"}' http://127.0.0.1:8880/R4/validator

# Stop pre-building it (the package stays stored; ?ig= naming it is refused with 400 until it is included again)
curl -H "$AUTH" -X DELETE 'http://127.0.0.1:8880/R4/validator?ig=hl7.fhir.dk.core%233.4.0'
```

A request validates against the IG it names, its dependencies and the core specification; no other stored or included
package contributes. A version series is `N.x`, `N.x.x` or `N.N.x`; versions are compared part by part, so `1.2.x`
never matches `1.20.0`, and a release wins over a pre-release. `?ig=` on validation needs an exact version (or none):
a series there is 400. `/igs/register` stores the dependencies too (unless `"includeDependency": false`), resolving a
dependency declared as a series (`"1.0.x"`) or as `latest` the same way as the package itself (3.2.1); a package
uploaded through `/igs/upload` needs its dependencies stored before it is included. To use your own
profiles, put the StructureDefinitions in an IG package (`package/package.json` with `name`, `version`, `fhirVersions`
and `dependencies`, plus the `.json` resources under `package/`), then upload it, include it, and name it in `ig`.

Package downloads are limited to the hosts in `FV_PACKAGE_SOURCES`, over https, to public addresses. Archives are
checked for size, entry count, expansion ratio and unsafe paths before they are unpacked.

### Terminology servers (4.0.0)

By default every context is offline: codes are checked only against code systems in its packages and the built-in
common code systems (UCUM, BCP-47, ISO 3166/4217, MIME types). An included IG can instead use a terminology server,
like `validator_cli.jar -tx`:

| Mode        | Behaviour                                                                                                                                   |
|-------------|---------------------------------------------------------------------------------------------------------------------------------------------|
| `none`      | default; no outbound request at all                                                                                                         |
| `server`    | codes that can't be decided locally are checked by one server (`"server": "<base url>"`)                                                    |
| `ecosystem` | each code system is routed to the server the official HL7 terminology registry names for it; an optional `server` replaces tx.fhir.org    |

```bash
# Set it when including ...
curl -H "$AUTH" -H 'Content-Type: application/json' \
  --data '{"igPackageId":"hl7.fhir.uv.ips","igPackageVersion":"2.0.0","terminology":{"mode":"ecosystem"}}' \
  http://127.0.0.1:8880/R4/validator
# ... or change it later (the context's owner or the system organisation); every instance rebuilds the context.
curl -H "$AUTH" -H 'Content-Type: application/json' -X PATCH --data '{"terminology":{"mode":"none"}}' \
  'http://127.0.0.1:8880/R4/validator?ig=hl7.fhir.uv.ips%232.0.0'
```

- **Only registry servers.** The service contacts only open servers that the official registry
  (`https://ecosystem.hl7.org/tx-reg`, fixed, not configurable) lists for the FHIR version, on public addresses,
  without following redirects. A setting naming another server is refused with **400** `tx-server-not-registered`.
  The list is refreshed every `FV_TX_REGISTRY_REFRESH_MS` and the last good copy is kept in the database. Until a list
  has ever been obtained, only a built-in fallback (`http://tx.fhir.org/r4`, `http://tx.fhir.org/r5`) is allowed, and
  other servers are refused with **503** `tx-registry-unavailable`.
- Some listed servers are plain `http://` (for example `http://tx.fhir.org/r4`); they are contacted exactly as listed.
  Only codes, systems, displays and the IG's own value set and code system definitions are sent, never the resource
  being validated.
- The registry lists no server for **STU3** and **R4B**, so those versions can only use mode `none`.
- An IG's own value sets are sent with the request, so membership follows the IG; its code systems go along as
  `tx-resource`.
- Each call is bounded by `FV_TX_TIMEOUT_MS`, each request by `FV_TX_REQUEST_BUDGET_MS` of terminology time; a server
  that keeps failing is skipped for a minute. Answers are cached per context (`FV_TX_CACHE_SIZE`, `FV_TX_CACHE_TTL_MS`).
- Validation workers wait on terminology calls, so terminology-heavy traffic may need a larger `FV_VALIDATION_WORKERS`.
- `GET /health` shows the allowlist per FHIR version (`source`: `registry`, `stored-registry` or `built-in`) and each
  context's setting; `/metrics` adds `fv_tx_calls_total`, `fv_tx_call_seconds`, `fv_tx_routing_lookups_total`,
  `fv_tx_code_cache_hits_total` and `fv_tx_allowlist_age_seconds`.

### Organisations and API keys

Since 3.1.0 every API key, IG package and included IG belongs to an **organisation**. A built-in **system
organisation** (id `00000000-0000-0000-0000-100000000001` since 4.1.0) owns everything that existed before the upgrade,
and the key from `FV_ADMIN_API_KEY` (`system administrator`) belongs to it. A built-in **trial organisation** (`trial`,
4.1.0) owns the shared VALIDATE key from `FV_VALIDATOR_API_KEY` (`trial validator`): a free-trial key that may use
shareable content only. Organisations and keys are managed with the command line in the service jar and image (no SQL needed):

```bash
APP="docker compose run --rm validator fv-admin"     # or: java -jar target/fhir-validation-service-*.jar
$APP org create --name-short acme --name-long "Acme Health ApS" --type COMMERCIAL --country DK
$APP org create --file beta.json --email ops@beta.example      # a JSON file, with flags overriding its fields
$APP org update --name-short acme --phone "+45 12 34 56 78"   # only the given fields change
$APP key create --org acme --name acme-ci --role VALIDATE      # prints the key once; only its hash is stored
$APP key create --org acme --name acme-temp --role VALIDATE --expires 2027-01-01T00:00:00Z   # 4.1.0: refused after that
$APP key list --org acme                                        # never shows keys or hashes; shows expiresAt, lastUsedIp
$APP key revoke --name acme-ci
$APP org deactivate --name-short acme                           # its keys get 403 within FV_AUTH_CACHE_TTL_MS
$APP org activate --name-short acme
```

Commands print one JSON document on stdout, write errors to stderr as `error: <code>: <message>`, and exit with 0
(ok), 2 (usage or invalid value), 3 (not found), 4 (conflict) or 1 (anything else). They use the same `FV_PG_*`
settings as the service and apply migrations first. The full reference is
[`specs/005-organisation-ownership/contracts/cli.md`](specs/005-organisation-ownership/contracts/cli.md).

Roles are `ADMIN` and `VALIDATE` (validation and the package read endpoints; since 4.1.0 these always need a key). To
rotate a key without downtime, create the new one under a second name, switch the client over, then revoke the old one.
Instances cache key lookups for `FV_AUTH_CACHE_TTL_MS` (30 s by default), so revocations and organisation deactivations
take effect within that time; an expired key is refused at its expiry, cached or not. `key list` shows each key's
`lastUsedIp`, written within a second when a key is used from a new address, so a stolen key used elsewhere stands
out.

The names `system administrator` and `trial validator` are reserved for the keys installed from `FV_ADMIN_API_KEY` and
`FV_VALIDATOR_API_KEY` (`key create` refuses them). They are rotated by changing the variable and restarting. Seeded keys are revoked like any other key, with `key revoke --name "trial validator"`. A revoked seeded key's value is never installed again (it is remembered in `revoked_key_hashes`, V13): an instance
still configured with a leaked, revoked value logs an ERROR and leaves the stored key alone, also after the key was
rotated. Deploy a new value to replace it. Instances configured with different non-revoked values still overwrite each
other's key, so change the value on all instances together. An instance started without the variable leaves the
stored key as it is.

#### Network controls (4.1.0)

- `FV_AUTH_ADMIN_CIDRS` (comma-separated IPv4/IPv6 networks) limits administrative operations to those networks: any
  other client gets 403, whatever key it sends, before the key is even looked up. Unset, nothing is restricted.
- `FV_AUTH_TRUSTED_PROXIES` names the reverse proxies whose forwarding header is believed, and
  `FV_AUTH_FORWARDED_HEADER` says which one they write: `x-forwarded-for` (default) or `forwarded`. Only that header is
  read: a proxy that appends to one passes a client's copy of the other through unchanged. The client is the
  right-most address that isn't a trusted proxy. From any other peer the header is ignored, so a client can't fake its
  address. When no such address is found (no header, every hop trusted, an unparseable entry), or the peer's own address
  can't be parsed, the client is unknown: administrative calls are refused and nothing is recorded as its address. The same address is used for the
  allowlist, `lastUsedIp` and usage records.

#### Who may use what

- **IG packages** stored by register, upload or URL download belong to the caller's organisation, dependencies
  included. They are **shareable** unless the request says `"shareable": false` (upload: form field `shareable=false`).
  The package licence is not used.
- **Included IGs** (validator contexts) belong to the organisation that included them, shareable by default
  (`"shareable": false` in the include body). Only the owner of the IG package can include it as private.
- A caller may use content its organisation owns, content that is shareable, and (the system organisation) everything.
  The trial organisation owns nothing, so the trial key may use shareable content only. A validation context is usable only when it and every package it
  is built from are usable.
- Content you may not use is answered exactly like content that isn't stored: 404 `package-not-found`, or 424
  `dependency-not-found` without naming the dependency. Uploading a `name#version` another organisation stored answers
  409 `conflict`.
- Validation responses report the context's owner and sharing: `igOwner`/`igShareable` in the JSON body and the
  `X-Validation-Context-Owner`/`X-Validation-Context-Shareable` headers.

Change sharing, or (system organisation only) transfer ownership, over HTTP or with the command line:

```bash
curl -H "Authorization: Bearer $ACME_ADMIN" -X PATCH -H 'Content-Type: application/json' \
  --data '{"shareable": false}' http://127.0.0.1:8880/igs/acme.core/1.0.0
curl -H "Authorization: Bearer $FV_ADMIN_API_KEY" -X PATCH -H 'Content-Type: application/json' \
  --data '{"owner": "beta"}' 'http://127.0.0.1:8880/R4/validator?ig=acme.core%231.0.0'
$APP package share --ig acme.core#1.0.0 --shareable true
$APP snapshot transfer --fhir-version R4 --ig acme.core#1.0.0 --to beta
```

The owner (or the system organisation) may change `shareable`; another organisation gets 404, and an owner that
isn't the system organisation gets 403 for a transfer. Changes reach every instance within seconds and never rebuild a
validator.

### Usage accounting (4.1.0)

Every authorised validation can be recorded, attributed to its key and organisation, with its outcome, issue counts,
phase timings (`totalMs`, `queueMs`, `contextMs`, `validateMs`), CPU time and allocated bytes. "Memory" means bytes
allocated by the request's work: the heap is shared, so resident memory can't be attributed to a request.
`FV_USAGE_SINK` chooses where records go:

- `none` (default): nothing is kept (the CPU and allocation metrics are still measured);
- `log`: one JSON line per request on the logger `nzi.fhir.validator.usage`, which `log4j2.xml` sends to its own
  appender (`UsageConsole`, just the JSON), so it can be routed to a file without touching the application log. Lines
  are written from their own thread through a bounded queue (8 192 lines); when it is full they are dropped and
  counted (`fv_usage_dropped_total{reason="log_full"}`), and a validation never waits;
- `db`: rows in `fhir_validator_schema.validation_usage`, written in batches from a bounded queue
  (`FV_USAGE_QUEUE_SIZE`, `FV_USAGE_BATCH_SIZE`, `FV_USAGE_FLUSH_MS`). A full queue or a failed write drops records and
  counts them (`fv_usage_dropped_total`); a validation never waits for usage. A graceful stop writes what is
  buffered; a hard kill (SIGKILL) loses at most the last `FV_USAGE_FLUSH_MS` of records;
- `log,db`: both.

Nothing deletes old rows: schedule `DELETE FROM fhir_validator_schema.validation_usage WHERE created_at < now() -
interval '90 days'` (about 250 bytes a row). Summaries: `$APP usage summary --org acme --since 2026-11-01T00:00:00Z`
(or `--key <name>`).

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
| `GET /metrics`          | Prometheus metrics (`fv_validations_total`, `fv_validation_seconds`, `fv_validation_cpu_seconds`, `fv_validation_allocated_bytes`, `fv_usage_*`, `fv_validation_pending`, `fv_config_revision`, `fv_tx_*`, ...) |

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
| `FV_VALIDATOR_API_KEY`                                                       | *(unset: warning)*                                              | the trial organisation's shared VALIDATE key (4.1.0)              |
| `FV_AUTH_ADMIN_CIDRS` / `FV_AUTH_TRUSTED_PROXIES`                            | *(unset)*                                                       | admin network allowlist / proxies whose headers count (4.1.0)     |
| `FV_AUTH_FORWARDED_HEADER`                                                   | `x-forwarded-for`                                               | the header the trusted proxies write (`forwarded` for RFC 7239)   |
| `FV_USAGE_SINK`                                                              | `none`                                                          | usage records: `none`, `log`, `db`, `log,db` (4.1.0)              |
| `FV_USAGE_QUEUE_SIZE` / `_BATCH_SIZE` / `_FLUSH_MS`                          | 10000 / 500 / 1000                                              | the `db` sink's bounded queue and batching (4.1.0)                |
| `FV_HTTP_PORT`                                                               | `8880`                                                          | HTTP port                                                         |
| `FV_VALIDATION_WORKERS` / `FV_VALIDATION_MAX_PENDING`                        | cores / 4 × workers                                             | parallel validations / admission limit                            |
| `FV_ADMIN_MAX_PENDING`                                                       | 4                                                               | administrative operations in progress; beyond it 503 `overloaded` |
| `FV_TX_TIMEOUT_MS` / `FV_TX_REQUEST_BUDGET_MS`                               | 5000 / 10000                                                    | one terminology call / a request's total terminology time (4.0.0) |
| `FV_TX_CACHE_SIZE` / `FV_TX_CACHE_TTL_MS`                                    | 5000 / 600000                                                   | code results cached per terminology context (4.0.0)               |
| `FV_TX_REGISTRY_REFRESH_MS`                                                  | 21600000 (6 h)                                                  | how often the registry's server list is refreshed (4.0.0)         |
| `FV_TX_MAX_CONNECTIONS_PER_SERVER`                                           | 10                                                              | pooled connections per terminology server (4.0.0)                 |
| `FV_PACKAGE_SOURCES`                                                         | `packages.fhir.org,packages2.fhir.org`                          | registries and approved download hosts                            |
| `FV_CORS_ALLOWED_ORIGINS`                                                    | *(none)*                                                        | browser origins allowed to call the API                           |
| `FV_SYSTEM_ORG_NAME_SHORT`, `FV_SYSTEM_ORG_NAME_LONG`                        | `system`, `FHIR Validation Service`                             | the system organisation's names, applied at every start (3.1.0)   |
| `FV_SYSTEM_ORG_EMAIL`, `_PHONE`, `_WEBSITE`, `_COUNTRY`, `_POST_CODE`        | *(unset)*                                                       | the system organisation's contact details (3.1.0)                 |
| `FV_SYSTEM_ORG_ADDRESS_LINES`                                                | *(unset)*                                                       | address lines separated by `\|` (3.1.0)                           |
| `FV_SYSTEM_ORG_CONTACT_PERSON_NAME`, `_MOBILE`, `_EMAIL`                     | *(unset)*                                                       | the system organisation's contact person (3.1.0)                  |

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
