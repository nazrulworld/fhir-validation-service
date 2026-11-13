# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [4.2.0] - Unreleased

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
- **`FV_GLOBAL_DISABLED_ACL_SECURITY`** (`true`/`1`/`yes`): public mode.
  - No endpoint needs a key, and the `Authorization` header is ignored.
  - Requests run as fixed callers: validation and the package reads as `trial validator` (shareable content only),
    administrative operations as `system administrator`.
  - Ownership, access rules and usage accounting are unchanged, and `FV_AUTH_ADMIN_CIDRS` still applies.
- **`FV_ACL_MODE_CHANGE_CONFIRMED`**: a start whose access-control mode differs from the one stored by the previous
  start (V15 `service_settings.acl_security`) is refused unless it is set. Switching back to enforced access control
  re-installs `FV_ADMIN_API_KEY` / `FV_VALIDATOR_API_KEY` in one transaction ("API keys re-synchronised from
  environment"), with no manual database change.
- `GET /health`: `fhirVersions` (served versions, and inactive contexts with a count and the first 100) and
  `security` (`aclSecurity`, `adminNetworkRestricted`).
- Metrics: `fv_acl_security_disabled`, `fv_validator_contexts_inactive`.

### Security

- Public mode is opt-in, logged at WARN at every start, and reported in `/health` and `/metrics`.
- While it is on, both fixed-caller keys get new 256-bit random values (`SecureRandom`) at every start. These values
  are never logged, returned or stored readably, so no key value known while the service was open still works
  afterwards.
- A keyed request to a public-mode instance is answered 503 until the instance's startup access-control step has
  committed, so an instance whose mode change is refused never serves public requests.

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

## [3.2.1] - 2026-11-08

Fixes to IG package version resolution after 3.2.0 (spec 006 FR-019 to FR-024). No contract shape or status code
changes, and no migration or configuration change. Included contexts with a dependency declared as a version series,
or whose highest floating dependency version is private to another organisation, are rebuilt once after the upgrade,
because that dependency now resolves differently.

### Fixed

- **Dependencies declared with a version series are resolved** (spec 006 US5, FR-019, FR-021, FR-022). A package
  whose `package.json` declares a dependency such as `"dep": "1.0.x"` had that dependency fetched as the literal
  version `1.0.x` during registration, which failed and was dropped, and include reported it as "not stored;
  skipped". Registration now stores the highest match the registries list (else uses the highest stored match), and
  include, on-demand and snapshot builds resolve it to the highest stored match. A malformed dependency series is
  skipped with a warning; it never fails the request.
  - Included contexts whose series dependency was reported as missing now resolve it, so they are rebuilt once after
    the upgrade by the existing reconciliation.
- **Dependencies declared as `latest`, `current` or without a version resolve from the registries first** during
  registration, like a top-level `latest` (FR-020). A stored older version no longer hides a newer release.
- **Another organisation's private dependency version no longer blocks an IG** (FR-024). A dependency declared as a
  series, `latest`, `current` or without a version is now chosen among stored versions that are shareable or owned by
  the context's owner (the organisation that included it, or the IG package's owner for `?ig=` on demand). Before, the
  highest stored version was chosen whoever owned it, so include and `?ig=` failed with 424 when it was private to
  another organisation, even though a lower usable version was stored. Dependencies with an exact version are
  unchanged. Changing a dependency's sharing, or transferring an included context, can now rebuild contexts whose
  choice changes.
- **The `latest` registration fallback only uses stored versions the caller may use** (FR-023). When the highest
  stored version was private to another organisation, `POST /igs/register` answered 404 even though a lower usable
  version was stored; it now returns that version with the usual warning.

## [3.2.0] - 2026-11-08

