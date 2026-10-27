# Contract: Container image, Compose and CI

Covers FR-027 and FR-032 to FR-035, plus audit section 3b (D1–D12). Decisions are in R16, R17
and R18 of [research.md](../research.md).

## 1. Image `ghcr.io/nazrulworld/fhir-validation-service`

| Aspect | Contract |
|--------|----------|
| Platforms | `linux/amd64`, `linux/arm64`, both built and smoke-tested in CI on every PR: amd64 with the full smoke test (`scripts/smoke.sh`), arm64 with a startup smoke test under QEMU (`scripts/startup-smoke.sh`) |
| Process | Only the service JVM, PID 1 under `tini`. No database, no shell supervisor |
| User | Numeric non-root `10001:10001`. The image works with `--read-only` plus a tmpfs on `/tmp` |
| Ports | `8880/tcp` (overridable through `FV_HTTP_PORT`, and `EXPOSE`/`HEALTHCHECK` follow it) |
| Config | Environment variables only ([configuration.md](./configuration.md)), plus an optional mounted `FV_CONFIG_FILE` |
| Signals | SIGTERM → readiness 503 → drain → exit 0 within `FV_SHUTDOWN_PRE_DRAIN_MS + FV_SHUTDOWN_GRACE_MS` |
| Health | `HEALTHCHECK` = `GET /health/readiness`, interval 15 s, timeout 3 s, start period 120 s, retries 4. Orchestrators should use `/health/liveness` for liveness and `/health/readiness` for readiness |
| Volumes | None declared. Uploads go to `/tmp` |
| Labels | `org.opencontainers.image.{title,version,revision,source,licenses,created}` |
| Tags | `X.Y.Z`, `X.Y`, `latest` (**releases only**), `sha-<short>` (every main build) |
| Size target | ≤ 550 MB uncompressed (measured 2026-10-25: 493 MB = JRE base ~260 MB + 234 MB shaded jar dominated by HAPI validation resources for 4 FHIR versions). The previous all-in-one image also carried PostgreSQL 16. Slimming the jar (e.g. trimming unused validation resources) is follow-up work |
| Exit codes | `0` graceful stop · `1` startup config error (message names the variable) · `3` OOM (`ExitOnOutOfMemoryError`) |

## 2. Compose (`compose.yaml`, replacing `docker-compose.yml`)

| Command | Brings up | Endpoints |
|---------|-----------|-----------|
| `docker compose up -d --wait` | `postgres`, `validator` | `http://127.0.0.1:8880` |
| `docker compose --profile cluster up -d --wait` | + `validator-2`, `lb` | `http://127.0.0.1:8881` (round-robin over 2 instances, one DB) |
| `docker compose --profile observability up -d` | + `prometheus`, `postgres-exporter` | `http://127.0.0.1:9090` |
| `docker compose --profile admin up -d` | + `pgadmin` | `http://127.0.0.1:5050` |

Invariants:
- Every published port binds to `127.0.0.1` by default. PostgreSQL isn't published; a
  git-ignored `compose.override.yaml` (merged automatically by Compose) can publish it on loopback.
- Every image is pinned to a version, and no `platform:` is hard-coded.
- Required secrets use `${VAR:?message}` (`PG_PASSWORD`; `PGADMIN_DEFAULT_PASSWORD` for the admin
  profile), so compose refuses to start without them.
- `.env` is git-ignored. `.env.example` is committed and complete.
- `validator` has `depends_on: postgres: condition: service_healthy`, `stop_grace_period: 30s`,
  and a memory limit from `FV_MEMORY_LIMIT` (default `4g`, so a 20 MB IG fits (SC-004); warmed validators for 4 FHIR versions take ~0.9 GB heap, and 1g ran out of heap while warming).
- The compose file runs standalone (image reference) or from a checkout (`build: .`).

`Makefile` targets: `up`, `up-cluster`, `up-obs`, `down` (keeps volumes), `clean` (removes
volumes, asks first), `logs`, `smoke` (runs `scripts/smoke.sh`), `run-postgres` (kept for IDE
runs, using a password instead of trust auth). Removed: `run-redis`, `run-ollama`, `stop-all`.

## 3. CI

| Workflow | Trigger | Gates (in order) | Publishes |
|----------|---------|------------------|-----------|
| `ci.yml` (new) | PR, push | `mvn -B verify` → `hadolint Dockerfile` → `docker compose config` → build amd64 image → `scripts/smoke.sh`; in parallel, build arm64 image → `scripts/startup-smoke.sh` | nothing |
| `docker-publish.yml` | push to `main`, release published | same gates → multi-arch build → `trivy` (fail on CRITICAL) | `sha-*` on main. Semver + `latest` on release |
| `maven-publish.yml` | release created | `mvn -B verify` | Jar to GitHub Packages. Release assets: jar, `compose.yaml`, `.env.example` |

`scripts/smoke.sh` contract: start the stack, wait until readiness is 200 (timeout 180 s), POST
the R4 fixture `src/test/resources/fhir/resources/dk/Patient.json` to `/R4/validate`, and assert
HTTP 200 and a JSON body with a boolean `valid`. Then send SIGTERM to `validator` and assert it
exits 0 within 30 s. Exits non-zero on any failure, printing container logs.

`scripts/startup-smoke.sh <image> <platform>` contract: start a native PostgreSQL and the image on the given
platform (emulated by QEMU in CI), wait until liveness is 200 and the schema migration has run (timeout 300 s),
then send SIGTERM and assert a graceful exit (0 or 143, with the drain log lines). Readiness and validation are not
checked: warming the validators for four FHIR versions under emulation takes too long for CI.
