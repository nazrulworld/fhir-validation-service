# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `FV_ADMIN_MAX_PENDING` (default 4): administrative operations (upload, register, include-ig, register-profile) have
  their own per-instance admission limit, separate from validation. Beyond it they get 503 `overloaded` with
  `Retry-After` instead of queueing without limit for the package workers. New metrics `fv_admin_pending` and
  `fv_admin_max_pending`.

- CI: a startup smoke test of the arm64 image under QEMU (liveness, schema migration, graceful stop), so both
  published platforms are smoke-tested on every PR as the container contract states.

### Changed

- Image build: the Maven and dependency download step is retried up to 3 times, so a transient network error on
  repo.maven.apache.org no longer fails the build.

## [1.0.0] - 2026-10-26

Upgrading from 0.1.x moves data out of the container: follow [docs/upgrade-1.0.md](docs/upgrade-1.0.md). Running
0.1.x and 1.0.0 against the same database at the same time is not supported.

### Breaking

| Area | 0.1.x | 1.0.0 |
|------|-------|-------|
| Container | Bundled PostgreSQL 16 in the app image | Service only; use the `postgres` service of `compose.yaml` or your own database |
| Compose | `docker-compose.yml`, database published on `0.0.0.0:5432` | `compose.yaml` with profiles (`cluster`, `observability`, `admin`); everything on `127.0.0.1`, database not published |
| Administrative calls | Open to anyone | Need `Authorization: Bearer <ADMIN key>` (`FV_ADMIN_API_KEY`); refused if no key is configured |
| Errors | Mixed shapes (`{valid,messages}`, `{error}`, `{status,error}`), internal messages | RFC 9457 `application/problem+json` everywhere, with `correlationId` |
| Server failures on validate | 400 with `valid:false` | 500 problem |
| Unparseable body on validate | 200 with a FATAL message | 400 problem `invalid-resource` with `issues` |
| Unknown package | 400 (include-ig), hanging request (register), 200 empty list (dependencies) | 404 problem `package-not-found` |
| `Accept: application/fhir+xml` / `application/xml` | JSON labelled as XML | FHIR `OperationOutcome` XML |
| Version path | `R6` accepted (profiles stored as R4); UUID "client validators" half-implemented | STU3, R4, R4B, R5 only (case-insensitive); everything else 400 |
| `/igs/{name}/{version}/conformance` | Fake `"conformance":"basic"` verdict | Deprecated; returns the package file listing |
| Configuration | `application*.properties`, `-Dvertx.environment`, `-Dapplication.config.path`, `-Dvertx.config.path` | Environment variables (`FV_*`, legacy `PG_*` aliases) plus optional `FV_CONFIG_FILE` |
| Default database port | 54329 | 5432 |
| Database password | Default `Test1234` / `password` | Required (`FV_PG_PASSWORD`) unless `FV_PROFILE=dev` |
| CORS | Any origin | Only `FV_CORS_ALLOWED_ORIGINS` |
| Image tags | `latest` on every push to main | `latest` and semver on releases only; `sha-<commit>` for main |
| Memory | 1 GB container limit in compose | 2 GB recommended (warmed validators for four FHIR versions) |

### Added

- `GET /health/readiness` (database, validator state, draining) and `GET /metrics` (Prometheus).
- STU3 validation.
- FHIR `OperationOutcome` responses for `application/fhir+json`, `application/fhir+xml` and `application/xml`.
- `fhirVersion`, `appliedProfiles` in the JSON validation response; `revision`, `included`, `resolved`, `warnings` in
  the include-ig response; `revision` in the register-profile response.
- API keys (`api_keys` table, roles ADMIN and VALIDATE); optional authenticated validation (`FV_AUTH_ANONYMOUS_VALIDATE=false`).
- Cross-instance propagation of IG and profile changes (revision counter, `LISTEN/NOTIFY`, 5 s poll fallback).
- Graceful shutdown (readiness 503 → pre-drain → in-flight requests finish → exit).
- `X-Request-Id` correlation ids, echoed and attached to every log line written while serving a request.
- Compose `cluster` profile (two validators behind nginx), `observability` and `admin` profiles.
- CI: tests, Dockerfile lint, container smoke test, arm64 build, image scan before publishing; upgrade rehearsal job.
- k6 load-test scripts (`perf/`).

### Fixed

