# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Documentation

- The user guide is published at <https://nazrulworld.github.io/fhir-validation-service/>, generated from the project
  wiki on every wiki edit (spec 011). It has search, a dark mode and a phone layout, and publishes the repository
  files the wiki links to as reference pages: the configuration contract, a rendered OpenAPI reference, the changelog
  and the load-test results. No change to the service.
- The problem `type` URIs in error responses
  (`https://nazrulworld.github.io/fhir-validation-service/problems/<type>`) now resolve: each redirects to its row in
  the API Documentation's problem-type table, which says what the problem means and what to do. Before, they
  answered 404. No change to the service.

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