IG versions can be requested as a series such as `1.2.x` on `POST /igs/register` and `POST /{version}/include-ig`
(GitHub issue #56). Additive: exact versions and `latest` behave as before, and no migration or configuration change
is needed. The only status change is for a request that could never succeed: a series in `?ig=` on validation is
now 400 instead of 404.

### Added

- **Version series on `POST /igs/register` and `POST /{version}/include-ig`** (GitHub issue #56, spec 006): a version
  `N.x`, `N.x.x` or `N.N.x` (for example `1.2.x`; `x` or `X`) resolves to the highest matching version, a release
  before a pre-release, with version parts compared numerically (`1.2.x` never matches `1.20.0`).
  - `register` matches against the versions listed by the configured package registries, then stores or returns
    that version as an exact registration would. When no registry can be consulted or none lists a matching version,
    it falls back to the highest matching stored version the caller may use, with a warning; 404
    `package-not-found` when there is none.
  - `include-ig` matches against the stored versions the caller may use and never contacts a registry. The context
    is pinned to the concrete version; including the series again later adds the then-highest match.
  - Any other value with an `x` part (`1.x.3`, `x.x.x`, `1.2.3.x`) is 400 `invalid-request`.
- `requestedVersion` on `IgRegistrationResponse` (name registrations) and `IncludeResponse`: the version value of the
  request (`latest`, an exact version or a series), next to the concrete version.

### Changed

- `POST /{version}/validate?ig=name#<series>` (for example `name#1.2.x`) is now 400 `invalid-request` saying the `ig`
  parameter needs an exact version; it was 404 `package-not-found`. The request could never succeed before, so this
  is not a breaking change (spec 006 clarification, 2026-11-08). Exact versions, `latest` and a bare name are
  unchanged.

### Fixed

- `openapi.yaml` declares `401 unauthorized` on `POST /{version}/validate`, returned when
  `FV_AUTH_ANONYMOUS_VALIDATE=false` and no valid key is sent (the behaviour is unchanged since 1.0.0).

## [3.1.0] - 2026-11-07

Organisations own API keys, IG packages and included IGs, and content can be private to its organisation or
shareable. Additive: with the defaults, previous clients see the same results, status codes and fields. Everything that
existed before the upgrade belongs to the built-in system organisation and stays shareable. Upgrade guide:
[docs/upgrade-3.1.md](docs/upgrade-3.1.md).

### Added

- **Organisations** (`organisations` table, migration V8): short and long name, type (`COMMERCIAL`,
  `NON_COMMERCIAL`; one built-in `SYSTEM` organisation), active flag, address and contact fields. Organisations are
  never deleted; the system organisation can't be deactivated.
- **Ownership and sharing**: API keys, stored IG packages and included IGs have an owner; packages and included IGs
  are shareable (default) or private. A caller may use what its organisation owns, what is shareable, and (the system
  organisation) everything; anonymous callers shareable content only. A context is usable only when it and every
  package it is built from are.
- `shareable` on `POST /igs/register` (JSON), `POST /igs/upload` (form field) and `POST /{version}/include-ig`;
  `owner` and `shareable` in their responses. Only the owner of an IG package can include it as private.
- `PATCH /igs/{name}/{version}` and `PATCH /{version}/include-ig?ig=name#version`: the owner (or the system
  organisation) changes `shareable`; the system organisation transfers ownership. Changes reach every instance within
  the propagation window and never rebuild a validator (new notification kind `IG_ACCESS`).
- Validation responses report the context's owner and sharing: `igOwner`, `igShareable` and the
  `X-Validation-Context-Owner` / `X-Validation-Context-Shareable` headers (all formats).
- **Command line** in the service jar: `org create|update|deactivate|activate|show|list`,
  `key create|revoke|list`, `package share|transfer`, `snapshot share|transfer`. JSON on stdout, exit codes
  0/1/2/3/4, every change logged at INFO without key values. The image adds `/usr/local/bin/fv-admin`.
- `FV_SYSTEM_ORG_*` settings for the system organisation's names and contact details, applied at every start.
- `openapi.yaml` (3.1.0) documents every new answer: `424` on `POST /{version}/include-ig` (a dependency the caller
  may not use), `401`/`403` on `GET /igs/{name}/{version}/dependencies` and `/conformance`, `503` on
  `PATCH /igs/{name}/{version}` (administrative admission), and the "not visible" meaning of `404 package-not-found`.

### Changed

- `/health` lists only contexts that are shareable and built from shareable packages; the others are counted in
  `privateContexts` by state. Readiness reasons don't name private contexts. Owners are never listed.
- A versionless `ig` resolves to the highest version the caller may use.
- A dependency that is missing now records the validation outcome `package_not_found` (was `error`), the same as a
  dependency the caller may not use, so `/metrics` can't tell the two apart.

### Security

- Content the caller may not use is answered exactly like content that isn't stored (404 `package-not-found`, or 424
  `dependency-not-found` without naming the dependency), on validation, include, exclude, register, PATCH and the
  package read endpoints, and no metric label reveals it.
- Upload or URL download of a `name#version` another organisation stored is refused with 409 `conflict` (the system
  organisation may still replace it; a replacement keeps the owner and sharing).
- Keys of a deactivated organisation get 403 `forbidden` within `FV_AUTH_CACHE_TTL_MS`.

## [3.0.0] - 2026-11-06

A breaking release. Each validation request now chooses the one IG package it is validated against
(`?ig=name#version`), the same model as the HL7 validator CLI's `-ig`, and several versions of one IG can be offered
side by side. Requests that name no IG are validated against the core specification only.

### Upgrade: do this first

- **Clients that relied on included IGs must name the IG.** Without `ig`, 2.x validated against every IG included for
  the FHIR version; 3.0.0 uses the core specification only. Add `?ig=name%23version` to keep the previous results.
- **Back up the database.** Restoring it is the only way back to 2.x; there is no down-migration.
- **Stop every 2.x instance before starting 3.0.0.** Migration V7 drops a column 2.x reads, so a mixed fleet is not
  supported, and `scripts/rolling-restart.sh` refuses to cross a major version.
- **Pin your image tag.** The `latest` tag now points to 3.0.0. A 2.x deployment on `latest` (the default `FV_TAG`)
  upgrades on its next pull, and requests without `ig` stop using included IGs, so set `FV_TAG` to your current 2.x
  release until your clients name their IG and you have backed up.
- Full procedure: [docs/upgrade-3.0.md](docs/upgrade-3.0.md). `scripts/upgrade-rehearsal-3.0.sh` rehearses it.

### Breaking

- `POST /{version}/validate` without `ig` validates against the core specification only (the base context). `?profile=`
  resolves only from the chosen IG, its dependencies and the core specification; a profile of another IG, even an
  included one, answers 400 `unknown-profile`.
- `POST /{version}/include-ig` adds a pinned context for the IG version and no longer replaces other versions of the
  same package. `IncludeResponse` gains `key {fhirVersion, igPackage}`; `resolved` lists only that IG's packages.
- `POST /igs/register` with `latest` (or no version) asks the package registries for the newest release and downloads
  it when it is new, instead of returning the highest stored version.
- `/health` details: per FHIR version, `base` plus a `contexts` list (`key`, `kind`, `state`, `packages`,
  `packageBytes`, `lastUsed`, `reason`), and a top-level `catalogRevision`.
- Migration V7 drops `fhir_validator_logs.resolved_ig_packages` (each instance resolves every included IG from the
  stored packages) and adds the single-row `fhir_package_catalog` revision table. Included IGs are kept, each as its
  own pinned context.

### Added

- `?ig=` on validate: `name#version`, `name#latest` or `name` (highest stored version). Any stored package that
  supports the FHIR version can be named; one that is not included is built on first use, from stored packages only,
  with one shared build for concurrent requests and a bounded wait.
- `X-Validation-Context` header on every 200 (`name#version` or `base`) and `igPackage` in the JSON response.
- `DELETE /{version}/include-ig?ig=name#version` (ADMIN key) stops pre-building one IG version; the package stays
  nameable in `ig`.
- Problem types `dependency-not-found` (424) and `insufficient-capacity` (503, `Retry-After: 30`); `package-not-found`
  (404) on validate.
- Settings `FV_IG_MAX_ON_DEMAND` (default 4) and `FV_IG_BUILD_WAIT_MS` (default `min(10000, requestTimeout/2)`).
  On-demand contexts are evicted least recently used first, by count and under memory pressure; pinned contexts never
  are.
- `IgRegistrationResponse.downloaded` and `.warnings`; a warning when registration falls back to the highest stored
  version because no registry could be consulted or none lists the package.
- Metrics `fv_validator_contexts`, `fv_validator_context_builds_total`, `fv_validator_context_evictions_total`,
  `fv_validator_context_lookups_total`, and the `fv_validations_total` outcome `package_not_found`.
- `docs/upgrade-3.0.md`, `scripts/upgrade-rehearsal-3.0.sh`, `scripts/bench-validate.sh`.

### Fixed

- Replacing a stored package (same `name#version`, new content) left validators and the parsed-package cache on the
  old content. Every context built from it is now invalidated on every instance.
- `POST /igs/register` with `latest` never noticed a newer release once any version was stored.
- Every method on an administrative path now requires the ADMIN key (previously only POST was classified as
  administrative).

### Unchanged

- Validation never contacts a package registry or downloads profiles; include and exclude never download.
- The spec 001 guarantees for configuration changes: one transaction, propagation to every instance within the
  window, 413 `package-too-large` before commit, and the local-apply wait with a warning.

## [2.0.0] - 2026-11-04

A breaking release. Profiles now come only from included IG packages, the same model as the HL7
validator CLI's `-ig` and `-profile`. Custom profile registration is removed.

### Upgrade: do this first

- **Export custom profiles before upgrading.** Migration V6 drops `fhir_profiles` unconditionally on the first 2.0.0
  start. Run `scripts/export-custom-profiles.sh --check`, then export, against the 1.x database. It writes uploadable IG
  packages, and stops without writing anything if one of them is already stored under the same name and version
  (choose another `--name` or `--package-version`).
- **Back up the database.** Restoring it is the only way back to 1.x; there is no down-migration.
- **Stop every 1.x instance before starting 2.0.0.** A mixed 1.x/2.0 fleet is not supported, and
  `scripts/rolling-restart.sh` refuses to cross a major version.
- **Pin your image tag.** The `latest` tag now points to 2.0.0. A 1.x deployment on `latest` (the default `FV_TAG`)
  upgrades on its next pull, so set `FV_TAG` to your current 1.x release until you have exported and backed up.
- Full procedure: [docs/upgrade-2.0.md](docs/upgrade-2.0.md). `scripts/upgrade-rehearsal-2.0.sh` rehearses it.

### Breaking

- `POST /{version}/register-profile` is removed and answers 404. Package profiles as an IG, then upload
  (`/igs/upload`) and include (`/{version}/include-ig`) it.
- `POST /{version}/validate?profile=…`: a requested profile that the core specification and the included IGs (with
  their dependencies) do not define answers **400 `unknown-profile`**, with `unresolvedProfiles`, and nothing is
  validated. This applies even when other requested profiles resolve. The answer is always problem JSON, whatever
  `Accept` asks for.
- `?profile=` values must be exact canonicals (`url` or `url|version`). Bare ids and partial URLs no longer resolve.
- `?profile=` accepts at most 10 distinct profiles of at most 1024 characters each. Beyond that it answers
  **400 `invalid-request`**, without echoing the URLs.
- Migration V6 drops `fhir_validator_schema.fhir_profiles` and the never-used `fhir_validator_logs.included_profiles`
  column. It runs unconditionally: the risk is accepted, and the export step above is the safeguard.

### Fixed

- An unknown requested profile was silently dropped when another requested profile resolved, so a resource could be
  reported as valid without ever being checked against a profile the client asked for.
- `appliedProfiles` listed profiles that were never resolved or applied. It now lists only applied profiles: the
  requested ones first, then resolvable `meta.profile` entries, each once.

### Removed

- The `fv_profile_count` metric, the PROFILE configuration change kind, and the readiness reason "profile changes
  could not be applied".

### Added

- `fv_validations_total` outcome `unknown_profile`.
- `scripts/export-custom-profiles.sh`, `scripts/upgrade-rehearsal-2.0.sh` and `docs/upgrade-2.0.md`. The export
  script:
  - writes one ready-to-upload IG package per FHIR version;
  - exits 1 without writing anything if a package it would generate is already stored with the same name and version,
    because uploading it would silently replace that package;
  - writes profiles stored under a FHIR version 2.0.0 does not support as raw files in `unsupported-<version>/`, with
    a warning, instead of dropping them.

### Unchanged

- An unknown `meta.profile` inside the resource is still reported as an ERROR issue (200, `valid: false`).
- Validation never downloads profiles or other conformance resources.

## [1.2.0] - 2026-10-28

The first release under an explicit license, PolyForm Noncommercial 1.0.0: free for noncommercial use, commercial use
needs a separate license. Upgrading from 1.1.0 changes nothing in the service: no migration, configuration, API or
behaviour change. The jar and image only gain license and notice files and two image labels.

### Changed

- The project is now licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). It is free for any
  noncommercial use; commercial use needs a separate license from Md Nazrul Islam (email2nazrul@gmail.com). Releases up
  to and including 1.1.0 were published without an explicit license. Contributions are accepted under the same license
  (`CONTRIBUTING.md`).

