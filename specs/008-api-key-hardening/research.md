# Research: API Key Hardening, Key-Only Validation, Usage Accounting and Well-Known Organisations

**Feature**: `008-api-key-hardening` | **Date**: 2026-11-11 | **Spec**: [spec.md](./spec.md)

The Technical Context had no open `NEEDS CLARIFICATION` items: `var/api_key_hardening_plan.md` fixed the main
decisions. This file records those decisions and resolves the details the outline left open, each checked against
the current code.

---

## R1. Release number and the constitution's versioning rule

- **Decision**: ship as **4.1.0**, using the Principle IV exception added in constitution 1.3.0: the break is recorded
  under Complexity Tracking in `plan.md` with the maintainer's approval, and the release carries a **Breaking** section
  in `CHANGELOG.md` and a new `docs/upgrade-4.1.md`.
- **Rationale**: the user decided this explicitly. 5.0 is reserved for a larger, planned set of breaks. The breaks
  here are an auth requirement (clients add one header), one removed setting, and a stop-the-world database
  upgrade. All three are announced in the release notes.
- **Alternatives considered**:
  - Release 5.0.0 now. Rejected by the user.
  - Ship the breaking parts behind a flag in 4.x. Rejected because keeping anonymous validation as an option
    defeats US1.

## R2. Rejecting malformed tokens (FR-001)

- **Decision**: `ApiKeyAuthHandler.authorise` calls `ApiKeyRepository.isWellFormed(key)` right after extracting the
  bearer value. If the value is malformed, it answers 401 at once: no hashing, no cache access, no lookup.
  `findActive` keeps its own check as a second guard.
- **Rationale**: today, each distinct junk token costs a SHA-256 hash, a cache miss and a database query, and
  then takes a cache slot. The key format (`[A-Za-z0-9_-]{32,128}`) already exists, so the check is free.
- **Alternatives considered**: a rate limit per address. That is out of scope and belongs to the proxy.

## R3. Client address resolution (FR-003, FR-004)

- **Decision**: a new `core/security/ClientAddress` with a root handler mounted right after `RequestIdHandler`
  and before authorisation. It resolves the address once per request and stores it in the routing context
  (`ClientAddress.KEY`). Rules:
  1. Let `peer = request.remoteAddress().hostAddress()`. If no trusted proxies are configured, or the peer isn't in
     one, the client is the peer and all headers are ignored.
  2. Otherwise read the chain from `Forwarded` (RFC 7239, the `for=` parameters in order). Only when `Forwarded` is
     absent, read it from `X-Forwarded-For`. Multiple header lines are joined in order. Only one of the two headers
     is ever used.
  3. Append the peer and walk the chain from right to left. Skip addresses inside a trusted proxy network. The
     first address that is not trusted is the client.
  4. An entry that can't be parsed (such as `unknown`, an obfuscated `_id`, or garbage) stops the walk. The client
     is then the last trusted hop seen, so a forged entry can never be chosen.
  5. If every hop is trusted, the left-most address is the client.
  6. Ports and IPv6 brackets are stripped. IPv4-mapped IPv6 addresses are treated as IPv4.
- **Rationale**: Vert.x `Router.allowForward(...)` trusts the headers from any peer, so a client could spoof them.
  Right-most-untrusted is the standard safe rule. Resolving once means the allowlist, the audit and usage
  accounting can't disagree (FR-004).
- **Alternatives considered**:
  - Vert.x `allowForward`. It trusts every peer.
  - Left-most XFF. A client can spoof it.
  - Reading both headers. Their precedence would be ambiguous.

## R4. CIDR matching

- **Decision**: a small hand-written `core/security/Cidr` record (network bytes plus prefix length) with
  `parse(String)` and `contains(InetAddress)`, and a `CidrList` that holds several of them.
  - Only literals are parsed, never names: the input must consist of `[0-9A-Fa-f:.]` before
    `InetAddress.getByName` is called, so DNS is never used.
  - IPv4 and IPv6 are matched byte-wise. IPv4-mapped v6 is normalised to v4. Prefixes `/0` to `/32` (v4) and `/0`
    to `/128` (v6) are accepted. A bare address means a single-host network.
  - Invalid entries throw, and `ConfigLoader` turns that into a startup error naming the entry (FR-005).
- **Rationale**: about 60 lines of code, with no new dependency (Principle VIII). `NetworkGuard` (spec 007) checks
  only whether an address is public, which is a different job.
