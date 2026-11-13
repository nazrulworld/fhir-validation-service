# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

Retires every release before 4.0.0. A **minor release with a breaking change for pre-4.0 databases only**: 4.x
databases upgrade as usual, with a rolling restart, and 4.2 can still run on them.

### Changed
- Flyway migrations V1 to V9 are squashed into one `V9__baseline_4_0.sql`, which a new database runs in their place.
  A database that already ran the original V1 to V9 (any 4.x) keeps its migration history unchanged; only those
  retired versions are exempt from checksum validation, and V10 onwards are validated as before.

### Removed
- Upgrades from before 4.0.0: a database whose schema predates 4.0.0 is refused at startup. Upgrade it with 4.2.0
  first (see `docs/upgrade-4.3.md`).
- Upgrade guides `docs/upgrade-1.0.md`, `upgrade-2.0.md`, `upgrade-3.0.md`, `upgrade-3.1.md`, `upgrade-4.0.md` (still
  attached to their releases up to 4.2.0), the upgrade rehearsal scripts, `scripts/export-custom-profiles.sh`, and the
  `upgrade-rehearsal` CI job.

## [4.2.0] - 2026-11-13

Operator switches for the FHIR versions an instance serves and for API access control (spec 009). A **minor release
without breaking changes**: with the new settings unset, behaviour is unchanged. Migrations V14 and V15 are additive,
so a rolling upgrade from 4.1 is fine; turn the switches on only after every instance runs 4.2. See
[docs/upgrade-4.2.md](docs/upgrade-4.2.md).

### Added

- **`FV_ENABLED_FHIR_VERSIONS`** (e.g. `R4,R5`; unset = all):
  - only the listed versions' core specifications are loaded, and readiness waits only for them;
  - other versions get 400 `unsupported-fhir-version`, whose message now lists the served versions;
  - an unknown name, including `R6`, stops the start naming it.
- **422 `fhir-version-disabled`** on `POST /{version}/validator` for a supported version that isn't served. Nothing is
  stored.
- **Inactive validator contexts** (V14 adds `is_active` and `inactive_reason`):
  - at startup, contexts of versions that aren't served are marked inactive in one transaction and are not built
    (logged at WARN with a count);
  - they become active again when their version is listed again;
  - other instances follow through the configuration notification;
  - `DELETE` and `PATCH /{version}/validator` (and `fv-admin snapshot share|transfer`) still work on them.
- **`FV_FHIR_VERSIONS_CHANGE_CONFIRMED`**: the served versions are stored (`service_settings.fhir_versions`); a start
  whose versions differ is refused unless it is set, so one instance with a stale setting can't switch contexts off
  for the whole fleet.
- **`FV_GLOBAL_DISABLED_ACL_SECURITY`** (`true`/`1`/`yes`): public mode.
  - No endpoint needs a key, and the `Authorization` header is ignored.
  - Requests run as fixed callers: validation and the package reads as `trial validator` (shareable content only),
    administrative operations as `system administrator`.
  - Ownership, access rules and usage accounting are unchanged, and `FV_AUTH_ADMIN_CIDRS` still applies.
  - Packages and validator contexts are always stored as shareable: `shareable: false` on include, register, upload
    or PATCH is ignored with a `warnings` entry (PATCH can still make private content shareable).
