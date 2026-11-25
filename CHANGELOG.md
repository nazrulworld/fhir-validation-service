# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project
uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Security

- The container runtime is pinned to `eclipse-temurin:17.0.21.1_2-jre-jammy` (by tag and digest), the Temurin build that
  fixes Java4Shell (CVE-2026-114938). The moving `17-jre-jammy` tag and the build cache could otherwise put an affected
  JDK into a rebuilt image. Dependabot now checks the Dockerfile base images weekly and opens PRs that bump tag and
  digest together, staying on Java 17 and Maven 3.

## [1.0.0] - 2026-11-24

First release. Version numbering starts here: earlier development builds are not supported. The database schema is
created by one baseline migration (`V18__baseline_1_0.sql`); a database from a development build keeps its migration
history, and one whose schema stops before V18 is refused at startup.

### Validation

- FHIR resource validation for STU3, R4, R4B and R5 at `POST /{version}/validate`, against the core specification or
  an included IG (`?ig=name#version`) with its dependencies. Results as a JSON summary or a FHIR `OperationOutcome`
  (JSON or XML); errors as RFC 9457 `application/problem+json` with an `X-Request-Id` correlation id. Every problem
  `type` URI resolves to its row in the user guide's problem-type table.
- Validator contexts built from two IGs: `POST /{version}/validator` takes `igPackages` with one or two entries, like
  the validator CLI's repeated `-ig`. Its key is `A#a|B#b`; it is also found by the reversed pair and, for an exact
  `A#a` with no single-IG context of that name, by its first IG. `ig` on validate accepts `name#version|name#version`.
  Conflicting dependency versions answer 400 `dependency-conflict`; a second two-IG context with the same first IG
  answers 409 `alias-conflict`.
- No spurious regex timeouts under load: a result hit by the validation engine's 500 ms regex limit is validated
  again before it is answered, at once and then, if needed, with no other validation running. Nothing is filtered or
  downgraded; re-checks are bounded in time and rate, so a hostile input can't pause the instance.
- Contexts of one FHIR version share the parsed definitions of identical package content on an instance, so a second
  context costs about its own packages. Results are identical to unshared contexts.

### IG packages

- Upload, or register from the FHIR package registries (exact versions, `latest` and version series `N.x`, `N.x.x`,
  `N.N.x`), with size and safety checks before unpacking. Validator contexts are included per FHIR version and shared
  by every instance.

### Terminology

- Terminology servers per included context, chosen from the official HL7 terminology registry, with a per-request
  budget and one code cache per instance (`FV_TX_CACHE_SIZE`, default 50,000).
- `tx` and `txMode` (`none`, `server`, `ecosystem`) on validate choose the terminology setting for one request; the
  context's setting is the default. A server must be on the allowlist (400 `tx-server-not-registered`, or 503
  `tx-registry-unavailable`). 200 responses report the setting used in `X-Tx-Mode`, `X-Tx-Server` and `X-Tx-Source`.
- Changing a context's terminology setting takes effect on the next request on every instance, without a rebuild.

### Access, organisations and usage

- Organisations own API keys, packages and contexts, with private and shareable content; a system and a trial
  organisation are built in. The `fv-admin` command line manages organisations, keys, sharing, usage and rate limits.
- API keys (hashed, optional expiry, last use recorded), an administrative network allowlist and trusted proxies.
- Usage accounting to the log and/or the database (`FV_USAGE_SINK`), including the terminology setting used.
- Operator switches: `FV_ENABLED_FHIR_VERSIONS` (serve fewer FHIR versions) and `FV_GLOBAL_DISABLED_ACL_SECURITY`
  (public mode), each guarded against an accidental fleet-wide change.

### Rate limits

- Per-minute caps on `POST /{version}/validate` for each API key and each organisation (all its keys together). Above
  a cap the answer is 429 `rate-limited` with `Retry-After`; nothing is validated, the refused request counts towards
  no cap and is not written to `validation_usage`. Responses to a limited key carry `RateLimit-Limit`,
  `RateLimit-Remaining` and `RateLimit-Reset`.
- Initial caps: 600 per key and 1200 per organisation by default, 60 for the trial organisation and 30 for the
  `trial validator` key. Keys of the system organisation are never limited; nothing is limited in public mode.
  `fv-admin limit set | remove | list` changes them on every instance within `FV_THROTTLE_CAPS_REFRESH_MS`, without a
  restart.
- Instances share their counts through PostgreSQL every `FV_THROTTLE_SYNC_INTERVAL_MS`; a cap holds fleet-wide within
  10 % (at least one request). When counts can't be shared, each instance enforces caps on its own counts.

### Operations

- Horizontal scaling on one PostgreSQL database, configuration propagation without restarts, liveness and readiness
  probes, Prometheus metrics (validation, packages, terminology, regex re-checks, rate limits), graceful shutdown.
- Container images for `linux/amd64` and `linux/arm64`; the user guide at
  <https://nazrulworld.github.io/fhir-validation-service/>, generated from the project wiki.