- **Alternatives considered**: Apache Commons Net `SubnetUtils` (IPv4 only); Guava `InetAddresses` (would add a
  dependency for little gain).

## R5. Admin network allowlist (FR-002)

- **Decision**: in `rootHandler`, when `isAdministrative(ctx)` holds and an allowlist is configured, a client
  address outside it gets **403 `forbidden`** ("Administrative operations are not allowed from this network"). The
  check runs before the bearer value is read, so it costs no lookup and its result doesn't depend on the key. The
  CLI is not affected, because it talks to the database directly.
- **Rationale**: this matches the outline. 403 rather than 404, because the path exists and the service is not
  trying to hide that.
- **Alternatives considered**: per-key IP binding (dropped in the outline).

## R6. Key expiry (FR-006)

- **Decision**:
  - `api_keys.expires_at TIMESTAMPTZ NULL`.
  - `findActive` adds `AND (k.expires_at IS NULL OR k.expires_at > now())` and returns `expiresAt` in `KeyInfo`.
  - A cached positive result expires at `min(now + ttl, expiresAt)`, so a cached key can't outlive its expiry.
  - Negative results are cached as before.
  - The CLI's `keys create --expires <ISO-8601 instant>` is rejected when the instant is in the past.
- **Rationale**: the database decides at lookup time, and the cache bound closes the remaining gap (SC-005). The
  server and database clocks are assumed to be in sync (NTP), as for the existing `revoked_at`.
- **Alternatives considered**: checking expiry only in Java. Rejected because the database filter also protects
  the CLI and any future paths.

## R7. Last-used address (FR-007)

- **Decision**:
  - `api_keys.last_used_ip INET NULL`.
  - `touch(id, ip)` now writes when the 60 s interval has passed **or** the address differs from the last one this
    instance wrote for the key, so a move to a new place shows up at once.
  - The write is `UPDATE … SET last_used_at = now(), last_used_ip = $2::inet`. As before, it is fire-and-forget.
  - The in-memory map is bounded like the auth cache (LRU, 10 000 entries) to satisfy Principle VI. The current
    unbounded `ConcurrentHashMap` is a latent deviation and is fixed here.
- **Rationale**: an attacker using a stolen key from a new place should be visible immediately, not up to a minute
  later.
- **Alternatives considered**: writing on every request. Rejected because it adds one database write per
  validation.

## R8. Key-only validation and the removed setting (FR-008 to FR-010)

- **Decision**:
  - `ApiKeyAuthHandler` loses `anonymousValidate`, and `authorise(ctx, anonymousAllowed, …)` loses the flag.
    `VALIDATE_PATHS` → `authorise(ctx, VALIDATE, ADMIN)`.
  - `ConfigLoader`'s `DEPRECATED` list becomes a map from name to reason. `FV_AUTH_ANONYMOUS_VALIDATE` gets the
    reason "removed in 4.1.0: validation always requires a VALIDATE or ADMIN key". The two existing 4.0 entries
    keep their current message.
  - `ApplicationConfig.anonymousValidate()` is removed.
  - `Caller.ANONYMOUS` stays as the default of `ApiKeyAuthHandler.caller(ctx)` for open paths.
- **Rationale**: this reuses the deprecation mechanism added in 4.0, and the warning is tested the same way
  (`ConfigLoaderTest`).

## R9. OpenAPI security declaration

- **Decision**:
  - Add `security: [{apiKey: []}]` to **every** operation that requires a key: validate, the two package reads, and
    the administrative operations, which today describe the requirement only in prose.
  - Register no OpenAPI security handler. `HttpVerticle` already builds the router with
    `RouterBuilderOptions.setRequireSecurityHandlers(false)`, and enforcement stays in
    `ApiKeyAuthHandler.rootHandler()`, which runs before contract validation (contract validation must not examine
    input from unauthenticated callers).
  - Add 401 responses wherever they are missing; validate and reads already list 401.
  - Rewrite the scheme description.
- **Rationale**: Principle IV makes the contract the source of truth, and generated clients then send the header.
- **Check**: an IT confirms that the router still builds with the declarations, and that a 401 comes from the root
  handler (problem body with `WWW-Authenticate: Bearer`), not from the contract layer.
- **Alternatives considered**: registering a pass-through `apiKey` handler and turning the option on. Rejected:
  it adds a no-op handler that would look like enforcement, while the real check must stay at router level.

## R10. Measuring a validation cycle (FR-011 to FR-013)