- **`FV_ACL_MODE_CHANGE_CONFIRMED`**: a start whose access-control mode differs from the one stored by the previous
  start (V15 `service_settings.acl_security`) is refused unless it is set. Switching back to enforced access control
  re-installs `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY` in one transaction ("API keys re-synchronised from
  environment"), with no manual database change.
- `GET /health`: `fhirVersions` (served versions; inactive contexts: a count, how many are private, and the first 100
  shareable ones; private contexts are never named) and `security.aclSecurity`.
- Metrics: `fv_acl_security_disabled`, `fv_validator_contexts_inactive`.

### Security

- Public mode is opt-in, logged at WARN at every start, and reported in `/health` and `/metrics`.
- While it is on, both fixed-caller keys get new 256-bit random values (`SecureRandom`) at every start. These values
  are never logged, returned or stored readably, so no key value known while the service was open still works
  afterwards.
- A fixed key revoked while public mode is on stays revoked across restarts (logged at WARN), so revoking
  `trial validator` stops anonymous validation and package reads.
- A keyed request is answered 503 (in both modes) until the instance's startup access-control step has
  committed. An instance whose mode change is refused never serves public requests, and after a switch back the
  restored keys are never refused (and the refusal cached) against the previous run's random ones.

## [4.1.0] - 2026-11-11

API key hardening, key-only validation, usage accounting and well-known organisations (spec 008). A **minor release
with breaking changes**, documented as constitution 1.3.0 (Principle IV) requires; see
[docs/upgrade-4.1.md](docs/upgrade-4.1.md). Migrations V10 to V13 run automatically, but **not as a rolling upgrade**:
stop every 4.0 instance first.

### Breaking

- **Validation needs a key.** `POST /{version}/validate` and the package reads (`/igs/{name}/{version}/dependencies`,
  `/conformance`) answer **401** without a VALIDATE or ADMIN key. `openapi.yaml` now declares `security: apiKey` on
  every keyed operation.
- **`FV_AUTH_ANONYMOUS_VALIDATE` is removed** (also `auth.anonymousValidate` in `FV_CONFIG_FILE`); set, it logs a
  deprecation warning and is ignored.
- **The system organisation's id changes** to `00000000-0000-0000-0000-100000000001` (V11, with every owner reference
  and column default). 4.0 instances must not run against the upgraded database.
- **The bootstrap key is renamed** to `system administrator` with the fixed id `00000000-0000-0000-0000-200000000001`.
  `system administrator` and `trial validator` are reserved names for `fv-admin key create`. They can be revoked by
  name; a revoked value is never installed again (V13), so a leaked key can't come back, also after a rotation.

### Added

- **Trial organisation and key**: the `trial` organisation (`…-100000000002`) is seeded; `FV_VALIDATOR_API_KEY`
  installs its shared VALIDATE key (`trial validator`) at every start. Startup fails loudly when a well-known name is
  already taken or a configured key conflicts, and exits without the graceful drain. An instance without the variable
  leaves the stored key unchanged.
- **Admin network allowlist** `FV_AUTH_ADMIN_CIDRS`: administrative operations from other networks get 403 for every
  key, before any key lookup.
- **Trusted proxies** `FV_AUTH_TRUSTED_PROXIES`: the forwarding header is believed only from these peers (right-most
  untrusted address); `FV_AUTH_FORWARDED_HEADER` names the one header they write (`x-forwarded-for` by default, or
  `forwarded`), and the other is never read. When the client can't be determined (no header, every hop trusted, an
  unparseable entry or peer), administrative calls are refused.
- **Key expiry**: `fv-admin key create --expires <ISO-8601 instant>`; an expired key gets 401, also while cached.
  `key list` shows `expiresAt` and `lastUsedIp` (written within a second when a key is used from a new address).
- **Usage accounting** `FV_USAGE_SINK` (`none` by default, `log`, `db`, `log,db`): one record per authorised validation
  with key, organisation, outcome, issue counts, phase timings, CPU time and allocated bytes. The `log` sink writes one
  JSON line on the `nzi.fhir.validator.usage` logger; the `db` sink batches into `validation_usage` through a bounded
  queue (`FV_USAGE_QUEUE_SIZE`, `FV_USAGE_BATCH_SIZE`, `FV_USAGE_FLUSH_MS`) and never blocks a request. Retention is
  the operator's job. `fv-admin usage summary --org|--key [--since]` aggregates the table by request start time.
- **Metrics**: `fv_validation_cpu_seconds` and `fv_validation_allocated_bytes` (by version and outcome, whatever the
  sink), `fv_usage_written_total`, `fv_usage_dropped_total{reason}` (`queue_full`, `write_failed`, `closed`, `unattributed`,
  `log_full`), `fv_usage_queue_size`. No metric is labelled by
  key or organisation.

### Changed

- A malformed bearer value gets 401 without a key lookup.
- The `last_used_at` throttle map is bounded (10 000 keys).

