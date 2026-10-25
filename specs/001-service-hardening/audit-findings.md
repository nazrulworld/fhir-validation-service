# Code Audit Findings: FHIR Validation Service (v0.1.3-snapshot)

**Date**: 2026-10-25
**Scope**: All of `src/main` (~3,600 lines), `openapi.yaml`, Flyway migrations, `Dockerfile`, config files
**Method**: Static read-through, checked against constitution v1.0.0. No code was run; items marked
*(likely)* depend on runtime behaviour that hasn't been reproduced yet.
**Companion to**: [spec.md](./spec.md). The spec states *what* must change; this file holds the
technical evidence for `/speckit-plan`.

Severity: **Critical** = wrong results or service-wide degradation in normal use ·
**High** = wrong results or outage in common scenarios · **Medium** = edge cases, contract
violations · **Low** = hygiene.

---

## 1. Bugs (correctness)

| ID | Sev | Finding | Evidence |
|----|-----|---------|----------|
| B1 | Critical | **Custom profiles are ignored on first use.** `fetchStructureDefinition` calls `.result()` on a Future that is still pending on a cache miss (DB query is async), so it returns `null`. The first validation after startup, after registration, or after cache expiry runs without the profile. Later requests work only because the async load fills the cache afterwards. | `CustomProfileValidationSupport.java:26`, `ProfileService.java:68-76` |
| B2 | High | **Profile changes do not propagate across instances.** Registration invalidates only the local cache. Other instances keep the old profile, or a cached "not found" marker (`emptyResource`), for up to 24 h. HAPI's `ValidationSupportChain` adds its own cache on top. | `ProfileService.java:71,107`, `CachingService.java:8` |
| B3 | High | **`include-ig` affects only the instance that received the request.** Other instances read `fhir_validator_logs` only at startup (`syncPreviousStateFromDatabase`), so replicas behind a load balancer return different results until restarted. | `ValidationApi.java:240-241`, `ValidationApi.java:64-88`, `FhirValidationService.java:201` |
| B4 | High | **`include-ig` changes the shared validator for every consumer, while requests are running.** One client including an IG changes results for all clients of that FHIR version. `includedIgPackages` (`ArrayList`) and the NPM support's internal maps are modified while worker threads read them. | `CustomNpmPackageValidationSupport.java:32,103,109-113`, `FhirValidationService.java:230-237` |
| B5 | High | **An IG version upgrade leaves the old version's resources loaded.** `findSimilarPackage` replaces only the list entry. Resources that exist only in the old version stay in the support, so validation runs against a mix of both versions. | `CustomNpmPackageValidationSupport.java:107-114` |
| B6 | High | **Dependency resolution is non-deterministic and ignores versions.** `resolvedDependencies` tracks names only, so whichever version is requested first wins. A dependency with no `#version` passes `null` and fails with "IG name and version cannot be null". This violates constitution Principle III (reproducible resolution). | `CustomNpmPackageValidationSupport.java:62-70`, `IgPackageService.java:124` |
| B7 | High | **"latest" means "most recently inserted", not the highest version.** `ORDER BY created_at DESC`, and the upsert never updates `created_at`. Uploading an older version after a newer one makes the older one "latest". | `PostgresPackageCacheManager.java:318` |
| B8 | High | **IG versions longer than 16 characters cannot be stored.** `ig_package_version VARCHAR(16)` rejects common pre-release versions (e.g. `4.0.0-ballot-2023`, `1.1.0-snapshot-01`), so the insert fails. | `V1__tables_are_initialized.sql:29` |
| B9 | High | **Registry fallback race.** `CompositeFuture.any` completes on the first *succeeded* future, and an HTTP 404 is mapped to a succeeded `null`. If the first registry to answer returns 404, the fetch reports "not found" even when another registry has the package. | `PostgresPackageCacheManager.java:239-246,291-313`, `AsyncBasePackageCacheManager.java:163-172` |
| B10 | High | **Registering an unknown IG by name hangs the request.** `registerIg` succeeds with `null`, then `npmPackage.name()` throws an NPE inside `onSuccess` and no response is ever sent. | `IgPackageApi.java:104-111`, `PostgresPackageCacheManager.java:53-55,80` |
| B11 | Medium | Download-by-URL response puts the package **name** in the `version` field. | `IgPackageApi.java:166` |
| B12 | Medium | **Common header values break validation.** The contract enumerates exact `Accept`/`Content-Type` values, so `*/*` (curl's and most clients' default) or `application/json; charset=utf-8` are rejected *(likely)*. When they do get through, an unknown value maps to `null`: content is parsed as XML, and `getAcceptedContentType().getMimeType()` throws an NPE, leaving the request hanging. | `openapi.yaml:61-81`, `ValidationRequestContext.java:85-100`, `ValidationApi.java:155` |
| B13 | Medium | `Accept: application/fhir+xml` returns a **JSON body labelled as XML**. | `ValidationApi.java:155-157` |
| B14 | Medium | **Custom validator identities are half-built.** The UUID path is rejected by the version check and the contract enum. The `api_clients` table queried for UUIDs exists in no migration. No validator is ever created for a non-default identity. | `ValidationApi.java:131`, `ValidationRequestContext.java:64-83`, `openapi.yaml:352` |
| B15 | Medium | **FHIR version handling is inconsistent.** STU3 profiles are accepted (`ProfileApi.java:35`) and the STU3 core package ships, but no STU3 validator is created (`ValidationApi.java:52-88`). `R6` passes `isValid`, and registering an R6 profile is **silently stored as R4** through `getOrDefault`. | `ProfileApi.java:85`, `SupportedFhirVersion.java:15` |
| B16 | Medium | **Server failures look like invalid resources.** Internal failures return HTTP 400 with `valid:false`, so clients cannot tell "your resource is invalid" from "the service is broken". Internal exception text (`err.toString()`, class names) is returned to clients, which violates Principle IV. | `ValidationApi.java:159-164`, `IgPackageApi.java:53,63,115,143,170,179` |
| B17 | Medium | **Profile cache keys collide.** Replacing every non-alphanumeric character with `_` maps different canonical URLs (`…/a-b` and `…/a.b`) to the same key. | `ProfileService.java:173` |
| B18 | Low | **Password handling in config.** The PG password prefix is written to logs, and a password shorter than 3 characters crashes startup (`substring` with a negative index). Default credentials are in source and properties files. | `PgConfig.java:22,42-47`, `application*.properties` |
| B19 | Low | **Latest-version lookup is fragile.** `getLatestVersion` uses `CompositeFuture.all`, so one unreachable registry fails the whole lookup. `isLaterVersion` throws on non-numeric segments (`current`, `ballot`) and orders pre-releases wrongly. | `AsyncBasePackageCacheManager.java:224,236-247` |
| B20 | Low | `include-ig` writes its response in two places (inner `onSuccess`/`onFailure` and outer `onComplete`), guarded by a meaningless `synchronized` on the response. | `ValidationApi.java:191-205,243-264` |
| B21 | Low | The "conformance report" lists file names with a hard-coded `"conformance":"basic"`, which is misleading. The dependency endpoint returns 200 with an empty list for an unknown package but 404 on a DB error, which is backwards. | `IgPackageService.java:132-188`, `IgPackageApi.java:47-54` |