- **Decision**: `core/usage/UsageMeter`, one per request.
  - **Created** at the top of `ValidationApi.handleDoValidation` and stored in the routing context. It holds the
    caller (`apiKeyId`, `apiKeyName`, `orgId` from `Caller`), the request id, the client address, the FHIR version
    path parameter, the content type and the body size.
  - **Passed** to a new `FhirValidationService.validate(…, UsageMeter meter, finisher)` overload; the old overloads
    delegate with `UsageMeter.NONE`. Marks:
    - `admitted` when the admission supplier runs;
    - `contextReady` after `registry.snapshot(key)` completes;
    - `workerStart` and `workerEnd` inside the blocking task.
  - **Phases**:
    - `queueMs` = (admission start − entry) + (worker start − context ready);
    - `contextMs` = context ready − admitted;
    - `validateMs` = worker end − worker start (this includes rendering via `finisher`, which runs on the same
      worker);
    - `totalMs` = (the later of response end and work end) − entry, so it is never less than `validateMs`, also
      after a timeout.
  - **CPU and allocation**: inside the blocking task, read `ThreadMXBean.getCurrentThreadCpuTime()` and
    `com.sun.management.ThreadMXBean.getThreadAllocatedBytes(Thread.currentThread().getId())` before and after. Each
    value is -1 when the counter isn't supported or enabled (checked once at start-up). This is the only per-request
    measure that is honest about shared heap and pooled threads. CPU and allocation are **always** measured, because
    they feed the metrics (FR-020); the sink setting only controls whether a record is built (metrics-only mode when
    the sink is `none`).
  - **Outcome**: set from the validation future using `FhirValidationService.failureOutcome` and the
    `ServiceMetrics.OUTCOME_*` constants. An admission refusal (503 overloaded) keeps today's mapping, `error`; no new
    metric outcome is added. Usage-only outcomes, in order of precedence:
    - `timeout`: the request timeout fired. This wins over the service's outcome, even if the work later succeeds;
    - `aborted`: the connection reset before the response ended. This also wins over a later service outcome;
    - then the service's outcome;
    - `rejected`: the handler answered 4xx before the service ran (415, 406, empty body, bad `ig`).
  - **Issue counts** by severity (fatal, error, warning, information) come from the `Outcome` result. They are
    captured in the blocking task, so the event loop never walks the issue list.
  - **Exactly-once emission**: the meter emits when both "the response is done" (`ctx.addEndHandler`, which fires
    on end or reset) and "the work is done" (validate future complete, or never started) hold. An atomic latch of
    two guarantees exactly one record. So a timed-out request whose worker is still running is recorded with its
    real CPU time once the worker finishes.
  - Requests refused with 401/403 by the auth handler never reach the handler and produce no record. They have no
    key to attribute, and auth failures are already counted by the HTTP metrics.
- **Rationale**: FR-011 needs every request that reaches validation, including errors and timeouts, and FR-013
  needs the caller without an extra lookup. `Caller` gains `apiKeyId` and `apiKeyName`, filled by
  `KeyInfo.caller()`.
- **Alternatives considered**:
  - Process-wide CPU or memory sampling. It can't be attributed to a request.
  - Measuring in a Vert.x interceptor. It can't see the worker thread.

## R11. Metrics (FR-020)

- **Decision**:
  - Keep the existing `fv.validation` Timer (tag `version`) as the duration metric, instead of adding a second
    timer named `fv.validation.duration`.
  - Add two DistributionSummaries tagged `version` and `outcome`: `fv.validation.cpu` (base unit seconds) and
    `fv.validation.allocated` (base unit bytes).
  - Add counters `fv.usage.dropped` (tag `reason` = `queue_full` | `write_failed`) and `fv.usage.written`.
  - There are no key or organisation tags.
- **Rationale**: a second duration timer would duplicate data and dashboards. Tag names follow the existing
  `version` convention rather than the outline's `fhir_version`.

## R12. Usage writers (FR-014 to FR-019)