## [4.0.0] - 2026-11-09

Terminology servers per included IG (spec 007), and validation served only by included IG contexts. **Breaking** for
clients that name an IG package that isn't included; see [docs/upgrade-4.0.md](docs/upgrade-4.0.md). Migration V9 runs
automatically; every existing included context keeps mode `none` and behaves as before.

### Changed (breaking)

- **`/{version}/include-ig` is renamed to `/{version}/validator`** (POST to include, PATCH to change sharing, owner or
  terminology, DELETE to exclude). Bodies, query parameters (`?ig=name#version`) and responses are unchanged; the old
  path answers 404. In `openapi.yaml` the operations are `validatorApiIncludeIg`, `validatorApiChangeIncludedIg` and
  `validatorApiExcludeIg`. The endpoint handlers moved from `ValidationApi` to the new `ValidatorApi`.
- **Validation never builds an IG context** (FR-021 to FR-025). `?ig=` naming a stored version that isn't included for
  the FHIR version answers **400 `invalid-request`** ("IG package name#version is not included for R4; include it
  first"); before, it was built on demand. `ig=name` / `name#latest` resolve to the highest **included** version the
  caller may use. Excluding an IG drops its context; naming it is refused until it is included again. Packages that
  aren't stored, or that the caller may not use, still answer 404.
- **`FV_IG_MAX_ON_DEMAND` and `FV_IG_BUILD_WAIT_MS` are retired.** Set, they log a deprecation warning and are ignored.
  The `on-demand` kind is gone from `/health` and `fv_validator_contexts`.

### Added

- **Terminology setting per included IG** (FR-001 to FR-005): mode `none` (default), `server` (one server, like
  `validator_cli.jar -tx`) or `ecosystem` (each code system routed to the server the official HL7 registry names for
  it; an optional own server replaces tx.fhir.org). Set it with `POST /{version}/validator` (`terminology`) and change
  it with `PATCH /{version}/validator` (the owner or the system organisation); every instance rebuilds the context.
- **Only official, open registry servers are contacted** (FR-006 to FR-012a): the list comes from
  `https://ecosystem.hl7.org/tx-reg` (fixed), is refreshed every `FV_TX_REGISTRY_REFRESH_MS` and kept in the database;
  servers must be listed for the FHIR version, healthy and open, on public addresses, and redirects are never followed.
  An unlisted server is refused with 400 `tx-server-not-registered`; with no list yet, only the built-in
  `tx.fhir.org` R4/R5 servers are allowed (503 `tx-registry-unavailable` otherwise).
- **Codes are checked locally first** (FR-013); only the rest reach a server, which only checks codes: definitions
  still come from packages only. The IG's own value sets (and code systems, as `tx-resource`) go with the request.
- **A failing server never fails validation** (FR-019, FR-019a): each call is bounded by `FV_TX_TIMEOUT_MS`, each
  request by `FV_TX_REQUEST_BUDGET_MS` of terminology time, and a failing server is skipped for a minute. Codes that
  could not be checked are reported as warnings; the answer is still 200.
- Configuration: `FV_TX_REGISTRY_REFRESH_MS`, `FV_TX_TIMEOUT_MS`, `FV_TX_REQUEST_BUDGET_MS`, `FV_TX_CACHE_SIZE`,
  `FV_TX_CACHE_TTL_MS`, `FV_TX_MAX_CONNECTIONS_PER_SERVER`.
- `/health` `terminology` section; metrics `fv_tx_calls_total`, `fv_tx_call_seconds`, `fv_tx_routing_lookups_total`,
  `fv_tx_code_cache_hits_total`, `fv_tx_allowlist_age_seconds`.
- Dependency: `ca.uhn.hapi.fhir:hapi-fhir-client` (same HAPI version), needed by HAPI's remote terminology client.

## Releases before 4.0.0

Releases 0.1.0 to 3.2.1 are retired (their releases, tags and images are removed). Their entries are kept in the
[4.2.0 changelog](https://github.com/nazrulworld/fhir-validation-service/blob/v4.2.0/CHANGELOG.md).
