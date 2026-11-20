# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.2.0] - 2026-11-20

Validator contexts built from two IGs (spec 012). No path, payload or status code of an existing request changes.

### Added

- `POST /{version}/validator` takes `igPackages`, a list of one or two `{igPackageId, igPackageVersion}` entries,
  instead of `igPackageId`/`igPackageVersion` (exactly one of the two forms; one entry behaves like `igPackageId`).
  Two entries build one validator context from both IGs, every dependency of each and the core specification, like
  the validator CLI's repeated `-ig`. Its key is `A#a|B#b` in the order given.
- Aliases: a two-IG context is also found by the reversed pair `B#b|A#a` and, for a validation request naming an exact
  `A#a` while no single-IG context `A#a` is included, by its first IG. A name without a version (or `latest`) only
  considers single-IG contexts, as before.
- `ig` on `POST /{version}/validate` accepts two IGs, `name#version|name#version` (URL-encode `|` as `%7C`): the key is
  looked up first, then the reversed pair; neither included is 400 `invalid-request`. `X-Validation-Context` always
  reports the key. PATCH and DELETE `/{version}/validator` and the `snapshot share|transfer` commands take the key or
  the reversed pair.
- Include responses and the `/health` context listings report `igPackages` (the context's IGs) and `aliases`.
- New problem types: 400 `dependency-conflict` (the two IGs need different versions of one package; the `conflicts`
  extension names it and the versions) and 409 `alias-conflict` (another two-IG context already starts with the same
  IG). Differences in `hl7.terminology*` and `hl7.fhir.uv.extensions*` are not conflicts: the highest version is used
  and named in `warnings`. That exception covers dependencies only, not an IG named in the request.

### Changed

- Database migration V17: `fhir_validator_snapshots.ig_package` and `validation_usage.ig` are `VARCHAR(256)` (were
  192), and two partial unique indexes keep two-IG contexts unique per first IG and per pair. Existing rows are not
  changed. Usage records keep `ig` values up to 256 characters.

### Documentation

- The user guide is published at <https://nazrulworld.github.io/fhir-validation-service/>, generated from the project
  wiki on every wiki edit (spec 011). It has search, a dark mode and a phone layout, and publishes the repository
  files the wiki links to as reference pages: the configuration contract, a rendered OpenAPI reference, the changelog
  and the load-test results. No change to the service.
- The problem `type` URIs in error responses
  (`https://nazrulworld.github.io/fhir-validation-service/problems/<type>`) now resolve: each redirects to its row in
  the API Documentation's problem-type table, which says what the problem means and what to do. Before, they
  answered 404. No change to the service.

### Upgrading from 1.1.0

- Migration V17 runs at start: it widens two `VARCHAR` columns (no table rewrite) and adds two partial unique indexes
  on `fhir_validator_snapshots`. A 1.1.0 instance still starts against the migrated schema.
- **Include two-IG contexts only once every instance runs 1.2.0.** A 1.1.0 instance reads a two-IG key as one package
  that isn't stored: it keeps serving base and single-IG validation, but its readiness stays `DOWN` while any two-IG
  context is included for a FHIR version it serves, so orchestrators stop routing to it.
- **Rolling back to 1.1.0:** first exclude every two-IG context (`DELETE /{version}/validator?ig=A%23a%7CB%23b`). No
  database restore is needed.

## [1.1.0] - 2026-11-16

Shared package definitions and a terminology setting per request (spec 010). No path, payload or status code of an
existing request changes.

### Added

- `POST /{version}/validate` takes `tx` (a terminology server base URL) and `txMode` (`none`, `server`,
  `ecosystem`) to choose the terminology setting for one request; the context's setting is the default. A server
  named in `tx` must be on the allowlist (400 `tx-server-not-registered`, or 503 `tx-registry-unavailable` while no
  registry list was ever obtained), and no call is made to a server that is not allowed. Malformed values answer 400
  `invalid-request` with a fixed text that never contains the address.
- 200 validation responses carry `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source` (`request` or `context`); they are
  exposed to browsers through CORS. The body is unchanged.
- Usage records (log and `db` sinks) record `txMode`, `txServer` and `txSource`; migration **V16** adds the nullable
  columns `tx_mode`, `tx_server`, `tx_source` to `validation_usage`.
- Contexts of one FHIR version share the parsed definitions of identical package content (same `name#version` and
  content hash) on an instance, so a second context costs about its own packages. Profiles a context completes stay
  private to it; results are identical to 1.0.0 (checked against a corpus recorded from 1.0.0). The memory check
  before a build counts shared packages for their indexes only.
- Metrics: `fv_packages_shared_entries`, `fv_packages_shared_expanded_bytes`,
  `fv_packages_shared_retained_estimate_bytes`, `fv_packages_shared_lookups_total`, `fv_tx_code_cache_misses_total`,
  `fv_tx_code_cache_size`, `fv_tx_code_cache_invalidations_total`, `fv_validations_tx_source_total`.

### Changed

- A context's terminology setting is no longer part of what it is built from: changing it (PATCH or include) takes
  effect on the next request on every instance, without a rebuild (amends spec 007 FR-004). A default server that is
  no longer on the allowlist is detected per request and fails closed for terminology only, as before, without a
  rebuild (amends spec 007 FR-010b).
- Code results are cached once per instance, separately per terminology scope (mode and server) and per context build,
  instead of per context (amends spec 007 FR-018). A "not checked" answer is never cached.
- **`FV_TX_CACHE_SIZE` now bounds the one cache of the instance**, and its default changes from 5000 (per context) to
  **50,000** (per instance, about 50 MB, reserved once in the memory budget). If you set it explicitly for a fleet with
  several terminology contexts, raise it accordingly.

### Upgrading from 1.0.0

- Migration V16 runs at start (nullable columns only; no table rewrite).
- During a rolling upgrade, instances still on 1.0.0 ignore `tx`/`txMode` (they answer with the context's setting and
  without the `X-Tx-*` headers) and still rebuild a context when its terminology setting changes. Rely on
  `tx`/`txMode` once every instance runs 1.1.0.

## [1.0.0] - 2026-11-13

First release. Version numbering starts here: earlier development builds are not supported. The database schema is
created by one baseline migration (V15); a database from a development build keeps its migration history, and one
whose schema stops before V15 is refused at startup.

- FHIR resource validation for STU3, R4, R4B and R5, against the core specification or one included IG package per
  request (`?ig=name#version`), with its dependencies; results as a JSON summary or a FHIR `OperationOutcome`.
- IG packages: upload, register from the FHIR package registries (exact versions, `latest` and version series), size
  and safety checks before unpacking, and validator contexts included per FHIR version and shared by every instance.
- Terminology servers per included IG, chosen from the official HL7 terminology registry, with a per-request budget
  and a code cache.
- Organisations owning API keys, packages and contexts, with private and shareable content; a system and a trial
  organisation; `fv-admin` command line for organisations, keys, sharing and usage.
- API keys (hashed, optional expiry, last use recorded), administrative network allowlist and trusted proxies.
- Usage accounting to the log and/or the database.
- Operator switches: `FV_ENABLED_FHIR_VERSIONS` (serve fewer FHIR versions) and `FV_GLOBAL_DISABLED_ACL_SECURITY`
  (public mode), each guarded against an accidental fleet-wide change.
- Horizontal scaling on one PostgreSQL database, configuration propagation without restarts, liveness, readiness
  and Prometheus metrics, graceful shutdown; container images for `linux/amd64` and `linux/arm64`.