### Added

- The jar and the container image carry the license and the third-party notices for every bundled library. In the jar:
  `LICENSE.md`, `META-INF/THIRD-PARTY.txt` (library, version, license), `META-INF/NOTICE` (merged notices),
  `META-INF/THIRD-PARTY-CLARIFICATIONS.md` and `META-INF/third-party-licenses/`. In the image: `/app/LICENSE.md` and
  `/app/licenses/`. The listing is generated from the shipped dependencies on every build. A library with a missing or
  unlisted license fails the build until its license text and a clarification are added.
- Image labels `org.opencontainers.image.licenses` (`PolyForm-Noncommercial-1.0.0`) and
  `org.opencontainers.image.authors`. The published POM declares the license, the developer and the SCM.

## [1.1.0] - 2026-10-27

Upgrading from 1.0.0 needs no data migration: migration V5 (one new nullable column) is applied on startup, and a
1.0.0 instance still runs against the migrated schema, so rolling back needs no database restore. Review
`FV_MEMORY_LIMIT` in your `.env` (the default is now `4g`). Clients can see three new refusals: include-ig answers 400
for an IG declared for another FHIR version and 413 for an IG set that would not fit in the heap, and administrative
calls answer 503 `overloaded` beyond `FV_ADMIN_MAX_PENDING`.