- **Decision**:
  - The interface is `UsageWriter { void write(UsageRecord); Future<Void> close(); }`. `FV_USAGE_SINK` takes a
    comma-separated set of `none`, `log` and `db`; anything else fails startup. `none` combined with others is an
    error.
  - **`LogUsageWriter`**: Log4j2 logger `nzi.fhir.validator.usage`, one `JsonObject.encode()` line per record at
    INFO. It is declared in `log4j2.xml` with `additivity="false"` and its own `AppenderRef` (Console by default),
    so operators can point it at a file or a separate stream without touching the application log.
  - **`DbUsageWriter`**:
    - An `ArrayBlockingQueue` (`FV_USAGE_QUEUE_SIZE`, 100 to 1 000 000, default 10 000). `write` calls `offer`,
      never `put`; on overflow it counts `queue_full`.
    - A periodic timer (`FV_USAGE_FLUSH_MS`, 100 to 60 000, default 1 000) triggers a flush, and so does reaching
      `FV_USAGE_BATCH_SIZE` (10 to 5 000, default 500).
    - One flush runs at a time (`AtomicBoolean`). It drains up to a batch and sends one
      `preparedQuery(INSERT … VALUES ($1…$n)).executeBatch(tuples)`, which the PG client pipelines in a single round
      trip.
    - On failure: WARN (at most once per 60 s), count `write_failed` for the batch size, and drop the batch with no
      retry.
    - `close()` cancels the timer and flushes until the queue is empty or 5 s pass.
  - **`CompositeUsageWriter`** fans out to several writers. **`NoopUsageWriter`** does nothing.
  - **Wiring**: `ServiceRegistry` builds the writer. `Bootstrap.stop()` calls `services.usage().close()` after the
    drain and before the pool closes.
- **Rationale**: the request path costs one `offer` (Principle I, SC-007). Memory is bounded by the queue
  (Principle VI). Losing usage records under failure is acceptable; blocking validation is not.
- **Alternatives considered**:
  - An unbounded queue. Forbidden by Principle VI.
  - Retry with backoff. It grows memory under a prolonged outage.
  - A separate connection pool. Not needed, because a flush holds one connection briefly.

## R13. Re-keying the system organisation (FR-024)