## 2. Performance

| ID | Sev | Finding | Evidence |
|----|-----|---------|----------|
| P1 | Critical | **Each instance runs at most one validation at a time.** The deprecated `executeBlocking(Handler)` defaults to `ordered=true`. All requests share the single `MainVerticle` context (one instance, `MainVerticle.java:40`), so blocking tasks run one after another on that context. Throughput per instance stays at one core no matter how many cores it has. | `FhirValidationService.java:152` |
| P2 | High | **IG loading blocks the event loop.** `NpmPackage.fromPackage` (gunzip + untar) runs inside DB-result `map` callbacks, and `loadResourcesFromPackage` parses every JSON file (thousands for US Core) inside a `compose` callback. All of this is on the event loop: every request, including `/health`, stalls for seconds. This violates Principle I. | `PostgresPackageCacheManager.java:97,133`, `IgPackageService.java:71`, `CustomNpmPackageValidationSupport.java:53-58,99-105` |
| P3 | High | **No package cache in memory.** Every include or dependency load re-reads the full `BYTEA` blob from PostgreSQL and decompresses it again. Shared dependencies are loaded repeatedly. | `PostgresPackageCacheManager.java:85-103` |
| P4 | Medium | **Unbounded buffering.** Uploads are read fully into memory (`IgPackageApi.java:130`). Downloads by URL and from registries have **no size limit** (`IgPackageApi.java:159`, `PostgresPackageCacheManager.java:250-256`), so a large or malicious response can exhaust heap. | as listed |
| P5 | Medium | **Slow, opaque startup.** The HTTP port opens only after every validator is built and every saved IG is reloaded, so startup time grows with the number of IGs and there's no readiness signal. Validators aren't warmed, so the first request per version pays HAPI's lazy initialisation cost. | `MainVerticle.java:91-123`, `ValidationApi.java:47-98` |
| P6 | Low | Every registry is queried in parallel for every package. When several respond, the package is downloaded and upserted more than once. | `PostgresPackageCacheManager.java:226-314` |
| P7 | Low | Single-row profile `SELECT` wrapped in a transaction, which adds BEGIN/COMMIT round trips. Two `IgPackageService` instances, cache managers and `WebClient`s are created. | `ProfileService.java:150`, `MainVerticle.java:105`, `ValidationApi.java:62` |