- B1: custom profiles were skipped on the first validation after startup or registration.
- B2, B3: profile and IG changes did not reach other instances (up to 24 h, or until restart).
- B4, B5: including an IG mutated the shared validator under concurrent use and left resources of replaced versions behind.
- B6: dependency resolution depended on load order; dependencies without a version failed.
- B7: "latest" meant "most recently uploaded"; now the highest released version.
- B8: package versions longer than 16 characters could not be stored (now 64).
- B9, B19: a fast "not found" from one registry hid a package available on another; one unreachable registry broke
  latest-version lookup.
- B10, B12: requests could hang (unknown package; `Accept: */*` or media-type parameters).
- B11: download-by-URL reported the package name as its version.
- B13: XML requested, JSON returned.
- B15: profiles posted for R6 were stored as R4; STU3 profiles were accepted but never used.
- B16: service failures were reported as invalid resources; internal exception text was returned to clients.
- B17: different profile URLs could share one cache entry.
- B18: password fragments were logged; a short password crashed startup.
- B20, B21: duplicate response writing in include-ig; misleading conformance report and dependency status codes.
- P1: validations ran one at a time per instance; now in parallel up to `FV_VALIDATION_WORKERS`, bounded by
  `FV_VALIDATION_MAX_PENDING` (503 beyond).
- P2, P3: IG decompression and parsing blocked the event loop; packages were re-read for every rebuild (now cached).
- P4: uploads and downloads were unbounded; archives are now limited and checked before unpacking.
- P5: validators are warmed at build time; HTTP (liveness) starts before the validators are ready.
- F1: download-by-URL allowed requests to any address (SSRF); now approved https hosts with public addresses only.
- F3: uploaded archives are checked for size, entry count, expansion ratio and path traversal.
- F4, D1–D12: container, compose and CI defects (unbuildable image group, bundled database, committed `.env`, signal
  handling, unconfigurable port, hard-coded DNS, health checks, unpinned images, untested publishing).
- Earlier releases stored profiles and package metadata as JSON strings; migration V3 repairs them.
- Validations could fail intermittently (500, or a validator rebuild failing and the version answering 503) while
  another thread used a new custom or IG profile for the first time. HL7 core's snapshot generator modifies the base
  definition it works on, and HAPI shares the core definitions JVM-wide, so lazily generated snapshots raced with
  concurrent validations (HAPI 8.4 and later). Missing snapshots are now generated up front, against private copies,
  before profiles and IG packages become visible to validation. Reported upstream as
  [hapifhir/hapi-fhir#8756](https://github.com/hapifhir/hapi-fhir/issues/8756).
- Registering a profile or including an IG could complete before the change was applied on the answering instance,
  when a notification-driven sync was applying it at the same time. Syncs of one FHIR version now run one after
  another, so the call returns only once its change is in effect locally.

### Security

- Dependency upgrades closing 12 CRITICAL vulnerabilities reported by Trivy in the image: HAPI FHIR 8.2.0 → 8.12.1
  (HL7 FHIR core 6.5.18 → 6.9.12: CVE-2026-33180, CVE-2026-34361, CVE-2026-55471) and Vert.x 4.5.14 → 4.5.35
  (Netty 4.1.118 → 4.1.139: CVE-2026-75595). The Netty native transports now follow Vert.x's Netty version, the
  SCRAM client moves to `com.ongres.scram:scram-client` 3.2 (required by the newer PostgreSQL client), and the
  obsolete `org.codehaus.woodstox:woodstox-core-asl` pin, which shadowed the StAX API HAPI needs, is removed.
- Dependency upgrades closing the remaining 10 HIGH vulnerabilities: Jackson 2.17.1 → 2.21.7 (CVE-2026-54512,
  CVE-2026-54513, CVE-2026-68494, CVE-2026-68497, CVE-2026-89407, CVE-2026-89425, CVE-2026-91776, CVE-2026-91777),
  now managed through `jackson-bom` so every Jackson module shares one version, and the SCRAM client 3.2 → 3.3
  (CVE-2026-53712). The image scans with no HIGH or CRITICAL findings.

### Removed

- The bundled PostgreSQL server, `docker/docker-entrypoint.sh`, `docker/init-scripts/`, `docker/postgres-init/init-database.sh`.
- `application.properties`, `application-production.properties`, `application-docker.properties`, the vertx-config dependency.
- Per-client validator identities (`ValidatorIdentity`, the `api_clients` lookup), `IGPackageIdentity`, `IgPackageName`,
  `CachingService`, `AsyncBasePackageCacheManager`, `IAsyncPackageCacheManager`, `MainVerticle` (now `Bootstrap` + `HttpVerticle`).
- Makefile targets `run-redis`, `run-ollama`, `stop-all`.