- **Decision**: `V11__well_known_organisations.sql` runs in Flyway's single transaction:
  1. `ALTER TABLE organisations DROP CONSTRAINT organisations_system_id_chk`.
  2. Drop the three owner FKs: `api_keys_owner_fkey`, `fhir_implementation_guides_owner_fkey` and
     `fhir_validator_snapshots_owner_fkey`. These are the PostgreSQL default names from V8; the migration asserts
     they exist.
  3. `UPDATE organisations SET id = '…-100000000001' WHERE id = '…-000000000001'`.
  4. `UPDATE` the `owner` column of the three tables from the old id to the new one.
  5. Re-add the three FKs exactly as in V8 (`ON DELETE RESTRICT`).
  6. `ALTER COLUMN owner SET DEFAULT '…-100000000001'` on the three tables.
  7. Re-add `organisations_system_id_chk` with the new id.
  8. Seed the system org (if it's missing) and the trial org with `INSERT … ON CONFLICT DO NOTHING`.
  9. Rename the bootstrap key (see R15).
- **Rationale**: dropping and re-adding the FKs is equivalent to the outline's temporary `ON UPDATE CASCADE`. It is
  clearer and leaves the final constraints identical to V8. Nothing else stores the system id. Checked:
  `ConfigRevisionRepository` and `PackageCatalogView` build their SQL from `Organisations.SYSTEM_ID`, and V9 has no
  owner column.
- **Rolling upgrade**: 4.0 code hard-codes the old id in `Organisations.SYSTEM_ID`, so all instances must stop
  before the migration runs. This is documented in `docs/upgrade-4.1.md` and the changelog. The constitution's gate
  ("migrations apply cleanly on top of the previous release's schema") still holds.

## R14. Well-known organisations at startup (FR-025, FR-028)

- **Decision**: `Bootstrap.ensureWellKnownOrganisations` runs right after migration and before
  `installSystemOrganisation`. It runs `INSERT … (id, name_short, name_long, type) … ON CONFLICT (id) DO NOTHING`
  for both orgs.
  - A unique violation on the short-name index (SQLState `23505`, `organisations_name_short_uq`) for the trial row
    becomes a `StartupException`: "an organisation named 'trial' already exists with id …; rename it before
    upgrading".
  - Existing rows are never changed. The `FV_SYSTEM_ORG_*` overrides are still applied afterwards by
    `installSystemOrganisation`.
- **Fatal startup**: today a warm-up failure is only logged, and the instance stays unready. A new
  `StartupException` (well-known org clash, malformed `FV_VALIDATOR_API_KEY`, a reserved-name or hash conflict while
  seeding keys) makes `Bootstrap.main` log ERROR and exit 1. Tests see `warmUp()` fail with that type. Other warm-up
  failures keep today's behaviour.
- **Rationale**: "fail loudly" (US2-5) means an operator notices at once, not when the readiness probe times out.

## R15. Seeded keys: trial validator and system administrator (FR-026, FR-027)

- **Decision**:
  - `ApiKeyRepository.upsertWellKnown(UUID id, String name, String key, Role role, UUID owner, Instant expiresAt)`:
    `INSERT (id, name, key_hash, role, owner, expires_at) … ON CONFLICT (id) DO UPDATE SET key_hash, role, owner,
    expires_at, name, revoked_at = NULL`.
  - `upsertBootstrap(key)` becomes `upsertWellKnown(SYS_ORG_ADMIN_API_KEY_ID, "system administrator", key, ADMIN,
    SYSTEM_ORG_ID, SYS_ORG_API_KEY_EXPIRES)`.
  - `installTrialValidatorKey` does the same with the trial constants, only when `FV_VALIDATOR_API_KEY` is set. If
    the key is malformed, it raises `StartupException`. If the variable is unset, it logs a WARN.
  - In V11, the existing row `name = 'bootstrap'` gets `id = SYS_ORG_ADMIN_API_KEY_ID` and
    `name = 'system administrator'`. This is skipped when either is already taken. In that case the startup upsert
    hits a unique violation on `name` or `key_hash`, which becomes a `StartupException` that names the conflicting
    key.
  - The CLI reserves the names `system administrator` and `trial validator` instead of `bootstrap`.
    `BOOTSTRAP_NAME` is replaced by the `Constant` names.
- **Rationale**: fixed ids make the seeded keys addressable across installations and idempotent on restart
  (SC-009). Upserting by id means a configured key rotation replaces the hash in place.
- **Note**: if the operator reuses the same secret for both seeded keys, it fails on the `key_hash` unique index,
  which is intended.

## R16. One source of truth for well-known values (FR-023)

- **Decision**:
  - `Constant` keeps the strings and adds parsed forms: `SYSTEM_ORG_UUID`, `TRIAL_ORG_UUID`, the key `UUID`s and
    `SYS_ORG_API_KEY_EXPIRES_AT = Instant.parse("2099-12-30T23:59:59Z")`.
  - The trial key constants become `TRIAL_ORG_VALIDATOR_API_KEY_ID/NAME/ROLE` (owner `TRIAL_ORG_ID`), replacing
    `SYS_ORG_VALIDATOR_*`.
  - `SYS_ORG_API_KEY_OWNER` is removed, since it duplicates `SYSTEM_ORG_ID`.
  - `Organisations.SYSTEM_ID` and `DEFAULT_SYSTEM_SHORT_NAME` delegate to `Constant`.
  - A unit test asserts that the SQL literals in V11 equal the `Constant` values (by reading the migration file),
    so the two can't drift.

## R17. Usage storage (FR-019, FR-021)

- **Decision**: `V12__validation_usage.sql` creates `fhir_validator_schema.validation_usage`. Columns are in
  [data-model.md](./data-model.md) §3.
  - `api_key_id UUID NULL REFERENCES api_keys(id) ON DELETE SET NULL`.
  - `org_id UUID NOT NULL`, without an FK, so usage never blocks org changes. Orgs are never deleted (spec 005).
  - Indexes: `(api_key_id, created_at)` and `(org_id, created_at)`.
  - No partitioning and no retention job. The README documents that operators must prune, with an example
    `DELETE … WHERE created_at < now() - interval '90 days'`.
- **Rationale**: an append-only table, written in batches. One row is about 250 bytes, so 1 M validations a day is
  about 250 MB a day before indexes. That makes the retention note necessary.

## R18. Performance (Principle VI, SC-007)

- **Decision**: the per-request overhead is:
  - two `ThreadMXBean` reads before and after (well under 1 µs each on HotSpot);
  - about 10 `System.nanoTime()` calls;
  - one small record allocation;
  - one `offer`, or one log line when `log` is on.

  The PR runs `perf/validate.js` before and after, with `FV_USAGE_SINK=none` and `FV_USAGE_SINK=db`, and records the
  results in `perf/results-4.1.0.md`. `getThreadAllocatedBytes` and thread CPU time are enabled by default on
  HotSpot. The startup check logs at INFO when they're unavailable.

## R19. Usage summary CLI (FR-022, optional)

- **Decision**: `usage summary (--org <shortName> | --key <name>) [--since <ISO-8601>]`, with `--since` defaulting
  to 24 h ago. It runs one aggregate `SELECT` (count, by outcome, sum/avg of total/validate ms, sum of CPU and
  allocated bytes) and prints JSON like the other commands. It is the last task, and it can be deferred without
  affecting the rest.