## 3. Flow, architecture & security

| ID | Sev | Finding | Evidence |
|----|-----|---------|----------|
| F1 | High | **SSRF.** `downloadUrl` accepts any URL with no allowlist, so internal network endpoints can be reached. This violates the Security & Compliance rule "restricted to configured registries". | `IgPackageApi.java:83-93,154-181` |
| F2 | High | **No authentication or authorization**, including on the endpoints that change validation behaviour for everyone (upload/register IG, include-ig, register-profile). | `MainVerticle.java:58` (`setRequireSecurityHandlers(false)`) |
| F3 | High | **Uploaded archives aren't sanitised.** There are no path-traversal, entry-count or compression-ratio checks, which leaves the service open to zip bombs. Constitution violation. | `IgPackageService.java:69-75` |
| F4 | Medium | **The Docker image bundles PostgreSQL in the app container.** Each replica gets its own database, so shared state is lost and the image works against horizontal scaling (Principle II). | `Dockerfile` (postgresql-16 install, `PGDATA` volume) |
| F5 | Medium | **Health and observability gaps.** `/health` checks only the DB. There's no readiness endpoint, liveness always returns `UP`, and there are no metrics or correlation ids (known baseline deviation 4). | `HealthApi.java`, `HealthService.java` |
| F6 | Medium | **Two configuration systems.** System props/env/properties and vert.x `config.json` both exist. `config.json` keys (`timeout`, `retries`, `package.servers`) are never read, and registries are hard-coded to `PackageServer.defaultServers()`. | `ApplicationConfig.java`, `VerticleConfig.java`, `PostgresPackageCacheManager.java:41` |
| F7 | Medium | **No graceful shutdown.** There's no `stop()`: the pool and HTTP server aren't closed and in-flight requests are dropped on redeploy. | `MainVerticle.java` |
| F8 | Medium | Static `HashMap` registries aren't thread-safe and are invisible to other instances (known baseline deviation 1). | `FhirValidationService.java:43`, `CustomNpmPackageValidationSupport.java:29` |
| F9 | Low | **Three different error shapes:** `{valid,messages}`, `{error}` and `{status,error}`. | `ValidationApi`, `IgPackageApi`, `ProfileApi` |
| F10 | Low | Responses aren't `OperationOutcome` and carry no resource type or applied profiles. The `ValidationResponse` model is unused. | `FhirValidationService.java:247-259`, `ValidationResponse.java` |

## 3b. Docker build, compose & CI (added 2026-10-25 at `/speckit-plan`)