### Added

- `FV_ADMIN_MAX_PENDING` (default 4): administrative operations (upload, register, include-ig, register-profile) have
  their own per-instance admission limit, separate from validation. Beyond it they get 503 `overloaded` with
  `Retry-After` instead of queueing without limit for the package workers. New metrics `fv_admin_pending` and
  `fv_admin_max_pending`.

- CI: a startup smoke test of the arm64 image under QEMU (liveness, schema migration, graceful stop), so both
  published platforms are smoke-tested on every PR as the container contract states.

- `POST /{version}/register-profile` returns a `warnings` array (additive), like include-ig.

- `scripts/rolling-restart.sh` (`make rolling-restart`): restarts the two `cluster` instances one at a time under
  k6 load and fails at 0.1% or more failed requests (SC-008). `perf/validate-during-ig-load.js` takes the IG
  archive from `PACKAGE`, `PACKAGE_NAME` and `PACKAGE_VERSION`, so SC-004 can be measured with a 20 MB IG.

### Changed

- The default `FV_MEMORY_LIMIT` (compose container memory) is now `4g` instead of `2g`, so a 20 MB IG such as
  `hl7.fhir.r4.examples` fits with the default settings (SC-004). The sizing rule is unchanged: 4g fits IG sets up to
  about 350 MB expanded. Hosts running the `cluster` profile need room for two such limits. An `.env` that still sets
  `FV_MEMORY_LIMIT=2g` keeps the old limit.

