# Research: Operator Switches for Enabled FHIR Versions and Global Access Control

**Feature**: `009-fhir-version-acl-toggles` | **Date**: 2026-11-12 | **Spec**: [spec.md](./spec.md)

The spec has no open clarifications. The items below record the design decisions made while reading the 4.1.0 code.
Each one gives the decision, the reason and the alternatives considered.

## Part A: Enabled FHIR versions

### R1. One `ServedVersions` object instead of the static `SupportedFhirVersion.enabled()`

- **Decision**: add `core/enums/ServedVersions` (immutable, ordered `EnumSet`). `ConfigLoader` builds it from
  `FV_ENABLED_FHIR_VERSIONS` and `ApplicationConfig.servedVersions()` exposes it. Every call site that today asks
  "which versions does this instance serve?" takes it from the config instead of `SupportedFhirVersion.enabled()` /
  `parseEnabled()`:
  - `ValidatorRegistry` (base slots; readiness only covers these, FR-004),
  - `ConfigSync` (its `states` map, so notifications and polls for other versions are ignored),
  - `ConfigRevisionRepository.current` (rows of other versions are dropped),
  - `ServiceMetrics` (per-version gauges),
  - `ValidationApi`, `ValidatorApi` (path parameter, see R4).

  `SupportedFhirVersion.enabled()` keeps its meaning, "versions this release can serve" (STU3, R4, R4B, R5; not R6).
  `ServedVersions.ALL` equals it and is the default.
- **Rationale**: there are about 12 call sites behind two static methods, so the change is mechanical. An instance
  object (not a static setter) keeps tests that start several configurations in one JVM independent.
- **Alternatives**: a static `SupportedFhirVersion.configure(Set)` was rejected because it holds global mutable state,
  which breaks parallel tests and Principle II's "derived, rebuildable" rule. Filtering only in `ValidatorRegistry` was
  rejected: `ConfigSync` would still sync, and the API would still accept disabled versions.

### R2. Parsing `FV_ENABLED_FHIR_VERSIONS`

- **Decision**: a new `Kind.STRING` entry `FV_ENABLED_FHIR_VERSIONS` / `fhir.enabledVersions`. Parsing: split on `,`,
  trim, drop empty items, upper-case (`Locale.ROOT`), de-duplicate. No items left means `ServedVersions.ALL`, with an
  INFO line. A name outside `SupportedFhirVersion.enabled()`, including `R6`, throws `IllegalStateException`, which
  `Bootstrap.main` already turns into `Configuration error: …` and exit 1 (FR-003). The message names the bad value and
  lists `STU3, R4, R4B, R5`. R6 gets its own message ("known but not served by this release").
- **Rationale**: this matches how other list settings (`FV_AUTH_ADMIN_CIDRS`) fail fast before anything starts.
- **Alternatives**: failing in warm-up was rejected because HTTP would already be listening.

### R3. Where the active flag lives

- **Decision**: add `is_active BOOLEAN NOT NULL DEFAULT TRUE` and `inactive_reason VARCHAR(32) NULL` to
  `fhir_validator_snapshots`, the table that already holds one row per (FHIR version, included IG package) with
  owner, sharing and terminology. The included set in `fhir_validator_logs.included_ig_packages` is **not** changed by
  deactivation, so exclude, PATCH and transfer keep working on inactive contexts with their existing code (FR-032).
  A check constraint keeps the two in step: `is_active = (inactive_reason IS NULL)`.
  `ConfigRevisionRepository.current` / `lockForUpdate` read `s.is_active`. `ConfigRow` gets `inactive()` (keys plus
  reason), and `ConfigSync.resolveAll` skips inactive keys (FR-006). Writes that insert a snapshot row (include, the
  terminology and access upserts) leave the defaults, so new contexts are active (FR-009).
- **Migration**: `V14__validator_context_active.sql` adds the columns. As in V8, it inserts a system-owned, shareable
  row for any included package that has none, so every included context has a row to carry the flag (FR-007). Existing
  rows get `TRUE` from the default.
- **Rationale**: a context is identified by (version, package) in this table. Removing keys from the included list
  would lose the information needed to restore them (FR-014) and would break exclude/PATCH, which lock that list.
- **Alternatives**: a per-version flag on `fhir_validator_logs` was rejected because FR-006 asks for a per-context flag
  and reason, and later features may deactivate single contexts. A new table was rejected as an extra join with no gain.

### R4. HTTP behaviour for versions that are supported but not served

