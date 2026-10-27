# Contract: Configuration (environment variables)

There is a single mechanism: vertx-config, with this store order: classpath `config.json`
defaults → optional `FV_CONFIG_FILE` (JSON) → environment variables (R14). Every row below MUST
be honoured, and no other documented key may exist (FR-028). `.env.example` lists all of them.

| Env var | Config key | Default | Bounds / notes |
|---------|-----------|---------|----------------|
| `FV_PROFILE` | `profile` | `prod` | `dev` allows a missing DB password, an ephemeral port and insecure package sources (tests) |
| `FV_CONFIG_FILE` | *(none)* | *(unset)* | Optional JSON file of settings, below environment variables in precedence. It may not set `log.format` (startup fails): use `FV_LOG_FORMAT` |
| `FV_LOG_FORMAT` | `log.format` | `text` | `text` or `json` (one JSON object per line; `contextMap.requestId` carries the correlation id). Read by Log4j at startup, so environment variable or system property only |
| `FV_HTTP_PORT` | `http.port` | `8880` | 1–65535 (fixes D6); `0` = ephemeral port, only with `FV_PROFILE=dev` (tests) |
| `FV_HTTP_INSTANCES` | `http.instances` | min(cores, 8) | 1–64 |
| `FV_HTTP_MAX_BODY_MB` | `http.maxBodyMb` | `20` | 1–200. IG uploads (`/igs/upload`) are limited instead by max(this, `FV_PACKAGE_MAX_COMPRESSED_MB` + 1), so an oversized archive gets `package-too-large` |
| `FV_HTTP_REQUEST_TIMEOUT_MS` | `http.requestTimeoutMs` | `60000` | 1000–600000. A request not completed in time is answered 503 `not-ready` with `Retry-After` (FR-020) |
| `FV_CORS_ALLOWED_ORIGINS` | `http.cors.allowedOrigins` | *(empty → CORS off)* | Comma-separated exact origins. `*` is refused when `FV_PROFILE=prod` |
| `FV_VALIDATION_WORKERS` | `validation.workers` | cores | 1–256 |
| `FV_VALIDATION_MAX_PENDING` | `validation.maxPending` | 4 × workers | ≥ workers |
| `FV_VALIDATION_MAX_BUNDLE_ENTRIES` | `validation.maxBundleEntries` | `10000` | 1–1000000. A Bundle with more entries is refused with 413 `payload-too-large` (constitution VI) |
| `FV_VALIDATION_TIMEOUT_MS` | `validation.timeoutMs` | `30000` | Worker max execute time: exceeding it logs a blocked-thread warning (Vert.x cannot interrupt a running validation) |
| `FV_PACKAGE_WORKERS` | `packages.workers` | `2` | 1–16. Full validator builds use at most `workers - 1` of them (at least 1), so one worker stays free for profile parsing and refresh, upload parsing and DNS resolution while validators rebuild (FR-008, SC-002). With `1` nothing is reserved; use at least `2` in production |
| `FV_ADMIN_MAX_PENDING` | `admin.maxPending` | `4` | 1–64. Administrative operations (upload, register, include-ig, register-profile) in progress per instance, separate from `FV_VALIDATION_MAX_PENDING`. Beyond it: 503 `overloaded` with `Retry-After`. Background syncs are not counted |
| `FV_PACKAGE_CACHE_MAX_MB` | `packages.cacheMaxMb` | `256` | 0 disables the cache |
| `FV_PACKAGE_SOURCES` | `packages.sources` | `packages.fhir.org,packages2.fhir.org` | Registries and approved download hosts. Each entry is a host (registry `https://<host>`) or a base URL (`scheme://host[:port]`); the host part is the allowlist for registry and `downloadUrl` fetches |
| `FV_PACKAGE_REGISTRY_ENABLED` | `packages.registryEnabled` | `true` | `false` = offline (cache-only) |
| `FV_PACKAGE_ALLOW_INSECURE_SOURCES` | `packages.allowInsecureSources` | `false` | **Dev profile only** (startup fails otherwise): allows http and private/loopback package sources, for tests and local registries. The allowlist still applies |
| `FV_PACKAGE_FETCH_TIMEOUT_MS` | `packages.fetchTimeoutMs` | `30000` | Bounds each step of a download: name resolution (including any wait for a free package worker) and the HTTP request |
| `FV_PACKAGE_MAX_COMPRESSED_MB` | `packages.maxCompressedMb` | `50` | |
| `FV_PACKAGE_MAX_EXPANDED_MB` | `packages.maxExpandedMb` | `500` | |
| `FV_PACKAGE_MAX_ENTRIES` | `packages.maxEntries` | `20000` | |
| `FV_PACKAGE_MAX_RATIO` | `packages.maxRatio` | `100` | |
| `FV_CONFIG_POLL_INTERVAL_MS` | `sync.pollIntervalMs` | `5000` | 1000–60000 (SC-002 needs ≤ 10000) |
| `FV_AUTH_ANONYMOUS_VALIDATE` | `auth.anonymousValidate` | `true` | |
| `FV_ADMIN_API_KEY` | `auth.bootstrapAdminKey` | *(unset → admin operations refused)* | 32–128 chars. Secret: supply via env/secret store only |
| `FV_AUTH_CACHE_TTL_MS` | `auth.cacheTtlMs` | `30000` | |
| `FV_READINESS_DB_STALENESS_MS` | `health.dbStalenessMs` | `10000` | |
| `FV_SHUTDOWN_PRE_DRAIN_MS` | `shutdown.preDrainMs` | `5000` | |
| `FV_SHUTDOWN_GRACE_MS` | `shutdown.graceMs` | `20000` | Must be less than the orchestrator's stop grace |
| `FV_PG_HOST` (alias `PG_HOST`) | `pg.host` | `localhost` | |
| `FV_PG_PORT` (alias `PG_PORT`) | `pg.port` | `5432` ⚠ (was 54329) | |
| `FV_PG_DATABASE` (alias `PG_DATABASE`) | `pg.database` | `fhir_validator` | |
| `FV_PG_USER` (alias `PG_USER`) | `pg.user` | `postgres` | |
| `FV_PG_PASSWORD` (alias `PG_PASSWORD`) | `pg.password` | *(none)* | **Required** unless `FV_PROFILE=dev`. Never logged (B18) |
| `FV_PG_POOL_SIZE` (alias `PG_POOL_SIZE`) | `pg.poolSize` | `15` | 1–200 |
| `FV_PG_SSL_MODE` | `pg.sslMode` | `prefer` | `disable` \| `prefer` \| `require` \| `verify-full` |
| `JAVA_OPTS` | *(JVM; container only)* | `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError` | |
| `FV_MEMORY_LIMIT` | *(compose only: container memory)* | `2g` | The JVM takes 75% as heap. Size it as: heap ≈ 0.9 GB (four warmed core validators) + 64 MB + 6 × the expanded size of the largest IG set of one version; container = heap / 0.75. The default fits IG sets up to about 100 MB expanded (roughly a 10 MB archive). A 20 MB IG such as `hl7.fhir.r4.examples#4.0.1` (180 MB expanded) needs **3g** or more (SC-004 measured at 4g). Include-ig refuses a set that does not fit with 413 `package-too-large` (T131) |

**Removed** ⚠: `application.properties`, `application-production.properties`,
`application-docker.properties`, the `-Dvertx.environment`, `-Dapplication.config.path` and
`-Dvertx.config.path` system properties (replaced by `FV_CONFIG_FILE`), the unused `config.json`
keys `http.timeout`, `http.retries`, `http.backoff` and `package.servers.*`, and `swagger.ui.version`.

Invalid values (out of bounds, or not parseable) fail startup with one clear error line that names
the variable. They're never silently replaced by the default.