- Include-ig refuses an IG that declares other FHIR versions than the one in the path (for example an R5 IG included
  into R4) with 400 `invalid-request`; before, it was accepted silently. Packages that declare no FHIR version are
  still accepted.

- Image build: the Maven and dependency download step is retried up to 3 times, so a transient network error on
  repo.maven.apache.org no longer fails the build.

- Full validator builds use at most `FV_PACKAGE_WORKERS - 1` package workers (at least 1). The free worker keeps
  profile registration and propagation, upload parsing and DNS resolution moving while validators rebuild.

- Migration V5 adds `fhir_implementation_guides.expanded_bytes`, recorded when a package is stored.

### Fixed

- An IG set too large for the heap no longer crashes the instance in a loop. With the default 2 GB container, including
  a 20 MB IG (`hl7.fhir.r4.examples#4.0.1`) exhausted the heap, `-XX:+ExitOnOutOfMemoryError` ended the process, and
  since the include was committed every restart crashed again. Include-ig now estimates the build's heap and refuses a
  set that does not fit with 413 `package-too-large` before committing; a committed set that an instance cannot build
  makes only that version unavailable (503, reason in `/health`) and is retried. README, `compose.yaml`,
  `.env.example` and the configuration contract give a memory sizing rule (a 20 MB IG needs 3 GB or more; the new 4 GB
  default fits it).

- A profile registered while this instance was rebuilding the same version's validator could wait until the
  request timed out and get 503 "retry later" although it was already committed. The answer now waits at most
  min(10 s, `FV_HTTP_REQUEST_TIMEOUT_MS`/2), then returns 200 with a "still applying" warning.

- `scripts/upgrade-rehearsal.sh` runs in CI (the old database is stopped cleanly between steps, and the 1.0.0
  validator gets the rehearsal's own env file) and also checks that a registered profile is still in effect after
  the upgrade, with the same error-level issues as on 0.1.2.

- `FV_PACKAGE_FETCH_TIMEOUT_MS` now also bounds name resolution of a package source, including any wait for a free
  package worker.

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