- **Decision**: three helpers in `web/endpoint/VersionParams`:
  - `served(param)`: served version, or **400 `unsupported-fhir-version`**, as today (FR-005). Used by
    `POST /{version}/validate` and every other version-scoped read. The message lists the *served* versions instead of
    the fixed list.
  - `servedForInclude(param)`: as above, except that a version that is supported but not served gives **422
    `fhir-version-disabled`**: "IG validator contexts for R5 can't be added: this service serves R4 only
    (FV_ENABLED_FHIR_VERSIONS)". Used by `POST /{version}/validator` (FR-008). The check runs before the body is read
    or anything is resolved, so a refused request touches nothing (FR-008, SC-003).
  - `supported(param)`: any supported version. Used by `DELETE` and `PATCH /{version}/validator` (FR-032).
- `IgPackageService.include` checks the served set again (defence in depth, also for the CLI and tests that call the
  service directly). `exclude` and `changeSnapshotAccess` don't. For a version that isn't served, they skip
  `LocalApplyWait`, because this instance has no context to wait for, and answer once the transaction commits.
- **Rationale**: the spec keeps today's 400 for everything except adding a context (422) and admin actions on inactive
  contexts (FR-005 exceptions).
- **Alternatives**: changing the 400 to 404 or 422 everywhere was rejected because it would change a status code
  (Principle IV).

### R5. Startup reconciliation (FR-010 to FR-014)

- **Decision**: a new `ContextActivation.reconcile(pool, served)` runs in `Bootstrap` warm-up after the migration and
  key steps, and before `services.configSync().start()`. It runs as one transaction (`pool.withTransaction`):
  1. `UPDATE snapshots SET is_active = false, inactive_reason = 'fhir-version-disabled' WHERE is_active AND
     fhir_version <> ALL($served) RETURNING fhir_version, ig_package, owner`
  2. `UPDATE snapshots SET is_active = true, inactive_reason = NULL WHERE inactive_reason = 'fhir-version-disabled'
     AND fhir_version = ANY($served) RETURNING …`
  3. For each version touched: bump `fhir_validator_logs.revision` and `pg_notify(CHANNEL, {kind: IG_ACTIVATION,
     fhirVersion})`, which is delivered on commit.

  After the commit it logs:
  - one WARN per deactivated context: `Validator context deactivated: R4 hl7.fhir.us.core#6.1.0 (owner acme): FHIR
    version R4 is not enabled (FV_ENABLED_FHIR_VERSIONS)`, then a WARN summary;
  - one INFO per reactivated context, then an INFO summary.

  Nothing is logged when nothing changed. If the transaction fails, a `StartupException` is raised: exit 1 and nothing
  changed (FR-011).
- **Concurrency**: several instances starting at once run the same idempotent UPDATEs. Row locks serialise them, and
  the second one finds nothing to change.
- **Other instances** (FR-013): `ConfigSync.onNotification` treats `IG_ACTIVATION` like `IG`. An instance still
  serving R4 (during a rolling change) re-reads the row, sees the inactive keys and drops those contexts. The poll
  fallback catches it through the bumped revision.
- **Alternatives**: computing activity at read time from the env setting was rejected because the stored state is
  required (FR-006, auditable), and other instances couldn't learn about it.

### R6. Terminology allowlist and registry stay on all supported versions

- **Decision**: `TxRegistry` and `TxServerAllowlist` keep using `SupportedFhirVersion.enabled()`.
- **Rationale**: FR-032 lets an administrator change the terminology server of an inactive context. That check needs
  the allowlist for that version. The registry is fetched once per refresh period and is small.
- **Alternatives**: restricting the registry to served versions saves a few small requests, but PATCH on inactive
  contexts would then answer 503 `tx-registry-unavailable`.

### R7. Health report

- **Decision**: `/health` gains two top-level objects (contracts/http-api.md §3):
  - `fhirVersions`: `{served: [...], inactiveContexts: {count, items: [{fhirVersion, igPackage, owner, reason}]}}`,
    with `items` capped at 100;
  - `security`: `{aclSecurity: "enabled"|"disabled"}`. Code review of PR #72: `/health` is unauthenticated, so
    inactive contexts that are private are counted (`private`) but not listed, and whether admin operations are
    network-limited is not reported (the startup log says it).

  `HealthService.checkHealth` reads the inactive contexts with one indexed query (partial index
  `WHERE NOT is_active`) in the same round trip pattern as the PostgreSQL ping. `/health/readiness` is unchanged: it
  already lists only served versions, because `ValidatorRegistry` has slots only for them.
- **Rationale**: FR-015, FR-023 and SC-009 need it, and `/health` is already open and already carries the terminology
  state.
- **Alternatives**: a new `/info` endpoint was rejected (spec Assumptions).

## Part B: Global access control switch

### R8. Mode values and parsing