| ID | Sev | Finding | Evidence |
|----|-----|---------|----------|
| D1 | High | **Build likely breaks**: `COPY --chown=fhiruser:appuser`, but group `appuser` is never created; BuildKit fails on an unknown group *(likely)*. | `Dockerfile:67` |
| D2 | High | **Database bundled in the app image** (PostgreSQL 16, initdb in the entrypoint, `VOLUME PGDATA`). Every replica gets its own database, which breaks shared state (Principle II) and roughly doubles image size and attack surface. | `Dockerfile:29-60`, `docker/docker-entrypoint.sh:6-25` |
| D3 | High | **Database exposed with default credentials**: `pg_hba` allows `0.0.0.0/0`, the password is `password`, and compose publishes `5432:5432` on all host interfaces. pgAdmin runs with `admin/admin` and server mode off. | `docker-entrypoint.sh:15-17`, `Dockerfile:54`, `docker-compose.yml:8,57-59` |
| D4 | High | **Secrets committed**: `.env` is tracked in git and holds `PG_PASSWORD`. Constitution: no secrets in committed `.env`. | `.env:27`, `git ls-files` |
| D5 | Medium | **Signals and shutdown are broken**: bash is PID 1 and the JVM runs in the background. On SIGTERM the trap sends TERM, then stops Postgres immediately and `exit 0`s without waiting for the JVM, so in-flight requests and DB writes are cut off. If the JVM crashes, Postgres isn't stopped cleanly. | `docker-entrypoint.sh:30-59` |
| D6 | Medium | **The app port can't be configured**: the app reads only `http.port` from the baked `config.json` (8880). `FV_SERVER_PORT` changes `EXPOSE`, `HEALTHCHECK` and the compose mapping but not the listener, so overriding it breaks the health check. The compose health check also expands the *host* variable inside the container. | `Dockerfile:63,85-92`, `docker-compose.yml:7,18`, `MainVerticle.java:117` |
| D7 | Medium | **Hard-coded public DNS** (`-Dvertx.addressResolverOptions.servers=["1.1.1.1","8.8.4.4"]`) breaks resolution in corporate and Kubernetes networks *(and is probably not a recognised property)*. There's no `JAVA_OPTS` hook at runtime, and `ARG JAVA_OPTS` in the builder is unused. | `docker-entrypoint.sh:46-56`, `Dockerfile:7` |
| D8 | Medium | **The health check ties container health to the database**: a short DB blip marks the container unhealthy and causes restarts. `start-period: 40s` is shorter than startup with several IGs. There's no readiness/liveness split. | `Dockerfile:85-89`, `docker-compose.yml:17-22` |
| D9 | Medium | **Compose problems**: no separate `postgres` service (commented out); unpinned `prom/prometheus:latest` and `postgres-exporter`; exporter platform hard-coded to `linux/arm64`; `.env` forces `DOCKER_DEFAULT_PLATFORM=linux/arm64`, so amd64 hosts build under emulation; Prometheus scrapes `/metrics`, which doesn't exist; Redis variables left over; no resource limits even though `.env` defines them; no way to run several validator instances. | `docker-compose.yml`, `.env:10-15`, `docker/prometheus.yml:7` |
| D10 | Medium | **CI publishes untested images**: `docker-publish` pushes `latest` on every push to `main` without running `mvn verify`, a container smoke test or an image scan. The release workflow attaches `docker-compose.yml`, which uses `build: .`, so it's useless outside a checkout. The image name in `.env` (`fhir-validator`) differs from the one CI publishes (`github.repository`). | `.github/workflows/docker-publish.yml`, `maven-publish.yml`, `.env:6` |
| D11 | Low | Fragile first `RUN`: it calls `curl` and `gpg` before installing anything (works only if the base image happens to include them). `maven:3.8.6` builder image is old. `wait-for-services.sh` "continues anyway" after a timeout. | `Dockerfile:4,29-34`, `wait-for-services.sh:13` |
| D12 | Low | Makefile leftovers (`run-redis`, `run-ollama`, trust auth, `stop-all` kills *every* container on the host). | `Makefile` |

---

## 4. Candidate features (not in scope of spec 001; for roadmap)

| ID | Feature | Value |
|----|---------|-------|
| FT1 | FHIR-native `OperationOutcome` responses (JSON and XML), plus the standard `$validate` operation (`POST /{version}/{type}/$validate`, `Parameters`, `mode`, `profile`) | Lets any FHIR client or tool integrate without custom code |
| FT2 | Bundle-aware results (per-entry issues) and a batch endpoint for many resources | Large exchanges validated in one call |
| FT3 | Per-request IG selection (`?ig=name#version`) and named validation configurations (per client/tenant) | Removes cross-tenant contamination (B4) without global state |
| FT4 | Read/manage APIs: list IGs, profiles and active validator configs; remove an IG from a validator; delete a profile | Today it is write-only; nothing can be inspected or undone |
| FT5 | External terminology server (tx.fhir.org, Ontoserver, Snowstorm), configurable per deployment | SNOMED/LOINC/ICD codes are not validated with in-memory terminology alone |
| FT6 | Opt-in issue filtering (minimum severity, suppress message ids, best-practice warnings on/off) | Matches Principle III (explicit, opt-in filtering) |
| FT7 | Authentication (API keys / OAuth2 client credentials) with separate *validate* and *admin* roles | Closes F2 |
| FT8 | Cross-instance change propagation (PostgreSQL `LISTEN/NOTIFY` or versioned state) | Closes B2/B3 without new infrastructure |
| FT9 | Async validation jobs for very large bundles (submit → poll → fetch result) | Avoids HTTP timeouts on large bundles |
| FT10 | Result cache keyed by content hash + validator configuration version | Repeated validations of identical payloads become nearly free |
| FT11 | Configurable / private package registries and an offline (cache-only) mode | Air-gapped and enterprise deployments |
| FT12 | Prometheus metrics, correlation ids, readiness reflecting warm-up | Closes F5 / Principle VII |
| FT13 | Per-client rate limiting and quotas | Protects a shared fleet from noisy neighbours |
| FT14 | Profile-author tools: snapshot generation, FHIRPath evaluation, "explain this issue" | Widens the audience beyond validation consumers |
