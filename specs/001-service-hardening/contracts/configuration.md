# Contract: Configuration (environment variables)

There is a single mechanism: vertx-config, with this store order: classpath `config.json`
defaults → optional `FV_CONFIG_FILE` (JSON) → environment variables (R14). Every row below MUST
be honoured, and no other documented key may exist (FR-028). `.env.example` lists all of them.

| Env var | Config key | Default | Bounds / notes |
|---------|-----------|---------|----------------|
| `FV_PROFILE` | `profile` | `prod` | `dev` allows a missing DB password and enables verbose logs |
| `FV_HTTP_PORT` | `http.port` | `8880` | 1–65535 (fixes D6) |
| `FV_HTTP_INSTANCES` | `http.instances` | min(cores, 8) | 1–64 |
| `FV_HTTP_MAX_BODY_MB` | `http.maxBodyMb` | `20` | 1–200 |
| `FV_CORS_ALLOWED_ORIGINS` | `http.cors.allowedOrigins` | *(empty → CORS off)* | Comma-separated exact origins. `*` is refused when `FV_PROFILE=prod` |
| `FV_VALIDATION_WORKERS` | `validation.workers` | cores | 1–256 |
| `FV_VALIDATION_MAX_PENDING` | `validation.maxPending` | 4 × workers | ≥ workers |
| `FV_VALIDATION_TIMEOUT_MS` | `validation.timeoutMs` | `30000` | Worker max execute time: exceeding it logs a blocked-thread warning (Vert.x cannot interrupt a running validation) |
| `FV_PACKAGE_WORKERS` | `packages.workers` | `2` | 1–16 |
| `FV_PACKAGE_CACHE_MAX_MB` | `packages.cacheMaxMb` | `256` | 0 disables the cache |
| `FV_PACKAGE_SOURCES` | `packages.sources` | `packages.fhir.org,packages2.fhir.org` | Hosts allowed for registry and `downloadUrl` fetches |
| `FV_PACKAGE_REGISTRY_ENABLED` | `packages.registryEnabled` | `true` | `false` = offline (cache-only) |
| `FV_PACKAGE_ALLOW_INSECURE_SOURCES` | `packages.allowInsecureSources` | `false` | **Dev profile only** (startup fails otherwise): allows http and private/loopback package sources, for tests and local registries. The allowlist still applies |
| `FV_PACKAGE_FETCH_TIMEOUT_MS` | `packages.fetchTimeoutMs` | `30000` | |
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

**Removed** ⚠: `application.properties`, `application-production.properties`,
`application-docker.properties`, the `-Dvertx.environment`, `-Dapplication.config.path` and
`-Dvertx.config.path` system properties (replaced by `FV_CONFIG_FILE`), the unused `config.json`
keys `http.timeout`, `http.retries`, `http.backoff` and `package.servers.*`, and `swagger.ui.version`.

Invalid values (out of bounds, or not parseable) fail startup with one clear error line that names
the variable. They're never silently replaced by the default.