- **Decision**: there are three settings:
  - `FV_GLOBAL_DISABLED_ACL_SECURITY` (`auth.globalDisabledAclSecurity`);
  - `FV_ACL_MODE_CHANGE_CONFIRMED` (`auth.aclModeChangeConfirmed`);
  - `FV_AUTH_*`, unchanged.

  Both new settings use a new lenient `Kind.FLAG`: `true`, `1` and `yes` (any case, trimmed) mean true; empty or unset
  means false; any other value means false with a WARN naming the variable and the value. Echoing the value is safe:
  it's a flag, not a secret. `ApplicationConfig.aclSecurity()` returns `AclMode.ENABLED | DISABLED`.
- **Rationale**: the spec's "any other value means enabled" rule doesn't fit the strict `Kind.BOOL`, which rejects
  unknown values.

### R9. Disabled mode in the request path

- **Decision**: `ApiKeyAuthHandler` gets a `FixedCallers` dependency and a mode. In `rootHandler()`:
  1. **Administrative path**: the `FV_AUTH_ADMIN_CIDRS` check runs first, exactly as today (FR-030). Then, in
     disabled mode, the caller is set to `fixed.admin()`; in enabled mode `authorise(ctx, ADMIN)` runs as today.
  2. **`/{version}/validate`**: disabled mode uses `fixed.validate()` (the trial validator, FR-018/FR-020).
  3. **Package reads** (`/igs/{n}/{v}/dependencies`, `/conformance`; these accept either role): disabled mode uses
     `fixed.validate()` too (code review of PR #72): as the system administrator they would disclose every
     organisation's private packages to anonymous callers, and the admin allowlist doesn't cover them (FR-031).
  4. Everything else is open, as today.

  In disabled mode the handler never calls `getHeader("Authorization")` (FR-017). The keyed paths are one table of
  (matcher, administrative, fixed caller, roles), checked in order: administrative paths (ADMIN, fixed
  `system administrator`, admin allowlist) and validation plus package reads (VALIDATE or ADMIN, fixed
  `trial validator`).
  `ctx.put(CONTEXT_KEY, role)` and `ctx.put(CALLER_KEY, caller)` are set exactly as for a real key, so ownership
  (FR-019), access checks (FR-031: the trial validator sees shareable plus trial-owned contexts) and `UsageMeter`
  (FR-020, which reads `Caller.apiKeyId`) need no change. `repository.touch` is still called with the fixed key id, so
  the key's `last_used_*` audit keeps working.
- **Fixed caller resolution**: `FixedCallers` loads both keys by id once the startup ACL step has completed
  (`ApiKeyRepository.findById`, new, returning `KeyInfo` with owner active and revoked or expired state). It caches
  them for `FV_AUTH_CACHE_TTL_MS`, like key lookups. A deactivated organisation answers 403, as today. A fixed key
  that an operator revoked during the run answers 403 "This operation's fixed caller key is revoked; restart to
  re-create it". The next disabled-mode start re-randomises the key, which un-revokes it, as `upsertWellKnown` does
  for any new value.
- **Alternatives**: building `Caller` objects from constants was rejected because it would skip the organisation
  active check and the short name used in logs.

### R10. Gate requests in disabled mode until the mode step has committed

- **Decision**: `ApiKeyAuthHandler` holds a volatile `ready` flag that the startup ACL step (R11) sets after it
  commits. Until then every keyed path answers **503 `not-ready`** with `Retry-After: 5`, in **both** modes. In
  disabled mode this keeps an instance about to be refused from serving public requests. In enabled mode (code review
  of PR #72), a switch back from disabled would otherwise look keys up against the previous run's random hashes and
  cache the 401 for up to `FV_AUTH_CACHE_TTL_MS`. Before 4.2, keyed requests already got 503 while migrations ran, so
  the gate only extends that window to the end of the (short) access-control step.
- **Rationale**: HTTP listens before warm-up (Principle VI). Without the gate, an instance that FR-029 is about to
  refuse would already serve public ADMIN calls against the shared database during the seconds before it exits.
- **Alternatives**: starting HTTP after warm-up in disabled mode was rejected because liveness must answer at once.

### R11. The startup ACL step (FR-021 to FR-029), replacing `installBootstrapKey` and `installTrialValidatorKey`

- **Decision**: a new `core/security/AclModeStartup.run(services)` runs one transaction:
  1. `SELECT value FROM service_settings WHERE name = 'acl_security' FOR UPDATE`. A missing row means "no stored
     mode". Concurrent starters serialise on it: the first inserts with `ON CONFLICT DO NOTHING`, then everyone
     re-selects `FOR UPDATE`.
  2. **Mismatch check (FR-029)**: if a mode is stored, it differs from the configured mode, and the confirmation is not
     set, raise `StartupException("Access control mode change refused: stored mode is enabled, this instance is
     configured disabled. Set FV_ACL_MODE_CHANGE_CONFIRMED=true on the instances being switched, …")`. Roll back,
     exit 1. If it differs and the confirmation is set, log a WARN naming both modes. If the modes match and the
     confirmation is set, log a WARN asking for it to be removed.
  3. **Disabled mode**: generate two values (`SecureRandom`, 32 bytes, base64url without padding, 43 characters of
     `[A-Za-z0-9_-]`, 256 bits, FR-021). Upsert both fixed keys with `upsertWellKnown` (new transaction-aware overload
     taking a `SqlClient`). Log at INFO "FV_ADMIN_API_KEY / FV_VALIDATOR_API_KEY ignored: access control is disabled"
     for each variable that is set (FR-022). The values are not validated and not printed.
  4. **Enabled mode**: install the configured values as 4.1 does: an unset value changes nothing, with its WARN; a
     revoked value is not installed, with its ERROR. When the stored mode was `disabled`, also log at INFO "API keys
     re-synchronised from environment" (FR-025), plus one WARN per unset variable naming the key that stays unusable.
  5. `UPSERT service_settings (acl_security, <mode>, now(), <instance id>)`, then commit.

  After the commit it sets the gate (R10) and logs the FR-023 WARN in disabled mode: "ACCESS CONTROL IS DISABLED
  (FV_GLOBAL_DISABLED_ACL_SECURITY): no endpoint needs a key; administrative endpoints are limited to
  FV_AUTH_ADMIN_CIDRS | open from any network".
- **Enabled → enabled** (FR-027): this is the 4.1 path, so an ordinary rotation upserts the new value and no resync line
  is logged.
- **Rationale**: the stored mode (FR-024) is the only reliable switch signal. Comparing hashes can't tell a disabled run
  from a rotation (spec Assumptions). Because the enabled path is unchanged from 4.1, the resync is only a log line and
  a transaction boundary.
- **Alternatives**: keeping the two install steps separate and outside a transaction was rejected because FR-025 needs
  all or nothing.

### R12. Where the stored mode lives

- **Decision**: `V15__service_settings.sql` creates `service_settings (name VARCHAR(64) PK, value VARCHAR(256) NOT
  NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_by VARCHAR(128) NULL)`. `updated_by` holds the instance
  host name, for audit. The migration doesn't seed a row, so the first start after the upgrade needs no confirmation
  (spec edge case).
- **Rationale**: this is a general key/value table for future service-wide settings. It is small and readable with
  `psql`.
- **Alternatives**: a column on `organisations` or `api_keys` was rejected as the wrong owner.

### R13. Ignoring key settings in disabled mode

- **Decision**: `ConfigLoader` skips the 32–128 format check for `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY` when the
  mode is disabled, because the values are ignored (FR-022). `ApplicationConfig` keeps them redacted as today
  (`NoSecretsInLogsTest`).
- **Rationale**: a malformed, ignored value shouldn't stop a disabled-mode start. Switching back validates it again.

## Cross-cutting

### R14. Version and release

- **Decision**: `pom.xml` 4.1.0 → **4.2.0**. `CHANGELOG.md` gets a 4.2.0 entry with **Added** (both settings, health
  fields, 422) and **Security** (the disabled mode warning) sections, and no **Breaking** section. `docs/upgrade-4.2.md`
  is short: V14 and V15 run automatically, a rolling upgrade is fine (additive columns and table; 4.1 instances ignore
  them), and the mode-switch procedure. README gets a settings table and a security warning.
- **Rationale**: the user asked for 4.x, and the spec confirms no break (Principle IV).
- **Rolling upgrade check**: 4.1 instances running against the V15 schema ignore `is_active`. An inactive context could
  still be built by a 4.1 instance that serves that version. That is acceptable during a rolling upgrade and goes away
  once all instances run 4.2. This is documented.

### R15. Metrics

- **Decision**: two gauges with no high-cardinality tags:
  - `fv_acl_security_disabled` (0 or 1);
  - `fv_validator_contexts_inactive` (count, refreshed on startup reconcile and each `ConfigSync` poll).

  The existing per-version gauges iterate over the served versions.
- **Rationale**: Principle VII wants state visible in Prometheus as well as in logs.

### R16. Performance

- **Decision**: no benchmark run is required. The validation hot path, `FhirValidationService`, doesn't change. The auth
  handler in disabled mode does less work (no hashing or lookup; the fixed callers are cached). Expected effects:
  - with `FV_ENABLED_FHIR_VERSIONS=R4`, heap at rest drops by the core packages of three versions (roughly 150–250 MB,
    measured and recorded in `perf/results-4.2.0.md` for SC-002);
  - readiness comes sooner because three base contexts aren't built.
- **Rationale**: Principle VI requires benchmarks for hot-path changes, and this feature has none. The memory
  measurement still gives evidence for SC-002.
